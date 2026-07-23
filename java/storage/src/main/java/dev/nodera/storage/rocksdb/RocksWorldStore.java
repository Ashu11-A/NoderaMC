package dev.nodera.storage.rocksdb;

import dev.nodera.core.Bytes;
import dev.nodera.core.consensuscert.QuorumCertificate;
import dev.nodera.core.consensuscert.EntityTransferCertificate;
import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.crypto.Encodable;
import dev.nodera.core.crypto.HashService;
import dev.nodera.core.crypto.TypeTags;
import dev.nodera.core.event.CommittedEventEnvelope;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.core.state.StateRoot;
import dev.nodera.core.state.EntityTransferRecord;
import dev.nodera.storage.CertificateStore;
import dev.nodera.storage.Checkpoint;
import dev.nodera.storage.CheckpointStore;
import dev.nodera.storage.ContentId;
import dev.nodera.storage.ContentStore;
import dev.nodera.storage.EventChainGuard;
import dev.nodera.storage.GenesisManifest;
import dev.nodera.storage.RegionEventStore;
import dev.nodera.storage.RegionOrder;
import dev.nodera.storage.StorageException;
import dev.nodera.storage.WorldStore;
import dev.nodera.storage.TransferStore;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;
import org.rocksdb.WriteBatch;
import org.rocksdb.WriteOptions;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The durable full-archive {@link WorldStore} (Task 9, Phase 5): the same seam as the in-memory
 * event-sourced store, over RocksDB + {@link FsContentStore}. Layout:
 *
 * <ul>
 *   <li>{@code events}: {@code regionKey(16) ++ eventId(u64 BE)} → canonical
 *       {@link CommittedEventEnvelope}. Append is one WAL-backed {@link WriteBatch} (event +
 *       region registration), so a kill can never tear an entry in half.</li>
 *   <li>{@code checkpoints}: {@code regionKey(16) ++ version(u64 BE)} → canonical
 *       {@link Checkpoint}.</li>
 *   <li>{@code certificates}: {@code sha256(canonical bytes)} → canonical
 *       {@link QuorumCertificate} (content-addressed, idempotent).</li>
 *   <li>{@code regions}: {@code regionKey} → canonical {@link RegionId} (reverse map for
 *       {@link RegionEventStore#regions()} and head recovery).</li>
 *   <li>{@code transfers}: {@code transferId(u64 BE)} → latest monotonic
 *       {@link EntityTransferRecord} for startup recovery.</li>
 *   <li>{@code default}: {@code "genesis"} → canonical {@link GenesisManifest}.</li>
 * </ul>
 *
 * <p>{@code regionKey} is the first 16 bytes of SHA-256 over the region's canonical encoding —
 * fixed-width so BE-suffixed ids iterate in order under RocksDB's default bytewise comparator.
 *
 * <p><b>Replay-on-boot window.</b> Heads ({@code lastEventId} + head root per region) are not
 * stored separately — they are recovered from the log tail on open, so there is no second record
 * that could disagree with the log after a crash. Append-time validation (strictly monotonic ids,
 * unbroken {@code prevRoot → resultingRoot} chain) then continues from the recovered head.
 *
 * @Thread-context confined to the owning thread; not thread-safe (single-writer commit path).
 */
public final class RocksWorldStore implements WorldStore, AutoCloseable {

    private static final byte[] GENESIS_KEY = "genesis".getBytes(StandardCharsets.UTF_8);
    private static final int REGION_KEY_BYTES = 16;

    private final RocksLifecycle lifecycle;
    private final WriteOptions writeOptions;
    private final HashService hashes;
    private final GenesisManifest genesis;
    private final FsContentStore content;
    private final RocksRegionEventStore events;
    private final RocksCheckpointStore checkpoints;
    private final RocksCertificateStore certificates;
    private final RocksTransferStore transfers;

    private RocksWorldStore(RocksLifecycle lifecycle, WriteOptions writeOptions, HashService hashes,
                            GenesisManifest genesis, FsContentStore content) {
        this.lifecycle = lifecycle;
        this.writeOptions = writeOptions;
        this.hashes = hashes;
        this.genesis = genesis;
        this.content = content;
        this.events = new RocksRegionEventStore();
        this.checkpoints = new RocksCheckpointStore();
        this.certificates = new RocksCertificateStore();
        this.transfers = new RocksTransferStore();
        this.events.recoverHeads();
    }

    /**
     * Open (creating if missing) the store under {@code dir}.
     *
     * @param genesis    the world this store belongs to. Stored on first open; a reopen against a
     *                   different genesis throws (a store must never silently mix worlds).
     * @param syncWrites {@code true} to fsync every commit write (the configurable fsync policy);
     *                   {@code false} leaves durability to the OS + WAL (crash-consistent either
     *                   way — an unsynced tail is lost whole, never torn).
     */
    public static RocksWorldStore open(Path dir, GenesisManifest genesis, HashService hashes,
                                       boolean syncWrites) {
        if (genesis == null) {
            throw new IllegalArgumentException("genesis must not be null");
        }
        if (hashes == null) {
            throw new IllegalArgumentException("hashes must not be null");
        }
        RocksLifecycle lifecycle = RocksLifecycle.open(dir.resolve("db"));
        WriteOptions writeOptions = new WriteOptions().setSync(syncWrites);
        try {
            byte[] stored = lifecycle.db().get(lifecycle.handle("default"), GENESIS_KEY);
            if (stored == null) {
                lifecycle.db().put(lifecycle.handle("default"), writeOptions, GENESIS_KEY, encode(genesis));
            } else {
                GenesisManifest existing = GenesisManifest.decode(new CanonicalReader(stored));
                if (!existing.equals(genesis)) {
                    throw new StorageException("store at " + dir + " belongs to a different world: "
                            + "stored genesis " + existing + " != " + genesis);
                }
            }
        } catch (RocksDBException e) {
            writeOptions.close();
            lifecycle.close();
            throw new StorageException("cannot read/store genesis at " + dir, e);
        } catch (RuntimeException e) {
            writeOptions.close();
            lifecycle.close();
            throw e;
        }
        return new RocksWorldStore(lifecycle, writeOptions, hashes, genesis,
                new FsContentStore(dir, hashes));
    }

    @Override
    public GenesisManifest genesis() {
        return genesis;
    }

    @Override
    public ContentStore content() {
        return content;
    }

    @Override
    public RegionEventStore events() {
        return events;
    }

    @Override
    public CheckpointStore checkpoints() {
        return checkpoints;
    }

    @Override
    public CertificateStore certificates() {
        return certificates;
    }

    @Override
    public TransferStore transfers() {
        return transfers;
    }

    @Override
    public void close() {
        writeOptions.close();
        lifecycle.close();
    }

    // --- helpers ---

    private static byte[] encode(Encodable value) {
        CanonicalWriter w = new CanonicalWriter();
        value.encode(w);
        return w.toBytes().toArray();
    }

    private byte[] regionKey(RegionId region) {
        return Arrays.copyOf(hashes.sha256(encode(region)).toArray(), REGION_KEY_BYTES);
    }

    private static byte[] suffixed(byte[] prefix, long id) {
        byte[] key = Arrays.copyOf(prefix, prefix.length + Long.BYTES);
        for (int i = 0; i < Long.BYTES; i++) {
            key[prefix.length + i] = (byte) (id >>> (8 * (Long.BYTES - 1 - i)));
        }
        return key;
    }

    private static long suffixOf(byte[] key) {
        long id = 0;
        for (int i = key.length - Long.BYTES; i < key.length; i++) {
            id = (id << 8) | (key[i] & 0xFF);
        }
        return id;
    }

    private static boolean hasPrefix(byte[] key, byte[] prefix) {
        if (key.length < prefix.length) {
            return false;
        }
        return Arrays.equals(key, 0, prefix.length, prefix, 0, prefix.length);
    }

    private static byte[] transferKey(long transferId) {
        return suffixed(new byte[0], transferId);
    }

    /** The append-only per-region event log over the {@code events} column family. */
    private final class RocksRegionEventStore implements RegionEventStore {

        private final Map<RegionId, Long> lastIds = new HashMap<>();
        private final Map<RegionId, StateRoot> heads = new HashMap<>();

        /** Recover per-region heads from the log tail (the replay-on-boot window). */
        void recoverHeads() {
            for (RegionId region : regions()) {
                byte[] prefix = regionKey(region);
                try (RocksIterator it = lifecycle.db().newIterator(lifecycle.handle(RocksLifecycle.CF_EVENTS))) {
                    it.seekForPrev(suffixed(prefix, -1L)); // -1 = u64 max: the last key under prefix
                    if (it.isValid() && hasPrefix(it.key(), prefix)) {
                        CommittedEventEnvelope tail =
                                CommittedEventEnvelope.decode(new CanonicalReader(it.value()));
                        lastIds.put(region, tail.eventId());
                        heads.put(region, tail.resultingRoot());
                    }
                }
            }
        }

        @Override
        public void append(CommittedEventEnvelope event) {
            appendAtomic(List.of(event));
        }

        @Override
        public void appendAtomic(List<CommittedEventEnvelope> events) {
            if (events == null || events.isEmpty()) {
                throw new IllegalArgumentException("events must not be null/empty");
            }
            Map<RegionId, Long> nextIds = new HashMap<>();
            Map<RegionId, StateRoot> nextHeads = new HashMap<>();
            for (CommittedEventEnvelope event : events) {
                if (event == null) {
                    throw new IllegalArgumentException("event must not be null");
                }
                RegionId region = event.region();
                long lastId = nextIds.getOrDefault(
                        region, lastIds.getOrDefault(region, -1L));
                StateRoot head = nextHeads.containsKey(region)
                        ? nextHeads.get(region) : heads.get(region);
                EventChainGuard.checkAppend(event, lastId, head);
                nextIds.put(region, event.eventId());
                nextHeads.put(region, event.resultingRoot());
            }
            try (WriteBatch batch = new WriteBatch()) {
                for (CommittedEventEnvelope event : events) {
                    byte[] prefix = regionKey(event.region());
                    batch.put(lifecycle.handle(RocksLifecycle.CF_EVENTS),
                            suffixed(prefix, event.eventId()), encode(event));
                    batch.put(lifecycle.handle(RocksLifecycle.CF_REGIONS),
                            prefix, encode(event.region()));
                }
                lifecycle.db().write(writeOptions, batch);
            } catch (RocksDBException e) {
                throw new StorageException("cannot atomically append " + events.size() + " events", e);
            }
            for (CommittedEventEnvelope event : events) {
                lastIds.put(event.region(), event.eventId());
                heads.put(event.region(), event.resultingRoot());
            }
        }

        @Override
        public List<CommittedEventEnvelope> readFrom(RegionId region, long fromEventId) {
            byte[] prefix = regionKey(region);
            List<CommittedEventEnvelope> out = new ArrayList<>();
            try (RocksIterator it = lifecycle.db().newIterator(lifecycle.handle(RocksLifecycle.CF_EVENTS))) {
                it.seek(suffixed(prefix, Math.max(fromEventId, 0)));
                while (it.isValid() && hasPrefix(it.key(), prefix)) {
                    out.add(CommittedEventEnvelope.decode(new CanonicalReader(it.value())));
                    it.next();
                }
            }
            return out;
        }

        @Override
        public long lastEventId(RegionId region) {
            return lastIds.getOrDefault(region, -1L);
        }

        @Override
        public Optional<StateRoot> headRoot(RegionId region) {
            return Optional.ofNullable(heads.get(region));
        }

        @Override
        public List<RegionId> regions() {
            List<RegionId> out = new ArrayList<>();
            try (RocksIterator it = lifecycle.db().newIterator(lifecycle.handle(RocksLifecycle.CF_REGIONS))) {
                it.seekToFirst();
                while (it.isValid()) {
                    out.add(RegionId.decode(new CanonicalReader(it.value())));
                    it.next();
                }
            }
            out.sort(RegionOrder.BY_DIMENSION_XZ);
            return out;
        }
    }

    /** The per-region checkpoint index over the {@code checkpoints} column family. */
    private final class RocksCheckpointStore implements CheckpointStore {

        @Override
        public void put(Checkpoint checkpoint) {
            if (checkpoint == null) {
                throw new IllegalArgumentException("checkpoint must not be null");
            }
            Optional<Checkpoint> current = latest(checkpoint.region());
            if (current.isPresent() && checkpoint.version().value() <= current.get().version().value()) {
                throw new IllegalStateException("checkpoint version " + checkpoint.version().value()
                        + " not greater than latest " + current.get().version().value()
                        + " for " + checkpoint.region());
            }
            byte[] key = suffixed(regionKey(checkpoint.region()), checkpoint.version().value());
            try {
                lifecycle.db().put(lifecycle.handle(RocksLifecycle.CF_CHECKPOINTS), writeOptions,
                        key, encode(checkpoint));
            } catch (RocksDBException e) {
                throw new StorageException("cannot store checkpoint for " + checkpoint.region(), e);
            }
        }

        @Override
        public Optional<Checkpoint> latest(RegionId region) {
            byte[] prefix = regionKey(region);
            try (RocksIterator it = lifecycle.db().newIterator(lifecycle.handle(RocksLifecycle.CF_CHECKPOINTS))) {
                it.seekForPrev(suffixed(prefix, -1L));
                if (it.isValid() && hasPrefix(it.key(), prefix)) {
                    return Optional.of(Checkpoint.decode(new CanonicalReader(it.value())));
                }
            }
            return Optional.empty();
        }

        @Override
        public Optional<Checkpoint> at(RegionId region, SnapshotVersion version) {
            byte[] key = suffixed(regionKey(region), version.value());
            try {
                byte[] value = lifecycle.db().get(lifecycle.handle(RocksLifecycle.CF_CHECKPOINTS), key);
                return value == null
                        ? Optional.empty()
                        : Optional.of(Checkpoint.decode(new CanonicalReader(value)));
            } catch (RocksDBException e) {
                throw new StorageException("cannot read checkpoint for " + region, e);
            }
        }

        @Override
        public List<Checkpoint> all(RegionId region) {
            byte[] prefix = regionKey(region);
            List<Checkpoint> out = new ArrayList<>();
            try (RocksIterator it = lifecycle.db().newIterator(lifecycle.handle(RocksLifecycle.CF_CHECKPOINTS))) {
                it.seek(prefix);
                while (it.isValid() && hasPrefix(it.key(), prefix)) {
                    out.add(Checkpoint.decode(new CanonicalReader(it.value())));
                    it.next();
                }
            }
            return out;
        }
    }

    /** The content-addressed certificate store over the {@code certificates} column family. */
    private final class RocksCertificateStore implements CertificateStore {

        @Override
        public ContentId put(QuorumCertificate certificate) {
            if (certificate == null) {
                throw new IllegalArgumentException("certificate must not be null");
            }
            byte[] bytes = encode(certificate);
            ContentId id = ContentId.of(hashes, bytes);
            try {
                lifecycle.db().put(lifecycle.handle(RocksLifecycle.CF_CERTIFICATES), writeOptions,
                        id.hash().toArray(), bytes);
            } catch (RocksDBException e) {
                throw new StorageException("cannot store certificate " + id, e);
            }
            return id;
        }

        @Override
        public ContentId put(EntityTransferCertificate certificate) {
            if (certificate == null) {
                throw new IllegalArgumentException("certificate must not be null");
            }
            byte[] bytes = encode(certificate);
            ContentId id = ContentId.of(hashes, bytes);
            try {
                lifecycle.db().put(lifecycle.handle(RocksLifecycle.CF_CERTIFICATES), writeOptions,
                        id.hash().toArray(), bytes);
            } catch (RocksDBException e) {
                throw new StorageException("cannot store transfer certificate " + id, e);
            }
            return id;
        }

        @Override
        public Optional<QuorumCertificate> get(ContentId id) {
            return getByHash(id.hash());
        }

        @Override
        public Optional<QuorumCertificate> getByHash(Bytes hash) {
            try {
                byte[] value = lifecycle.db().get(lifecycle.handle(RocksLifecycle.CF_CERTIFICATES),
                        hash.toArray());
                return value == null || encodedTag(value) != TypeTags.QUORUM_CERTIFICATE
                        ? Optional.empty()
                        : Optional.of(QuorumCertificate.decode(new CanonicalReader(value)));
            } catch (RocksDBException e) {
                throw new StorageException("cannot read certificate by hash", e);
            }
        }

        @Override
        public Optional<EntityTransferCertificate> getTransferByHash(Bytes hash) {
            try {
                byte[] value = lifecycle.db().get(lifecycle.handle(RocksLifecycle.CF_CERTIFICATES),
                        hash.toArray());
                return value == null || encodedTag(value) != TypeTags.ENTITY_TRANSFER_CERT
                        ? Optional.empty()
                        : Optional.of(EntityTransferCertificate.decode(new CanonicalReader(value)));
            } catch (RocksDBException e) {
                throw new StorageException("cannot read transfer certificate by hash", e);
            }
        }

        @Override
        public boolean has(ContentId id) {
            return getByHash(id.hash()).isPresent() || getTransferByHash(id.hash()).isPresent();
        }
    }

    private static int encodedTag(byte[] value) {
        if (value.length < 2) {
            return -1;
        }
        return ((value[0] & 0xFF) << 8) | (value[1] & 0xFF);
    }

    /** Latest durable transfer stage over the {@code transfers} column family. */
    private final class RocksTransferStore implements TransferStore {

        @Override
        public void put(EntityTransferRecord record) {
            if (record == null) {
                throw new IllegalArgumentException("transfer record must not be null");
            }
            EntityTransferRecord current = get(record.transferId()).orElse(null);
            TransferStore.checkAdvance(current, record);
            try {
                lifecycle.db().put(
                        lifecycle.handle(RocksLifecycle.CF_TRANSFERS), writeOptions,
                        transferKey(record.transferId()), encode(record));
            } catch (RocksDBException e) {
                throw new StorageException(
                        "cannot persist transfer " + record.transferId(), e);
            }
        }

        @Override
        public Optional<EntityTransferRecord> get(long transferId) {
            try {
                byte[] value = lifecycle.db().get(
                        lifecycle.handle(RocksLifecycle.CF_TRANSFERS), transferKey(transferId));
                return value == null
                        ? Optional.empty()
                        : Optional.of(EntityTransferRecord.decode(new CanonicalReader(value)));
            } catch (RocksDBException e) {
                throw new StorageException("cannot read transfer " + transferId, e);
            }
        }

        @Override
        public List<EntityTransferRecord> recoverable() {
            return all().stream()
                    .filter(record -> record.stage() != EntityTransferRecord.Stage.COMMITTED
                            && record.stage() != EntityTransferRecord.Stage.ABORTED)
                    .toList();
        }

        @Override
        public List<EntityTransferRecord> all() {
            List<EntityTransferRecord> out = new ArrayList<>();
            try (RocksIterator it = lifecycle.db().newIterator(
                    lifecycle.handle(RocksLifecycle.CF_TRANSFERS))) {
                it.seekToFirst();
                while (it.isValid()) {
                    out.add(EntityTransferRecord.decode(new CanonicalReader(it.value())));
                    it.next();
                }
            }
            return out;
        }
    }
}
