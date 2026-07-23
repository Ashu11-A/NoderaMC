package dev.nodera.peer.validation;

import dev.nodera.committee.VotePersistence;
import dev.nodera.core.Bytes;
import dev.nodera.core.consensuscert.QuorumCertificate;
import dev.nodera.core.crypto.CanonicalEncoder;
import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.crypto.HashService;
import dev.nodera.core.region.RegionEpoch;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.core.state.StateRoot;
import dev.nodera.shadow.SnapshotDeltaApplier;
import dev.nodera.simulation.RegionExecutionRequest;
import dev.nodera.simulation.RegionExecutionResult;
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

/** Durable prepared-candidate index backed by a world store and one atomic metadata file. */
public final class WorldStoreVotePersistence implements VotePersistence {

    private static final long MAGIC = 0x4E564F54L; // NVOT
    private static final int VERSION = 1;

    private final WorldStore store;
    private final HashService hashes;
    private final Path file;
    private final Map<Key, Candidate> candidates = new LinkedHashMap<>();

    public WorldStoreVotePersistence(WorldStore store, HashService hashes, Path file) {
        if (store == null || hashes == null || file == null) {
            throw new IllegalArgumentException("vote persistence arguments must not be null");
        }
        this.store = store;
        this.hashes = hashes;
        this.file = file;
        load();
    }

    @Override
    public synchronized void prepare(
            RegionExecutionRequest request, RegionExecutionResult result) {
        if (request == null || result == null
                || !request.context().region().equals(result.delta().region())
                || !request.context().baseVersion().equals(result.delta().baseVersion())) {
            throw new IllegalArgumentException("prepared candidate does not match request anchors");
        }
        RegionSnapshot snapshot = SnapshotDeltaApplier.apply(
                request.snapshot(), result.delta(), request.context().tickTo());
        StateRoot root = StateRoot.of(hashes.hash(snapshot));
        if (!root.equals(result.resultingRoot())
                || !root.equals(result.delta().resultingRoot())) {
            throw new IllegalStateException("prepared snapshot does not match result root");
        }
        byte[] snapshotBytes = CanonicalEncoder.encode(snapshot).toArray();
        ContentId snapshotId = ContentId.of(hashes, snapshotBytes);
        Candidate candidate = new Candidate(
                request.context().region(), request.context().epoch(),
                request.context().baseVersion(), StateRoot.of(hashes.hash(request.batch())),
                result.resultingRoot(), StateRoot.of(hashes.hash(result.delta())),
                snapshotId, Bytes.empty());
        Key key = candidate.key();
        Candidate current = candidates.get(key);
        if (current != null) {
            if (!current.samePrepared(candidate)) {
                throw new IllegalStateException("conflicting candidate already prepared for " + key);
            }
            return;
        }
        ContentId storedSnapshot = store.content().put(snapshotBytes);
        if (!storedSnapshot.equals(snapshotId)) {
            throw new IllegalStateException("content store changed prepared snapshot identity");
        }
        Map<Key, Candidate> next = new LinkedHashMap<>(candidates);
        next.put(key, candidate);
        persist(next);
        candidates.put(key, candidate);
    }

    @Override
    public synchronized void commit(QuorumCertificate certificate) {
        if (certificate == null || certificate.votes().isEmpty()) {
            throw new IllegalArgumentException("certificate must carry votes");
        }
        Key key = new Key(certificate.region(), certificate.epoch(), certificate.version());
        Candidate candidate = candidates.get(key);
        if (candidate == null
                || !candidate.resultingRoot().equals(certificate.resultingRoot())
                || certificate.votes().stream().anyMatch(vote ->
                !vote.batchRoot().equals(candidate.batchRoot())
                        || !vote.resultingRoot().equals(candidate.resultingRoot())
                        || !vote.transitionRoot().equals(candidate.transitionRoot()))) {
            throw new IllegalStateException("certificate does not match durable prepared candidate");
        }
        ContentId certificateId = ContentId.of(
                hashes, CanonicalEncoder.encode(certificate).toArray());
        if (!candidate.certificateHash().isEmpty()) {
            if (!candidate.certificateHash().equals(certificateId.hash())) {
                throw new IllegalStateException("prepared candidate already bound to another certificate");
            }
            return;
        }
        ContentId storedCertificate = store.certificates().put(certificate);
        if (!storedCertificate.equals(certificateId)) {
            throw new IllegalStateException("certificate store changed canonical identity");
        }
        Candidate committed = candidate.withCertificate(certificateId.hash());
        Map<Key, Candidate> next = new LinkedHashMap<>(candidates);
        next.put(key, committed);
        persist(next);
        candidates.put(key, committed);
    }

