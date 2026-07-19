package dev.nodera.distribution;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.HashService;
import dev.nodera.core.crypto.symmetric.ContentKey;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.ChunkColumnState;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.core.state.SnapshotVersion;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Task 24 rule-6 integration coverage: real region snapshots advance through the bounded stream into
 * hash-keyed physical replica stores, with activation serving as the end-to-end receipt proof.
 */
final class ActivePlayerStreamIT {

    private static final RegionId REGION = DistFixtures.region(3, -4);
    private static final int PIECE_TARGET = 512;
    private static final long OPEN_BUDGET = 64L * 1024 * 1024;

    /** Receiver whose inventory is derived from physically persisted, hash-keyed piece bytes. */
    private static final class PhysicalReceiver implements ActivePlayerStream.Receiver {

        private static final class Replica {
            private final Map<Bytes, Bytes> pieces = new LinkedHashMap<>();
            private final Map<RegionId, PieceManifest> active = new HashMap<>();
            private final Map<Bytes, Integer> storeAttempts = new HashMap<>();
        }

        private final Map<NodeId, Replica> replicas = new LinkedHashMap<>();
        private final HashService hashes = new HashService();
        private int storesToFail;

        PhysicalReceiver(List<NodeId> holders) {
            for (NodeId holder : holders) {
                replicas.put(holder, new Replica());
            }
        }

        void failNextStores(int count) {
            storesToFail = count;
        }

        @Override
        public Set<Bytes> heldPieceHashes(NodeId holder) {
            return Set.copyOf(replica(holder).pieces.keySet());
        }

        @Override
        public boolean storePiece(
                NodeId holder, PieceManifest manifest, int pieceIndex, Bytes payload) {
            Replica replica = replica(holder);
            Bytes pieceHash = manifest.piece(pieceIndex).pieceHash();
            replica.storeAttempts.merge(pieceHash, 1, Integer::sum);
            if (storesToFail > 0) {
                storesToFail--;
                return false;
            }
            if (!manifest.verifyPiece(pieceIndex, payload)) {
                return false;
            }
            replica.pieces.put(pieceHash, payload);
            return payload.equals(replica.pieces.get(pieceHash));
        }

        @Override
        public boolean activateManifest(NodeId holder, PieceManifest manifest) {
            Replica replica = replica(holder);
            byte[] blob = new byte[Math.toIntExact(manifest.totalLength())];
            for (Piece piece : manifest.pieces()) {
                Bytes payload = replica.pieces.get(piece.pieceHash());
                if (payload == null || !manifest.verifyPiece(piece.index(), payload)) {
                    return false;
                }
                payload.copyInto(blob, Math.toIntExact(piece.offset()));
            }
            if (!hashes.sha256(blob).equals(manifest.blob().hash())) {
                return false;
            }
            replica.active.put(manifest.region(), manifest);
            return true;
        }

        PieceManifest activeManifest(NodeId holder, RegionId region) {
            return replica(holder).active.get(region);
        }

        int storeAttempts(NodeId holder, Bytes pieceHash) {
            return replica(holder).storeAttempts.getOrDefault(pieceHash, 0);
        }

        Map<Bytes, Integer> storeAttempts(NodeId holder) {
            return Map.copyOf(replica(holder).storeAttempts);
        }

        private Replica replica(NodeId holder) {
            Replica replica = replicas.get(holder);
            if (replica == null) {
                throw new IllegalArgumentException("unknown holder " + holder);
            }
            return replica;
        }
    }

