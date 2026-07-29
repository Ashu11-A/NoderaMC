package dev.nodera.bench;

import dev.nodera.core.Bytes;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.distribution.Piece;
import dev.nodera.distribution.PieceManifest;
import dev.nodera.distribution.PieceReassembler;
import dev.nodera.distribution.PieceSelector;
import dev.nodera.distribution.PieceSplitter;
import dev.nodera.distribution.RegionSnapshotSplitter;
import dev.nodera.core.state.StateRoot;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Lane 2 — <b>chunk synchronisation</b>: the cost of moving one region's state from the peer that
 * owns it to a peer that does not have it.
 *
 * <h2>The pipeline being measured</h2>
 *
 * <pre>
 *   RegionSnapshot → canonical blob → piece plane → (network) → reassembly → root verification
 * </pre>
 *
 * <p>Every stage except the network is CPU this project spends, and every stage is on the path a
 * player waits on when they walk into a region they have never seen. The stages are benchmarked
 * both individually (so a regression names the stage) and end to end (so a regression that only
 * shows up in the composition is not missed).
 *
 * <p>Piece size is a {@link Param} because it is the one tuning knob with opposite-signed effects:
 * smaller pieces parallelise better across seeders but multiply the per-piece hashing, manifest,
 * and selection work measured here. The report is what that trade-off should be argued from.
 *
 * <p>Thread-context: JMH state per benchmark; the reassembler is rebuilt per invocation where it
 * would otherwise carry state between measurements.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3)
@Measurement(iterations = 5)
@State(Scope.Benchmark)
public class ChunkSyncBenchmark {

    /** Piece target sizes: the shipped default (24 KiB), plus one either side of it. */
    @Param({"8192", "24576", "65536"})
    public int pieceBytes;

    /** How many peers hold pieces — selection is O(holders x pieces). */
    @Param({"3", "24"})
    public int holderCount;

    private RegionSnapshot snapshot;
    private RegionSnapshotSplitter.Layout layout;
    private byte[] blob;
    private List<Piece> pieces;
    private PieceManifest manifest;
    private Map<NodeId, Set<Integer>> holders;
    private Set<Integer> wanted;
    private List<Bytes> payloads;
    private Set<NodeId> exclude;

    @Setup(Level.Trial)
    public void setUp() {
        snapshot = BenchFixtures.snapshot(BenchFixtures.region(4, -2), 1_200L);
        layout = RegionSnapshotSplitter.split(snapshot, pieceBytes);
        blob = layout.blob().toArray();
        manifest = layout.manifest();
        pieces = new ArrayList<>();
        payloads = new ArrayList<>();
        for (int i = 0; i < manifest.pieceCount(); i++) {
            Piece piece = manifest.piece(i);
            pieces.add(piece);
            payloads.add(Bytes.unsafeWrap(Arrays.copyOfRange(
                    blob, (int) piece.offset(), (int) (piece.offset() + piece.length()))));
        }

        // A realistic holder map: every peer has most pieces, nobody has all of them — the shape
        // that makes rarest-first do actual work instead of falling through.
        holders = new LinkedHashMap<>();
        for (int h = 0; h < holderCount; h++) {
            Set<Integer> held = new LinkedHashSet<>();
            for (int i = 0; i < pieces.size(); i++) {
                if ((i + h) % 7 != 0) {
                    held.add(i);
                }
            }
            holders.put(BenchFixtures.node(100 + h), held);
        }
        wanted = new LinkedHashSet<>();
        for (int i = 0; i < pieces.size(); i++) {
            wanted.add(i);
        }
        exclude = new HashSet<>();
    }

    /**
     * Stage 1 — freeze the region and lay pieces over it. This is what the OWNING peer pays every
     * time it publishes a new snapshot version, on the game thread's critical path in the mod.
     */
    @Benchmark
    public RegionSnapshotSplitter.Layout splitSnapshot() {
        return RegionSnapshotSplitter.split(snapshot, pieceBytes);
    }

    /** Stage 1a — the piece plane alone, over an already-encoded blob. */
    @Benchmark
    public List<Piece> splitBlob() {
        return PieceSplitter.splitFixed(blob, pieceBytes);
    }

    /**
     * Stage 1b — the manifest root: one SHA-256 over the whole piece list, recomputed on every
     * publish and re-verified by every receiver.
     */
    @Benchmark
    public Bytes manifestRoot() {
        return PieceManifest.computeRoot(pieces);
    }

    /** Stage 2 — rarest-first ordering across the whole want-list. */
    @Benchmark
    public List<Integer> selectOrder() {
        return PieceSelector.order(manifest, holders, wanted);
    }

    /**
     * Stage 2a — pick a seeder for every piece. Deterministic selection is the property that keeps
     * two downloaders from stampeding one peer; it is also O(holders) per piece, which is why it
     * is measured against holder count rather than assumed cheap.
     */
    @Benchmark
    public void chooseHolders(Blackhole hole) {
        Bytes root = manifest.manifestRoot();
        for (int i = 0; i < pieces.size(); i++) {
            hole.consume(PieceSelector.chooseHolder(root, i, holders, exclude));
        }
    }

    /**
     * Stage 3 — accept and hash-verify every piece. This is the receiving peer's whole CPU cost of
     * a region download, and the piece hash is verified against the MANIFEST, never against a hash
     * carried beside the payload.
     */
    @Benchmark
    public boolean verifyAndRestoreAll() {
        PieceReassembler reassembler = new PieceReassembler(manifest);
        for (int i = 0; i < payloads.size(); i++) {
            reassembler.restore(i, payloads.get(i));
        }
        return reassembler.isComplete();
    }

    /** Stage 4 — reassemble the blob from verified pieces. */
    @Benchmark
    public Bytes assemble() {
        PieceReassembler reassembler = new PieceReassembler(manifest);
        for (int i = 0; i < payloads.size(); i++) {
            reassembler.restore(i, payloads.get(i));
        }
        return reassembler.assemble();
    }

    /**
     * The whole download, end to end: order the wants, pick a seeder per piece, verify each piece,
     * reassemble, and re-derive the state root the committee committed. If this number moves and no
     * stage above it does, the cost is in the composition — which is the case a per-stage benchmark
     * alone would miss.
     */
    @Benchmark
    public StateRoot fullRegionSync() {
        List<Integer> order = PieceSelector.order(manifest, holders, wanted);
        PieceReassembler reassembler = new PieceReassembler(manifest);
        Map<NodeId, Set<Integer>> sources = new HashMap<>(holders);
        for (Integer index : order) {
            NodeId from = PieceSelector.chooseHolder(manifest.manifestRoot(), index, sources, exclude);
            if (from == null) {
                continue;
            }
            reassembler.restore(index, payloads.get(index));
        }
        return reassembler.assembledRoot();
    }
}
