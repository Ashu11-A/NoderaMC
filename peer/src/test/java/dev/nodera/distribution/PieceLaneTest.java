package dev.nodera.distribution;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.crypto.TypeTags;
import dev.nodera.core.crypto.symmetric.ContentKey;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.EntityKind;
import dev.nodera.core.state.FixedVec3;
import dev.nodera.core.state.NetworkEntityId;
import dev.nodera.core.state.PersistedEntityState;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.core.state.StateRoot;
import dev.nodera.protocol.content.ContentChunk;
import dev.nodera.storage.Compression;
import dev.nodera.storage.ContentId;
import dev.nodera.testkit.engine.EngineFixtures;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A region snapshot as pieces: split it, describe it, choose the next one, put it back together.
 *
 * <p>Four sibling classes over one subject and one round trip — the splitter's output is the
 * manifest the selector reads and the reassembler consumes, so a disagreement between any two of
 * them is a world that cannot be rebuilt. They were four files whose import blocks were the same
 * dozen lines.
 *
 * <p>Each nest keeps the class Javadoc naming what it was written from, and JUnit reports every
 * {@code @Nested @Test} individually, so the count this file contributes is unchanged.
 */
final class PieceLaneTest {

    /**
     * Splitting is where "addressable pieces" is either true or a lie: a cut in the wrong place
     * produces pieces that verify individually yet cannot be used individually. These tests pin the
     * record-boundary rule, the over-target record rule, and the invariant that the piece plane
     * addresses exactly the frozen {@code RegionSnapshot} bytes.
     *
     * <p>Thread-context: single test thread.
     */
    @Nested
    final class PieceSplitterTest {
        private static byte[] blob(int length) {
            byte[] out = new byte[length];
            for (int i = 0; i < length; i++) {
                out[i] = (byte) (i * 13 + 1);
            }
            return out;
        }

        @Test
        void cutsOnlyAtRecordBoundariesAndPacksTowardTheTarget() {
            byte[] data = blob(400);
            // Records of 100 bytes each at 0, 100, 200, 300.
            int[] recordStarts = {0, 100, 200, 300};

            List<Piece> pieces = PieceSplitter.split(data, recordStarts, 150);

            // 150-byte target over 100-byte records: two records per piece.
            assertThat(pieces).hasSize(2);
            assertThat(pieces.get(0).offset()).isZero();
            assertThat(pieces.get(0).length()).isEqualTo(200);
            assertThat(pieces.get(1).offset()).isEqualTo(200);
            assertThat(pieces.get(1).length()).isEqualTo(200);
            // Every cut lands on a declared record start.
            for (Piece p : pieces) {
                assertThat(recordStarts).contains((int) p.offset());
            }
        }

        @Test
        void anOverTargetRecordBecomesItsOwnOverTargetPieceRatherThanBeingCutMidRecord() {
            byte[] data = blob(500);
            // One 400-byte record followed by a 100-byte record.
            int[] recordStarts = {0, 400};

            List<Piece> pieces = PieceSplitter.split(data, recordStarts, 50);

            assertThat(pieces).hasSize(2);
            // The target is a packing goal, not a cap: the big record survives whole.
            assertThat(pieces.get(0).length()).isEqualTo(400);
            assertThat(pieces.get(1).length()).isEqualTo(100);
        }

        @Test
        void piecesAreContiguousCoverTheWholeBlobAndHashTheirOwnBytes() {
            byte[] data = blob(1000);
            List<Piece> pieces = PieceSplitter.splitFixed(data, 128);

            long covered = 0;
            for (int i = 0; i < pieces.size(); i++) {
                Piece p = pieces.get(i);
                assertThat(p.index()).isEqualTo(i);
                assertThat(p.offset()).isEqualTo(covered);
                covered = p.endOffset();
                Bytes slice = new Bytes(data, (int) p.offset(), (int) p.length());
                assertThat(EngineFixtures.hashes().sha256(slice)).isEqualTo(p.pieceHash());
            }
            assertThat(covered).isEqualTo(data.length);
        }

