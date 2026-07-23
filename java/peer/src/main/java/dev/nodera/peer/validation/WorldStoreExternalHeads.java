package dev.nodera.peer.validation;

import dev.nodera.core.consensuscert.ServerAuthorityCertificate;
import dev.nodera.core.crypto.CanonicalEncoder;
import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.crypto.HashService;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.core.state.StateRoot;
import dev.nodera.storage.ContentId;
import dev.nodera.storage.StorageException;
import dev.nodera.storage.WorldStore;
import dev.nodera.storage.io.AtomicFileWriter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Durable per-region head index for <b>external</b> (server-authoritative) commits — the half of
 * the validated lane the quorum path does not persist (issue #34 / L-50). Quorum commits already
 * reach the world store through {@link WorldStoreVotePersistence} (prepared snapshot in the
 * content store + certificate in the certificate store); external deltas were memory-applied
 * only, so a session reopen fell back to the INITIAL all-AIR snapshots. This journal closes that
 * gap: every external commit stores the certified resulting snapshot content-addressed and
 * records the region's head pointer in one atomically-rewritten metadata file, and
 * {@link #head(RegionId)} replays it on open.
 *
 * <p>Only the latest head per region is retained — an external commit supersedes the region's
 * previous external head (the store keeps earlier snapshots content-addressed; the journal is an
 * index, not a log).
 *
 * <p>Thread-context: all public methods {@code synchronized}; safe from any thread.
 */
public final class WorldStoreExternalHeads {

    private static final long MAGIC = 0x4E455848L; // NEXH
    private static final int VERSION = 1;

    private final WorldStore store;
    private final HashService hashes;
    private final Path file;
    private final Map<RegionId, Head> heads = new LinkedHashMap<>();

    public WorldStoreExternalHeads(WorldStore store, HashService hashes, Path file) {
        if (store == null || hashes == null || file == null) {
            throw new IllegalArgumentException("external heads arguments must not be null");
        }
        this.store = store;
        this.hashes = hashes;
        this.file = file;
        load();
    }

    /**
     * Record an applied external commit: persist {@code snapshot} content-addressed and advance
     * the region's durable head pointer. Idempotent for a re-applied identical commit.
     *
     * @param snapshot    the re-extracted resulting snapshot whose hash the certificate certifies.
     * @param certificate the server-authority certificate that authorised the delta.
     */
    public synchronized void externalCommitted(
            RegionSnapshot snapshot, ServerAuthorityCertificate certificate) {
        if (snapshot == null || certificate == null) {
            throw new IllegalArgumentException("snapshot and certificate must not be null");
        }
        StateRoot root = StateRoot.of(hashes.hash(snapshot));
        if (!snapshot.region().equals(certificate.region())
                || !snapshot.version().equals(certificate.resultingVersion())
                || !root.equals(certificate.resultingRoot())) {
            throw new IllegalArgumentException(
                    "external head snapshot does not match its certificate");
        }
        byte[] snapshotBytes = CanonicalEncoder.encode(snapshot).toArray();
        ContentId snapshotId = store.content().put(snapshotBytes);
        Head head = new Head(
                snapshot.region(), snapshot.version(), root, snapshotId,
                CanonicalEncoder.encode(certificate));
        Map<RegionId, Head> next = new LinkedHashMap<>(heads);
        next.put(head.region(), head);
        persist(next);
        heads.put(head.region(), head);
    }

    /**
     * @return the region's durable external head snapshot, decoded from the content store and
     *         root-verified, or empty if no external commit was ever recorded for it.
     */
    public synchronized Optional<RegionSnapshot> head(RegionId region) {
        if (region == null) {
            throw new IllegalArgumentException("region must not be null");
        }
        Head head = heads.get(region);
        if (head == null) {
            return Optional.empty();
        }
        byte[] bytes = store.content().get(head.snapshotId()).orElseThrow(() ->
                new StorageException("external head snapshot is missing: " + head.snapshotId()));
        RegionSnapshot snapshot = RegionSnapshot.decode(new CanonicalReader(bytes));
        if (!StateRoot.of(hashes.hash(snapshot)).equals(head.root())) {
            throw new StorageException("external head snapshot does not hash to its recorded root");
        }
        return Optional.of(snapshot);
    }

    /** @return the recorded head version for {@code region}, or empty. */
    public synchronized Optional<SnapshotVersion> headVersion(RegionId region) {
        return Optional.ofNullable(heads.get(region)).map(Head::version);
    }

    private void load() {
        if (!Files.exists(file)) {
            return;
        }
        try {
            CanonicalReader reader = new CanonicalReader(Files.readAllBytes(file));
            if (reader.readU32() != MAGIC || reader.readU16() != VERSION) {
                throw new StorageException("unsupported external heads header: " + file);
            }
            for (Head head : reader.readList(Head::decode)) {
                if (heads.putIfAbsent(head.region(), head) != null) {
                    throw new StorageException("duplicate external head for " + head.region());
                }
                if (!store.content().has(head.snapshotId())) {
                    throw new StorageException(
                            "external head snapshot is missing: " + head.snapshotId());
                }
            }
            if (reader.available() != 0) {
                throw new StorageException("trailing bytes in external heads file " + file);
            }
        } catch (IOException | RuntimeException e) {
            if (e instanceof StorageException storage) {
                throw storage;
            }
            throw new StorageException("cannot load external heads file " + file, e);
        }
    }

    private void persist(Map<RegionId, Head> next) {
        List<Head> ordered = new ArrayList<>(next.values());
        ordered.sort(Comparator.comparing(head -> head.region().toString()));
        CanonicalWriter writer = new CanonicalWriter();
        writer.writeU32(MAGIC).writeU16(VERSION);
        writer.writeList(ordered, (w, head) -> head.encode(w));
        try {
            AtomicFileWriter.write(file, writer.toByteArray());
        } catch (IOException e) {
            throw new StorageException("cannot persist external heads file " + file, e);
        }
    }

    private record Head(
            RegionId region,
            SnapshotVersion version,
            StateRoot root,
            ContentId snapshotId,
            dev.nodera.core.Bytes certificateBytes) {

        void encode(CanonicalWriter writer) {
            region.encode(writer);
            version.encode(writer);
            root.encode(writer);
            snapshotId.encode(writer);
            writer.writeBytes(certificateBytes);
        }

        static Head decode(CanonicalReader reader) {
            return new Head(
                    RegionId.decode(reader), SnapshotVersion.decode(reader),
                    StateRoot.decode(reader), ContentId.decode(reader),
                    reader.readBytesValue());
        }
    }
}