    /**
     * The latest quorum-committed snapshot for {@code region} — the resulting snapshot of the
     * highest-base-version candidate that has a bound certificate — decoded from the content
     * store, or empty if no commit ever landed. Session reopen resumes the validated lane from
     * this head instead of re-deriving the INITIAL all-AIR snapshot (issue #34 / L-50).
     */
    public synchronized java.util.Optional<RegionSnapshot> latestCommittedSnapshot(RegionId region) {
        if (region == null) {
            throw new IllegalArgumentException("region must not be null");
        }
        Candidate best = null;
        for (Candidate candidate : candidates.values()) {
            if (!candidate.region().equals(region) || candidate.certificateHash().isEmpty()) {
                continue;
            }
            if (best == null || candidate.baseVersion().value() > best.baseVersion().value()) {
                best = candidate;
            }
        }
        if (best == null) {
            return java.util.Optional.empty();
        }
        ContentId snapshotId = best.snapshotId();
        byte[] bytes = store.content().get(snapshotId).orElseThrow(() ->
                new StorageException("committed candidate snapshot is missing: " + snapshotId));
        RegionSnapshot snapshot = RegionSnapshot.decode(new CanonicalReader(bytes));
        if (!StateRoot.of(hashes.hash(snapshot)).equals(best.resultingRoot())) {
            throw new StorageException("committed snapshot does not hash to its certificate root");
        }
        return java.util.Optional.of(snapshot);
    }

    private void load() {
        if (!Files.exists(file)) {
            return;
        }
        try {
            CanonicalReader reader = new CanonicalReader(Files.readAllBytes(file));
            if (reader.readU32() != MAGIC || reader.readU16() != VERSION) {
                throw new StorageException("unsupported vote journal header: " + file);
            }
            for (Candidate candidate : reader.readList(Candidate::decode)) {
                if (candidates.putIfAbsent(candidate.key(), candidate) != null) {
                    throw new StorageException("duplicate vote candidate " + candidate.key());
                }
                if (!store.content().has(candidate.snapshotId())) {
                    throw new StorageException("prepared candidate snapshot is missing: "
                            + candidate.snapshotId());
                }
                if (!candidate.certificateHash().isEmpty()
                        && store.certificates().getByHash(candidate.certificateHash()).isEmpty()) {
                    throw new StorageException(
                            "prepared candidate certificate is missing: "
                                    + candidate.certificateHash().toShortHex(8));
                }
            }
            if (reader.available() != 0) {
                throw new StorageException("trailing bytes in vote journal " + file);
            }
        } catch (IOException | RuntimeException e) {
            if (e instanceof StorageException storage) {
                throw storage;
            }
            throw new StorageException("cannot load vote journal " + file, e);
        }
    }

    private void persist(Map<Key, Candidate> next) {
        List<Candidate> ordered = new ArrayList<>(next.values());
        ordered.sort(Comparator
                .comparing((Candidate candidate) -> candidate.region().toString())
                .thenComparingLong(candidate -> candidate.epoch().value())
                .thenComparingLong(candidate -> candidate.baseVersion().value()));
        CanonicalWriter writer = new CanonicalWriter();
        writer.writeU32(MAGIC).writeU16(VERSION);
        writer.writeList(ordered, (w, candidate) -> candidate.encode(w));
        try {
            AtomicFileWriter.write(file, writer.toByteArray());
        } catch (IOException e) {
            throw new StorageException("cannot persist vote journal " + file, e);
        }
    }

    private record Key(RegionId region, RegionEpoch epoch, SnapshotVersion baseVersion) {
    }

    private record Candidate(
            RegionId region,
            RegionEpoch epoch,
            SnapshotVersion baseVersion,
            StateRoot batchRoot,
            StateRoot resultingRoot,
            StateRoot transitionRoot,
            ContentId snapshotId,
            Bytes certificateHash) {

        Key key() {
            return new Key(region, epoch, baseVersion);
        }

        boolean samePrepared(Candidate other) {
            return region.equals(other.region) && epoch.equals(other.epoch)
                    && baseVersion.equals(other.baseVersion) && batchRoot.equals(other.batchRoot)
                    && resultingRoot.equals(other.resultingRoot)
                    && transitionRoot.equals(other.transitionRoot)
                    && snapshotId.equals(other.snapshotId);
        }

        Candidate withCertificate(Bytes hash) {
            return new Candidate(region, epoch, baseVersion, batchRoot, resultingRoot,
                    transitionRoot, snapshotId, hash);
        }

        void encode(CanonicalWriter writer) {
            region.encode(writer);
            epoch.encode(writer);
            baseVersion.encode(writer);
            batchRoot.encode(writer);
            resultingRoot.encode(writer);
            transitionRoot.encode(writer);
            snapshotId.encode(writer);
            writer.writeBytes(certificateHash);
        }

        static Candidate decode(CanonicalReader reader) {
            return new Candidate(
                    RegionId.decode(reader), RegionEpoch.decode(reader),
                    SnapshotVersion.decode(reader), StateRoot.decode(reader),
                    StateRoot.decode(reader), StateRoot.decode(reader),
                    ContentId.decode(reader), reader.readBytesValue());
        }
    }
}