    @Test
    void tenCommittedVersionsReachFivePhysicalHoldersWithinOneBatch() {
        List<NodeId> holders = holders(5, 10L);
        PhysicalReceiver receiver = new PhysicalReceiver(holders);
        ActivePlayerStream stream = new ActivePlayerStream(receiver, OPEN_BUDGET);
        ActivePlayerStream.CommitListener listener = stream.commitListener();
        List<RegionSnapshotSplitter.Layout> layouts = evolvingLayouts(10, PIECE_TARGET);

        long fullRetransmissionPieces = 0;
        for (int i = 0; i < layouts.size(); i++) {
            if (i > 0) {
                assertThat(stream.nextWindow().piecesSent()).isZero();
            }
            RegionSnapshotSplitter.Layout layout = layouts.get(i);
            fullRetransmissionPieces += (long) layout.manifest().pieceCount() * holders.size();

            ActivePlayerStream.CommitResult result = listener.onCommitted(
                    ActivePlayerStream.CommittedSnapshot.from(layout), holders);

            assertThat(result.disposition()).isEqualTo(ActivePlayerStream.OfferDisposition.ACCEPTED);
            assertThat(result.window().versionsCompleted()).isEqualTo(1);
            assertThat(result.window().budgetExhausted()).isFalse();
            assertThat(result.window().metrics().pendingRegionCount()).isZero();
            for (NodeId holder : holders) {
                assertThat(receiver.activeManifest(holder, REGION))
                        .as("holder %s activates version %d in its commit window", holder, i + 1)
                        .isEqualTo(layout.manifest());
            }
        }

        ActivePlayerStream.Metrics metrics = stream.metrics();
        assertThat(metrics.versionsOffered()).isEqualTo(10);
        assertThat(metrics.versionsCompleted()).isEqualTo(10);
        assertThat(metrics.maxStalenessBatches()).isEqualTo(1);
        assertThat(metrics.pendingRegionCount()).isZero();
        assertThat(metrics.piecesSent()).isLessThan(fullRetransmissionPieces);

        // A physically-held hash is reused across manifests and never sent again.
        for (NodeId holder : holders) {
            assertThat(receiver.storeAttempts(holder).values())
                    .allMatch(attempts -> attempts == 1);
        }
    }

    @Test
    void tightWindowsCoalesceAndConvergeWithoutCrossingTheirBudget() {
        List<NodeId> holders = holders(1, 30L);
        PhysicalReceiver receiver = new PhysicalReceiver(holders);
        List<RegionSnapshotSplitter.Layout> layouts = evolvingLayouts(2, PIECE_TARGET);
        long budget = layouts.stream()
                .flatMap(layout -> layout.manifest().pieces().stream())
                .mapToLong(Piece::length)
                .max()
                .orElseThrow();
        assertThat(layouts.get(0).manifest().pieceCount()).isGreaterThan(1);

        ActivePlayerStream stream = new ActivePlayerStream(receiver, budget);
        ActivePlayerStream.CommitResult first = stream.onCommitted(
                ActivePlayerStream.CommittedSnapshot.from(layouts.get(0)), holders);
        assertThat(first.window().bytesSent()).isLessThanOrEqualTo(budget);
        assertThat(first.window().metrics().pendingRegionCount()).isEqualTo(1);

        ActivePlayerStream.CommitResult newer = stream.onCommitted(
                ActivePlayerStream.CommittedSnapshot.from(layouts.get(1)), holders);
        assertThat(newer.disposition()).isEqualTo(ActivePlayerStream.OfferDisposition.COALESCED);
        assertThat(first.window().bytesSent() + newer.window().bytesSent())
                .isLessThanOrEqualTo(budget);
        assertThat(newer.window().metrics().pendingRegionCount()).isEqualTo(1);

        for (int guard = 0; guard < 100 && stream.metrics().pendingRegionCount() > 0; guard++) {
            ActivePlayerStream.WindowResult window = stream.nextWindow();
            assertThat(window.bytesSent()).isLessThanOrEqualTo(budget);
            assertThat(window.oversizePieceSent()).isFalse();
        }

        assertThat(stream.metrics().pendingRegionCount()).isZero();
        assertThat(stream.metrics().versionsOffered()).isEqualTo(2);
        assertThat(stream.metrics().versionsCompleted()).isEqualTo(1);
        assertThat(receiver.activeManifest(holders.get(0), REGION))
                .isEqualTo(layouts.get(1).manifest());
    }