        @Test
        void rejectsMalformedInputs() {
            byte[] data = blob(100);
            assertThatThrownBy(() -> PieceSplitter.split(new byte[0], new int[]{0}, 10))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("empty blob");
            assertThatThrownBy(() -> PieceSplitter.split(data, new int[]{5}, 10))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("start at 0");
            assertThatThrownBy(() -> PieceSplitter.split(data, new int[]{0, 20, 20}, 10))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("strictly ascending");
            assertThatThrownBy(() -> PieceSplitter.split(data, new int[]{0, 100}, 10))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("inside the blob");
            assertThatThrownBy(() -> PieceSplitter.splitFixed(data, 0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("targetBytes must be positive");
        }

        @Test
        void mapsEveryRecordToTheContainingPiece() {
            byte[] data = blob(400);
            int[] recordStarts = {0, 100, 200, 300};
            List<Piece> pieces = PieceSplitter.split(data, recordStarts, 150);

            assertThat(PieceSplitter.pieceOfRecord(recordStarts, pieces))
                    .containsExactly(0, 0, 1, 1);
        }

        @Test
        void snapshotBlobIsByteForByteTheFrozenRegionSnapshotEncoding() {
            RegionSnapshot snapshot = EngineFixtures.variedSnapshot(
                    EngineFixtures.region(1, 1), new SnapshotVersion(3L), 99L);

            RegionSnapshotSplitter.Layout layout = RegionSnapshotSplitter.split(snapshot, 4096);

            CanonicalWriter w = new CanonicalWriter();
            snapshot.encode(w);
            assertThat(layout.blob().toArray()).isEqualTo(w.toByteArray());

            // ...which is exactly why the manifest's regionRoot equals the committee's StateRoot with
            // no extra agreement: both are SHA-256 over the same canonical bytes.
            assertThat(layout.manifest().regionRoot())
                    .isEqualTo(StateRoot.of(EngineFixtures.hashes().hash(snapshot)));
            assertThat(layout.manifest().blob().hash())
                    .isEqualTo(layout.manifest().regionRoot().hash());
        }

        @Test
        void snapshotSplitsIntoManyPiecesAndIndexesEveryChunkColumn() {
            RegionSnapshot snapshot = EngineFixtures.variedSnapshot(
                    EngineFixtures.region(0, 0), SnapshotVersion.INITIAL, 0L);

            RegionSnapshotSplitter.Layout layout = RegionSnapshotSplitter.split(snapshot, 512);

            // Task 19 acceptance #2 works with a region split into >= 8 addressable pieces.
            assertThat(layout.manifest().pieceCount()).isGreaterThanOrEqualTo(8);
            assertThat(layout.pieceOfChunk()).hasSize(snapshot.chunks().size());
            // The chunk -> piece index is monotonic: chunks are encoded in canonical order, so a later
            // chunk can never land in an earlier piece.
            for (int i = 1; i < layout.pieceOfChunk().size(); i++) {
                assertThat(layout.pieceForChunk(i)).isGreaterThanOrEqualTo(layout.pieceForChunk(i - 1));
            }
            assertThat(layout.pieceForChunk(layout.pieceOfChunk().size() - 1))
                    .isEqualTo(layout.manifest().pieceCount() - 1);
        }

        @Test
        void largeEntityTableSplitsAtEntityRecordBoundaries() {
            RegionSnapshot blocks = EngineFixtures.fullUniformSnapshot(EngineFixtures.region(0, 0), 0);
            List<PersistedEntityState> entities = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                entities.add(new PersistedEntityState(
                        new NetworkEntityId(i), EntityKind.GHOST, 54,
                        FixedVec3.ofBlock(i % 100, 64, i % 100), FixedVec3.ZERO,
                        0, PersistedEntityState.NEVER_DESPAWN, new Bytes(new byte[256])));
            }
            RegionSnapshot snapshot = new RegionSnapshot(
                    blocks.region(), blocks.version(), blocks.tick(), blocks.chunks(), entities);

            RegionSnapshotSplitter.Layout layout = RegionSnapshotSplitter.split(snapshot, 1024);

            assertThat(layout.manifest().pieceCount()).isGreaterThan(20);
            assertThat(layout.manifest().pieces())
                    .extracting(Piece::length)
                    .allMatch(length -> length < 2_000L);
            assertThat(layout.pieceOfChunk()).hasSize(snapshot.chunks().size());
            CanonicalWriter writer = new CanonicalWriter();
            snapshot.encode(writer);
            assertThat(layout.blob().toArray()).isEqualTo(writer.toByteArray());
        }

        @Test
        void splittingIsDeterministicAcrossRuns() {
            RegionSnapshot snapshot = EngineFixtures.variedSnapshot(
                    EngineFixtures.region(-2, 5), new SnapshotVersion(11L), 1234L);

            RegionSnapshotSplitter.Layout a = RegionSnapshotSplitter.split(snapshot, 700);
            RegionSnapshotSplitter.Layout b = RegionSnapshotSplitter.split(snapshot, 700);

            assertThat(a.manifest()).isEqualTo(b.manifest());
            assertThat(a.manifest().manifestRoot()).isEqualTo(b.manifest().manifestRoot());
            assertThat(a.pieceOfChunk()).isEqualTo(b.pieceOfChunk());
        }
    }

