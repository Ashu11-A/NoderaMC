package dev.nodera.bench;

import dev.nodera.core.Bytes;
import dev.nodera.core.identity.NodeCapabilities;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.region.DimensionKey;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.ChunkColumnState;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.protocol.membership.PeerEntry;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Deterministic builders shared by every benchmark in this source set.
 *
 * <h2>Why the fixtures are fixed-value</h2>
 *
 * <p>A benchmark whose input changes run to run measures the input, not the code. Every value here
 * is derived from its index with plain arithmetic, so two runs on two machines feed the code under
 * measurement byte-identical work and the only difference left between their numbers is the thing
 * we are trying to see.
 *
 * <p>The shapes are the ones the system actually moves: an 8x8-chunk region snapshot with 24
 * sections per column (what the engine freezes), peer entries carrying real capabilities, and
 * routes in the {@code host:port} form the tracker hands out.
 *
 * <p>Thread-context: stateless static builders; safe for any thread.
 */
final class BenchFixtures {

    /** The engine's region shape: 8x8 chunk columns. */
    static final int REGION_CHUNKS = 8;

    /** Sections per chunk column in a 1.21 overworld (y = -64..320). */
    static final int SECTION_COUNT = 24;

    /** The overworld's minimum build height. */
    static final int MIN_Y = -64;

    private BenchFixtures() {}

    static NodeId node(long lsb) {
        return new NodeId(new UUID(0L, lsb));
    }

    static RegionId region(int rx, int rz) {
        return new RegionId(DimensionKey.overworld(), rx, rz);
    }

    /** A 32-byte pseudo-hash derived from {@code seed} — a stand-in for a genesis/world id. */
    static Bytes digest(int seed) {
        byte[] raw = new byte[32];
        for (int i = 0; i < raw.length; i++) {
            raw[i] = (byte) (seed * 31 + i * 7);
        }
        return Bytes.unsafeWrap(raw);
    }

    /**
     * A full region snapshot whose columns all differ, so no two pieces are accidentally equal and
     * the piece plane is as wide as a real one.
     */
    static RegionSnapshot snapshot(RegionId region, long tick) {
        int ox = region.originChunkX();
        int oz = region.originChunkZ();
        List<ChunkColumnState> columns = new ArrayList<>(REGION_CHUNKS * REGION_CHUNKS);
        for (int dx = 0; dx < REGION_CHUNKS; dx++) {
            for (int dz = 0; dz < REGION_CHUNKS; dz++) {
                int[] palette = new int[SECTION_COUNT];
                for (int s = 0; s < SECTION_COUNT; s++) {
                    palette[s] = (dx * REGION_CHUNKS + dz) * 31 + s + (int) tick;
                }
                columns.add(new ChunkColumnState(ox + dx, oz + dz, palette, MIN_Y, SECTION_COUNT));
            }
        }
        return new RegionSnapshot(region, SnapshotVersion.INITIAL, tick, columns);
    }

    /** A peer entry as the tracker publishes it: real route, real capabilities, published key. */
    static PeerEntry peer(long lsb) {
        return new PeerEntry(
                node(lsb),
                "10." + ((lsb >> 16) & 0xFF) + "." + ((lsb >> 8) & 0xFF) + "." + (lsb & 0xFF) + ":25599",
                NodeCapabilities.initial(),
                lsb % 8 == 0,
                digest((int) lsb),
                "nodera/bench");
    }

    /** {@code count} peer entries, deterministic and distinct. */
    static List<PeerEntry> peers(int count) {
        List<PeerEntry> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            out.add(peer(i + 1));
        }
        return out;
    }
}