    @Test
    void anOversizePieceAdvancesOnlyOncePerOtherwiseEmptyWindow() {
        List<NodeId> holders = holders(1, 40L);
        PhysicalReceiver receiver = new PhysicalReceiver(holders);
        RegionSnapshotSplitter.Layout layout = evolvingLayouts(1, PIECE_TARGET).get(0);
        ActivePlayerStream.CommittedSnapshot snapshot =
                ActivePlayerStream.CommittedSnapshot.from(layout);
        ActivePlayerStream stream = new ActivePlayerStream(receiver, 1L);

        ActivePlayerStream.CommitResult first = stream.onCommitted(snapshot, holders);
        assertThat(first.window().piecesSent()).isEqualTo(1);
        assertThat(first.window().bytesSent()).isGreaterThan(1);
        assertThat(first.window().oversizePieceSent()).isTrue();
        assertThat(first.window().budgetExhausted()).isTrue();

        long sentBeforeDuplicate = stream.metrics().piecesSent();
        ActivePlayerStream.CommitResult duplicate = stream.onCommitted(snapshot, holders);
        assertThat(duplicate.disposition()).isEqualTo(ActivePlayerStream.OfferDisposition.DUPLICATE);
        assertThat(duplicate.window().piecesSent()).isZero();
        assertThat(stream.metrics().piecesSent()).isEqualTo(sentBeforeDuplicate);

        for (int guard = 0; guard < layout.manifest().pieceCount() + 2
                && stream.metrics().pendingRegionCount() > 0; guard++) {
            ActivePlayerStream.WindowResult window = stream.nextWindow();
            assertThat(window.piecesSent()).isEqualTo(1);
            assertThat(window.bytesSent()).isGreaterThan(1);
            assertThat(window.oversizePieceSent()).isTrue();
        }

        assertThat(stream.metrics().pendingRegionCount()).isZero();
        assertThat(stream.metrics().oversizePiecesSent())
                .isEqualTo(layout.manifest().pieceCount());
        assertThat(receiver.activeManifest(holders.get(0), REGION)).isEqualTo(layout.manifest());
    }

    @Test
    void failedPhysicalAckRetriesWhileStaleAndDuplicateCommitsDoNothing() {
        List<NodeId> holders = holders(1, 50L);
        PhysicalReceiver receiver = new PhysicalReceiver(holders);
        List<RegionSnapshotSplitter.Layout> layouts = evolvingLayouts(3, PIECE_TARGET);
        RegionSnapshotSplitter.Layout latest = layouts.get(2);
        Piece firstPiece = latest.manifest().piece(0);
        ActivePlayerStream.CommittedSnapshot latestSnapshot =
                ActivePlayerStream.CommittedSnapshot.from(latest);
        ActivePlayerStream stream = new ActivePlayerStream(receiver, OPEN_BUDGET);
        receiver.failNextStores(1);

        ActivePlayerStream.CommitResult failed = stream.onCommitted(latestSnapshot, holders);
        assertThat(failed.window().metrics().pendingRegionCount()).isEqualTo(1);
        assertThat(receiver.activeManifest(holders.get(0), REGION)).isNull();
        assertThat(receiver.storeAttempts(holders.get(0), firstPiece.pieceHash())).isEqualTo(1);

        ActivePlayerStream.WindowResult retried = stream.nextWindow();
        assertThat(retried.versionsCompleted()).isEqualTo(1);
        assertThat(receiver.storeAttempts(holders.get(0), firstPiece.pieceHash())).isEqualTo(2);
        assertThat(receiver.activeManifest(holders.get(0), REGION)).isEqualTo(latest.manifest());

        long sentAfterRetry = stream.metrics().piecesSent();
        ActivePlayerStream.CommitResult duplicate = stream.onCommitted(latestSnapshot, holders);
        ActivePlayerStream.CommitResult stale = stream.onCommitted(
                ActivePlayerStream.CommittedSnapshot.from(layouts.get(1)), holders);
        assertThat(duplicate.disposition()).isEqualTo(ActivePlayerStream.OfferDisposition.DUPLICATE);
        assertThat(stale.disposition()).isEqualTo(ActivePlayerStream.OfferDisposition.STALE);
        assertThat(stream.metrics().piecesSent()).isEqualTo(sentAfterRetry);
        assertThat(receiver.activeManifest(holders.get(0), REGION)).isEqualTo(latest.manifest());
    }