    /**
     * The manifest is the whole data plane's trust anchor (Task 19 acceptance #1), so these tests pin
     * its canonical encoding, its derived root, and every structural invariant a tampered manifest
     * would have to break.
     *
     * <p>Thread-context: single test thread.
     */
    @Nested
    final class PieceManifestTest {
        private static final RegionId REGION = EngineFixtures.region(2, -3);

        private static PieceManifest manifestOf(byte[] blob, int targetBytes) {
            List<Piece> pieces = PieceSplitter.splitFixed(blob, targetBytes);
            Bytes blobHash = EngineFixtures.hashes().sha256(blob);
            return PieceManifest.of(
                    REGION,
                    new SnapshotVersion(7L),
                    42L,
                    StateRoot.of(blobHash),
                    new ContentId(blobHash, blob.length, Compression.NONE),
                    blob.length,
                    pieces);
        }

        private static byte[] blob(int length) {
            byte[] out = new byte[length];
            for (int i = 0; i < length; i++) {
                out[i] = (byte) (i * 7 + 3);
            }
            return out;
        }

        @Test
        void roundTripsCanonicallyAndPinsItsTypeTag() {
            PieceManifest original = manifestOf(blob(500), 100);

            CanonicalWriter w = new CanonicalWriter();
            original.encode(w);
            byte[] frame = w.toByteArray();

            // The frame is self-describing: tag first, then version — the frozen Encodable contract.
            CanonicalReader peek = new CanonicalReader(frame);
            assertThat(peek.readU16()).isEqualTo(TypeTags.PIECE_MANIFEST);
            assertThat(peek.readU16()).isEqualTo(dev.nodera.core.crypto.Encodable.ENCODING_VERSION);

            PieceManifest decoded = PieceManifest.decode(new CanonicalReader(frame));
            assertThat(decoded).isEqualTo(original);

            // Byte-stability: re-encoding the decoded value reproduces the identical frame, which is
            // what lets a manifest root be quoted by a certificate.
            CanonicalWriter again = new CanonicalWriter();
            decoded.encode(again);
            assertThat(again.toByteArray()).isEqualTo(frame);
        }

        @Test
        void manifestRootIsDeterministicAndDependsOnPieceOrderAndLength() {
            byte[] data = blob(500);
            PieceManifest a = manifestOf(data, 100);
            PieceManifest b = manifestOf(data, 100);
            assertThat(a.manifestRoot()).isEqualTo(b.manifestRoot());

            // A different piece layout over the SAME bytes must produce a different root: the root
            // commits index and length, not just the hash multiset.
            PieceManifest coarser = manifestOf(data, 250);
            assertThat(coarser.manifestRoot()).isNotEqualTo(a.manifestRoot());
            assertThat(coarser.blob()).isEqualTo(a.blob());
        }

        @Test
        void rootCommitsPiecePositionSoPiecesCannotBeReordered() {
            byte[] data = blob(300);
            List<Piece> pieces = PieceSplitter.splitFixed(data, 100);
            assertThat(pieces).hasSize(3);

            // Swap two pieces' hashes while keeping the index/offset/length layout — the exact attack
            // "a hash list without positions" would permit.
            List<Piece> swapped = new ArrayList<>(pieces);
            swapped.set(0, new Piece(0, pieces.get(0).offset(), pieces.get(0).length(),
                    pieces.get(1).pieceHash()));
            swapped.set(1, new Piece(1, pieces.get(1).offset(), pieces.get(1).length(),
                    pieces.get(0).pieceHash()));

            assertThat(PieceManifest.computeRoot(swapped))
                    .isNotEqualTo(PieceManifest.computeRoot(pieces));
        }

        @Test
        void rejectsAManifestWhoseStoredRootDoesNotMatchItsPieces() {
            PieceManifest good = manifestOf(blob(300), 100);
            Bytes tampered = DistFixtures.corrupt(good.manifestRoot());

            assertThatThrownBy(() -> new PieceManifest(
                    good.region(), good.version(), good.tick(), good.regionRoot(), good.blob(),
                    good.totalLength(), false, null, good.pieces(), tampered, null, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("does not match the recomputed root");
        }

        @Test
        void rejectsGappedOverlappingOrNonContiguousPieceLayouts() {
            byte[] data = blob(300);
            List<Piece> pieces = PieceSplitter.splitFixed(data, 100);

            List<Piece> gapped = new ArrayList<>(pieces);
            gapped.set(1, new Piece(1, 150, 100, pieces.get(1).pieceHash()));
            assertThatThrownBy(() -> PieceManifest.of(
                    REGION, SnapshotVersion.INITIAL, 0L, StateRoot.zero(),
                    new ContentId(Bytes.fromHex("00".repeat(32)), 300, Compression.NONE), 300, gapped))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("gap/overlap");

            List<Piece> holed = new ArrayList<>(pieces);
            holed.remove(1);
            assertThatThrownBy(() -> PieceManifest.of(
                    REGION, SnapshotVersion.INITIAL, 0L, StateRoot.zero(),
                    new ContentId(Bytes.fromHex("00".repeat(32)), 300, Compression.NONE), 300, holed))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("contiguous");
        }

        @Test
        void rejectsALengthThatDisagreesWithTheContentId() {
            byte[] data = blob(300);
            List<Piece> pieces = PieceSplitter.splitFixed(data, 100);
            Bytes hash = EngineFixtures.hashes().sha256(data);
            assertThatThrownBy(() -> PieceManifest.of(
                    REGION, SnapshotVersion.INITIAL, 0L, StateRoot.of(hash),
                    new ContentId(hash, 999, Compression.NONE), 300, pieces))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must equal blob size");
        }

        @Test
        void encryptionSlotsAreReservedAndMustAgreeWithEachOther() {
            PieceManifest plain = manifestOf(blob(300), 100);
            WorldKeyMaterial key = new WorldKeyMaterial("argon2id", Bytes.fromHex("00112233445566778899aabbccddeeff"),
                    65536L, 3, 1);

            // Reserved NOW (Task 23 fills the key path) so shipping encryption needs no version bump:
            // the slot round-trips today.
            PieceManifest encrypted = new PieceManifest(
                    plain.region(), plain.version(), plain.tick(), plain.regionRoot(), plain.blob(),
                    plain.totalLength(), true, key, plain.pieces(), plain.manifestRoot(), null, null);
            CanonicalWriter w = new CanonicalWriter();
            encrypted.encode(w);
            assertThat(PieceManifest.decode(new CanonicalReader(w.toByteArray()))).isEqualTo(encrypted);

            assertThatThrownBy(() -> new PieceManifest(
                    plain.region(), plain.version(), plain.tick(), plain.regionRoot(), plain.blob(),
                    plain.totalLength(), true, null, plain.pieces(), plain.manifestRoot(), null, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("requires keyMaterial");

            assertThatThrownBy(() -> new PieceManifest(
                    plain.region(), plain.version(), plain.tick(), plain.regionRoot(), plain.blob(),
                    plain.totalLength(), false, key, plain.pieces(), plain.manifestRoot(), null, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must not carry keyMaterial");
        }

        @Test
        void verifyPieceRejectsWrongLengthAndWrongBytesForTheIndex() {
            byte[] data = blob(300);
            PieceManifest manifest = manifestOf(data, 100);

            Bytes piece0 = new Bytes(data, 0, 100);
            Bytes piece1 = new Bytes(data, 100, 100);

            assertThat(manifest.verifyPiece(0, piece0)).isTrue();
            // Right bytes, wrong index — the manifest pins hash PER index, so this must fail.
            assertThat(manifest.verifyPiece(1, piece0)).isFalse();
            assertThat(manifest.verifyPiece(1, piece1)).isTrue();
            assertThat(manifest.verifyPiece(0, DistFixtures.corrupt(piece0))).isFalse();
            assertThat(manifest.verifyPiece(0, new Bytes(data, 0, 99))).isFalse();
            assertThat(manifest.verifyPiece(99, piece0)).isFalse();
        }

        @Test
        void differenceIsAboutContentAndNotAboutVersionNumbers() {
            // What replaced isSupersededBy. That asked "is theirs at a higher version", which has no
            // honest answer between peers: a version is a chain height each machine counts on its own,
            // so two copies that both advanced from one base each hold a number the other's means
            // nothing to. Answering it anyway is what discarded a whole copy of somebody's work.
            byte[] data = blob(300);
            List<Piece> pieces = PieceSplitter.splitFixed(data, 100);
            Bytes hash = EngineFixtures.hashes().sha256(data);
            ContentId id = new ContentId(hash, data.length, Compression.NONE);

            byte[] other = blob(400);
            List<Piece> otherPieces = PieceSplitter.splitFixed(other, 100);
            Bytes otherHash = EngineFixtures.hashes().sha256(other);

            PieceManifest v1 = PieceManifest.of(REGION, new SnapshotVersion(1L), 10L,
                    StateRoot.of(hash), id, data.length, pieces);
            PieceManifest v9SameContent = PieceManifest.of(REGION, new SnapshotVersion(9L), 90L,
                    StateRoot.of(hash), id, data.length, pieces);
            PieceManifest v2Different = PieceManifest.of(REGION, new SnapshotVersion(2L), 20L,
                    StateRoot.of(otherHash),
                    new ContentId(otherHash, other.length, Compression.NONE), other.length,
                    otherPieces);
            PieceManifest otherRegion = PieceManifest.of(EngineFixtures.region(9, 9),
                    new SnapshotVersion(5L), 50L, StateRoot.of(otherHash),
                    new ContentId(otherHash, other.length, Compression.NONE), other.length,
                    otherPieces);

            assertThat(v1.differsFrom(v9SameContent))
                    .as("eight versions apart and byte-identical: nothing to do")
                    .isFalse();
            assertThat(v1.differsFrom(v2Different)).isTrue();
            assertThat(v1.differsFrom(v1)).isFalse();
            // Different content for a DIFFERENT region is not a difference to reconcile — it is a
            // different slice of the world.
            assertThat(v1.differsFrom(otherRegion)).isFalse();
        }
    }

    /**
     * Task 19 acceptance #5: two selectors given the same {@code (manifest, holderSet)} must request
     * pieces in the same order. That is a consensus-adjacent property — if it fails, two peers fetching
     * the same world make different requests and the "deterministic rarest-first" contract Task 21's
     * placement audit relies on evaporates.
     *
     * <p>Thread-context: single test thread.
     */
    @Nested
    final class PieceSelectorTest {
        private static PieceManifest manifest(int pieces) {
            byte[] blob = new byte[pieces * 64];
            for (int i = 0; i < blob.length; i++) {
                blob[i] = (byte) (i * 17 + 5);
            }
            List<Piece> list = PieceSplitter.splitFixed(blob, 64);
            dev.nodera.core.Bytes hash = EngineFixtures.hashes().sha256(blob);
            return PieceManifest.of(
                    EngineFixtures.region(0, 0), new SnapshotVersion(1L), 1L,
                    dev.nodera.core.state.StateRoot.of(hash),
                    new dev.nodera.storage.ContentId(hash, blob.length,
                            dev.nodera.storage.Compression.NONE),
                    blob.length, list);
        }

        private static Set<Integer> setOf(int... values) {
            Set<Integer> out = new LinkedHashSet<>();
            for (int v : values) {
                out.add(v);
            }
            return out;
        }

        @Test
        void ordersRarestFirst() {
            PieceManifest m = manifest(6);
            Map<NodeId, Set<Integer>> holders = new LinkedHashMap<>();
            holders.put(EngineFixtures.node(1), setOf(0, 1, 2, 3, 4));
            holders.put(EngineFixtures.node(2), setOf(0, 1, 2, 3));
            holders.put(EngineFixtures.node(3), setOf(0, 1, 2));
            // piece 5 is held by nobody; 4 by one peer; 3 by two; 0-2 by three.

            List<Integer> order = PieceSelector.order(m, holders, List.of(0, 1, 2, 3, 4, 5));

            assertThat(order).hasSize(6);
            assertThat(order.get(0)).isEqualTo(5);   // 0 holders
            assertThat(order.get(1)).isEqualTo(4);   // 1 holder
            assertThat(order.get(2)).isEqualTo(3);   // 2 holders
            assertThat(order.subList(3, 6)).containsExactlyInAnyOrder(0, 1, 2);
        }

        @Test
        void twoSelectorsAgreeOnOrderRegardlessOfHolderMapIterationOrder() {
            PieceManifest m = manifest(24);

            Map<NodeId, Set<Integer>> insertionOrdered = new LinkedHashMap<>();
            Map<NodeId, Set<Integer>> hashOrdered = new HashMap<>();
            for (int peer = 1; peer <= 5; peer++) {
                Set<Integer> held = new LinkedHashSet<>();
                for (int piece = 0; piece < 24; piece++) {
                    if ((piece + peer) % (peer + 1) == 0) {
                        held.add(piece);
                    }
                }
                insertionOrdered.put(EngineFixtures.node(peer), held);
                hashOrdered.put(EngineFixtures.node(peer), held);
            }
            List<Integer> wanted = new ArrayList<>();
            for (int i = 23; i >= 0; i--) {
                wanted.add(i);   // deliberately reversed: input order must not leak into output
            }

            List<Integer> a = PieceSelector.order(m, insertionOrdered, wanted);
            List<Integer> b = PieceSelector.order(m, hashOrdered, List.of(
                    0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23));

            assertThat(a).isEqualTo(b);
        }

        @Test
        void tieBreakIsNotIndexOrderSoConcurrentFetchersDoNotAllGrabPieceZero() {
            PieceManifest m = manifest(16);
            Map<NodeId, Set<Integer>> holders = new LinkedHashMap<>();
            Set<Integer> all = new LinkedHashSet<>();
            for (int i = 0; i < 16; i++) {
                all.add(i);
            }
            holders.put(EngineFixtures.node(1), all);

            List<Integer> order = PieceSelector.order(m, holders,
                    List.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15));

            // Every holder count is equal, so the order is decided entirely by the hash tie-break.
            // If it degenerated to index order, all fetchers would serialise on the same seeder.
            assertThat(order).containsExactlyInAnyOrderElementsOf(all);
            assertThat(order).isNotEqualTo(new ArrayList<>(all));
        }

        @Test
        void holderChoiceIsDeterministicSkipsExcludedPeersAndSpreadsAcrossPieces() {
            PieceManifest m = manifest(8);
            Map<NodeId, Set<Integer>> holders = new LinkedHashMap<>();
            Set<Integer> all = setOf(0, 1, 2, 3, 4, 5, 6, 7);
            NodeId a = EngineFixtures.node(11);
            NodeId b = EngineFixtures.node(22);
            NodeId c = EngineFixtures.node(33);
            holders.put(a, all);
            holders.put(b, all);
            holders.put(c, all);

            Set<NodeId> chosen = new HashSet<>();
            for (int piece = 0; piece < 8; piece++) {
                NodeId first = PieceSelector.chooseHolder(m.manifestRoot(), piece, holders, Set.of());
                NodeId again = PieceSelector.chooseHolder(m.manifestRoot(), piece, holders, Set.of());
                assertThat(again).isEqualTo(first);
                chosen.add(first);
            }
            // Rendezvous by (root, piece, node): different pieces prefer different seeders, which is
            // what actually parallelises a swarm.
            assertThat(chosen).hasSizeGreaterThan(1);

            NodeId picked = PieceSelector.chooseHolder(m.manifestRoot(), 0, holders, Set.of());
            NodeId alternate = PieceSelector.chooseHolder(m.manifestRoot(), 0, holders, Set.of(picked));
            assertThat(alternate).isNotNull().isNotEqualTo(picked);

            assertThat(PieceSelector.chooseHolder(m.manifestRoot(), 0, holders, Set.of(a, b, c)))
                    .isNull();
        }

        @Test
        void holderChoiceReturnsNullWhenNobodyHoldsThePiece() {
            PieceManifest m = manifest(4);
            Map<NodeId, Set<Integer>> holders = new LinkedHashMap<>();
            holders.put(EngineFixtures.node(1), setOf(0, 1));

            assertThat(PieceSelector.chooseHolder(m.manifestRoot(), 3, holders, Set.of())).isNull();
            assertThat(PieceSelector.holderCount(holders, 3)).isZero();
            assertThat(PieceSelector.holderCount(holders, 0)).isEqualTo(1);
        }
    }

    /**
     * Rule 10's "hash-validate before use" lives here. The tests that matter are the negative ones: a
     * rejected piece must leave the reassembler <b>bit-identical</b> to before, or a corrupt seeder
     * could advance progress (or worse, poison a byte) simply by being loud.
     *
     * <p>Thread-context: single test thread.
     */
    @Nested
    final class PieceReassemblerTest {
        private static RegionSnapshotSplitter.Layout layout() {
            RegionSnapshot snapshot = EngineFixtures.variedSnapshot(
                    EngineFixtures.region(4, 4), new SnapshotVersion(2L), 20L);
            return RegionSnapshotSplitter.split(snapshot, 512);
        }

        private static Bytes pieceBytes(RegionSnapshotSplitter.Layout layout, int index) {
            Piece p = layout.manifest().piece(index);
            return new Bytes(layout.blob().toArray(), (int) p.offset(), (int) p.length());
        }

        @Test
        void acceptsVerifiedPiecesAndReassemblesToTheCommittedStateRoot() {
            RegionSnapshotSplitter.Layout layout = layout();
            PieceReassembler r = new PieceReassembler(layout.manifest());

            for (int i = 0; i < layout.manifest().pieceCount(); i++) {
                assertThat(r.accept(new ContentChunk(
                        layout.manifest().manifestRoot(), i, pieceBytes(layout, i)))).isTrue();
            }

            assertThat(r.isComplete()).isTrue();
            assertThat(r.assemble()).isEqualTo(layout.blob());
            // The whole point: pieces from anywhere reassemble into the state the committee committed.
            assertThat(r.assembledRoot())
                    .isEqualTo(StateRoot.of(EngineFixtures.hashes().hash(layout.snapshot())))
                    .isEqualTo(layout.manifest().regionRoot());
        }

        @Test
        void encryptedBlobMustBeDecryptedBeforeComputingCanonicalStateRoot() {
            RegionSnapshotSplitter.Layout layout = layout();
            ContentKey key = ContentKey.of(new byte[ContentKey.KEY_BYTES]);
            EncryptedRegion encrypted = EncryptedRegion.encrypt(
                    layout,
                    key,
                    WorldKeyMaterial.defaultArgon2id(
                            Bytes.fromHex("00112233445566778899aabbccddeeff")));
            PieceReassembler reassembler = new PieceReassembler(encrypted.manifest());
            for (int i = 0; i < encrypted.manifest().pieceCount(); i++) {
                assertThat(reassembler.restore(i, encrypted.ciphertextPiece(i))).isTrue();
            }

            assertThat(reassembler.assemble()).isEqualTo(encrypted.ciphertextBlob());
            assertThatThrownBy(reassembler::assembledRoot)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("must be decrypted");
        }

        @Test
        void aCorruptedPieceIsRejectedAndChangesNothing() {
            RegionSnapshotSplitter.Layout layout = layout();
            PieceReassembler r = new PieceReassembler(layout.manifest());

            Bytes good = pieceBytes(layout, 3);
            assertThat(r.accept(new ContentChunk(layout.manifest().manifestRoot(), 3,
                    DistFixtures.corrupt(good)))).isFalse();

            assertThat(r.hasPiece(3)).isFalse();
            assertThat(r.verifiedCount()).isZero();
            assertThat(r.missing()).contains(3);

            // ...and the honest bytes still land afterwards: rejection is not a poison pill.
            assertThat(r.accept(new ContentChunk(layout.manifest().manifestRoot(), 3, good))).isTrue();
            assertThat(r.hasPiece(3)).isTrue();
        }

        @Test
        void rejectsChunksForAnotherManifestAndOutOfRangeIndexes() {
            RegionSnapshotSplitter.Layout layout = layout();
            PieceReassembler r = new PieceReassembler(layout.manifest());

            Bytes good = pieceBytes(layout, 0);
            Bytes foreignRoot = DistFixtures.corrupt(layout.manifest().manifestRoot());

            assertThat(r.accept(new ContentChunk(foreignRoot, 0, good))).isFalse();
            assertThat(r.accept(new ContentChunk(layout.manifest().manifestRoot(), 9999, good)))
                    .isFalse();
            assertThat(r.verifiedCount()).isZero();
        }

        @Test
        void locallyCachedBytesAreVerifiedTooSoACorruptCacheCannotBecomeACorruptWorld() {
            RegionSnapshotSplitter.Layout layout = layout();
            PieceReassembler r = new PieceReassembler(layout.manifest());

            assertThat(r.restore(0, DistFixtures.corrupt(pieceBytes(layout, 0)))).isFalse();
            assertThat(r.restore(0, pieceBytes(layout, 0))).isTrue();
        }

        @Test
        void refusesToAssembleWhilePiecesAreMissing() {
            RegionSnapshotSplitter.Layout layout = layout();
            PieceReassembler r = new PieceReassembler(layout.manifest());
            r.accept(new ContentChunk(layout.manifest().manifestRoot(), 0, pieceBytes(layout, 0)));

            assertThatThrownBy(r::assemble)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("still missing");
        }

        /**
         * {@link PieceReassembler#assemble()} hands its buffer out instead of cloning it — a clone of a
         * 123 MB world archive is what killed the replication lane on a phone, mid-download, with every
         * piece already verified. The guarantee the clone provided has to survive the change: a chunk
         * that arrives after assembly (a duplicate from a slow seeder, the ordinary case) must not be
         * able to write into bytes the caller is already holding.
         */
        @Test
        void aLateChunkCannotMutateAnAlreadyAssembledBlob() {
            RegionSnapshotSplitter.Layout layout = layout();
            PieceReassembler r = new PieceReassembler(layout.manifest());
            for (int i = 0; i < layout.manifest().pieceCount(); i++) {
                r.accept(new ContentChunk(layout.manifest().manifestRoot(), i, pieceBytes(layout, i)));
            }
            Bytes assembled = r.assemble();

            assertThat(r.accept(new ContentChunk(layout.manifest().manifestRoot(), 0,
                    pieceBytes(layout, 0))))
                    .as("nothing is accepted once the buffer belongs to the caller")
                    .isFalse();
            assertThat(assembled).isEqualTo(layout.blob());
        }

        @Test
        void tracksProgressPreciselyAcrossPartialDelivery() {
            RegionSnapshotSplitter.Layout layout = layout();
            int total = layout.manifest().pieceCount();
            PieceReassembler r = new PieceReassembler(layout.manifest());

            for (int i = 0; i < total; i += 2) {
                r.accept(new ContentChunk(layout.manifest().manifestRoot(), i, pieceBytes(layout, i)));
            }

            int expected = (total + 1) / 2;
            assertThat(r.verifiedCount()).isEqualTo(expected);
            assertThat(r.missing()).hasSize(total - expected);
            assertThat(r.isComplete()).isFalse();
        }
    }
}