    @Test
    void receiverCannotReEnterMutationAndFailedOfferIsAtomic() {
        List<NodeId> holders = holders(1, 60L);
        RegionSnapshotSplitter.Layout layout = evolvingLayouts(1, PIECE_TARGET).get(0);
        AtomicReference<ActivePlayerStream> streamRef = new AtomicReference<>();
        AtomicBoolean reenter = new AtomicBoolean(true);
        PhysicalReceiver physical = new PhysicalReceiver(holders);
        ActivePlayerStream.Receiver receiver = new ActivePlayerStream.Receiver() {
            @Override
            public Set<Bytes> heldPieceHashes(NodeId holder) {
                if (reenter.get()) {
                    streamRef.get().nextWindow();
                }
                return physical.heldPieceHashes(holder);
            }

            @Override
            public boolean storePiece(
                    NodeId holder, PieceManifest manifest, int pieceIndex, Bytes payload) {
                return physical.storePiece(holder, manifest, pieceIndex, payload);
            }

            @Override
            public boolean activateManifest(NodeId holder, PieceManifest manifest) {
                return physical.activateManifest(holder, manifest);
            }
        };
        ActivePlayerStream stream = new ActivePlayerStream(receiver, OPEN_BUDGET);
        streamRef.set(stream);
        ActivePlayerStream.CommittedSnapshot snapshot =
                ActivePlayerStream.CommittedSnapshot.from(layout);

        assertThatThrownBy(() -> stream.onCommitted(snapshot, holders))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must not re-enter");
        assertThat(stream.metrics().versionsOffered()).isZero();
        assertThat(stream.metrics().pendingRegionCount()).isZero();

        reenter.set(false);
        assertThat(stream.onCommitted(snapshot, holders).window().versionsCompleted()).isEqualTo(1);
    }

    @Test
    void committedPayloadValidatesPlaintextAndEncryptedManifests() {
        RegionSnapshotSplitter.Layout layout = evolvingLayouts(1, PIECE_TARGET).get(0);
        ActivePlayerStream.CommittedSnapshot plain =
                ActivePlayerStream.CommittedSnapshot.from(layout);
        assertThat(plain.manifest().encrypted()).isFalse();

        byte[] rawKey = new byte[ContentKey.KEY_BYTES];
        java.util.Arrays.fill(rawKey, (byte) 7);
        EncryptedRegion encrypted = EncryptedRegion.encrypt(
                layout,
                ContentKey.of(rawKey),
                WorldKeyMaterial.defaultArgon2id(
                        Bytes.fromHex("00112233445566778899aabbccddeeff")));
        ActivePlayerStream.CommittedSnapshot ciphertext = new ActivePlayerStream.CommittedSnapshot(
                encrypted.manifest(), encrypted.ciphertextBlob());
        assertThat(ciphertext.manifest().encrypted()).isTrue();

        byte[] corrupt = encrypted.ciphertextBlob().toArray();
        corrupt[corrupt.length - 1] ^= 1;
        assertThatThrownBy(() -> new ActivePlayerStream.CommittedSnapshot(
                encrypted.manifest(), Bytes.unsafeWrap(corrupt)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ContentId");
    }

    /** Build cumulative deterministic state changes and split every monotonically advancing state. */
    private static List<RegionSnapshotSplitter.Layout> evolvingLayouts(
            int count, int pieceTarget) {
        List<ChunkColumnState> columns = new ArrayList<>(
                DistFixtures.variedSnapshot(REGION, SnapshotVersion.INITIAL, 0L).chunks());
        List<RegionSnapshotSplitter.Layout> layouts = new ArrayList<>(count);
        for (int version = 1; version <= count; version++) {
            int changedIndex = (version * 7) % columns.size();
            ChunkColumnState previous = columns.get(changedIndex);
            int[] palette = previous.paletteStateIdsPerSection();
            palette[(version * 5) % palette.length] += 100_000 + version;
            columns.set(changedIndex, new ChunkColumnState(
                    previous.chunkX(),
                    previous.chunkZ(),
                    palette,
                    previous.minY(),
                    previous.sectionCount()));
            RegionSnapshot snapshot = new RegionSnapshot(
                    REGION, new SnapshotVersion(version), version * 2L, columns);
            layouts.add(RegionSnapshotSplitter.split(snapshot, pieceTarget));
        }
        return List.copyOf(layouts);
    }

    private static List<NodeId> holders(int count, long firstId) {
        Set<NodeId> ordered = new LinkedHashSet<>();
        for (int i = 0; i < count; i++) {
            ordered.add(DistFixtures.node(firstId + i));
        }
        return List.copyOf(ordered);
    }
}
