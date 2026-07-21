package dev.nodera.diagnostics.view;

import dev.nodera.diagnostics.state.Semantic;

import java.util.ArrayList;
import java.util.List;

/**
 * Task 31d: the pure view model behind the per-world <em>piece map</em> — a torrent-client-style grid
 * of a world's content pieces, so a player can see how much of a world they hold and how synchronised
 * the swarm is. Held pieces are green; other states (syncing, verifying, missing, locked, rare) get
 * their own colour policy. This class holds only the grid model + aggregates; the mod-side
 * {@code PieceMapWidget} renders it and {@code PieceMapDataSource} feeds the live per-piece state.
 *
 * <p>Layering: {@code diagnostics} → {@code core} only; no Minecraft, no {@code distribution}
 * dependency (the mod side unpacks {@code PieceManifest}/inventory into the plain {@link PieceState}
 * array before calling in). Stateless static functions over immutable inputs — unit-testable on the
 * gate with no GUI env, the same discipline as {@link TorrentWorldListView}.
 *
 * @Thread-context stateless; any thread.
 */
public final class PieceMapView {

    /** Lang key for the piece-map panel/screen title. */
    public static final String TITLE = "nodera.piecemap.title";

    /** The state of one content piece, in colour-priority order for the legend. */
    public enum PieceState {
        /** Present locally and hash-verified — the green cell. */
        HELD,
        /** Arrived, hash check pending. */
        VERIFYING,
        /** In-flight download. */
        SYNCING,
        /** Not held; available somewhere in the swarm. */
        MISSING,
        /** Availability below the rarest-first threshold (at risk). */
        RARE,
        /** Locked against render/edit (un-arrived section, fail-closed, Task 19 ChunkLockMap). */
        LOCKED,
        /** World is password-locked and no key is held (Task 23). */
        ENCRYPTED_NO_KEY
    }

    /** One grid cell: the piece index and its state. */
    public record PieceCell(int index, PieceState state) {
        public PieceCell {
            if (index < 0) {
                throw new IllegalArgumentException("index must be >= 0");
            }
            if (state == null) {
                throw new IllegalArgumentException("state must not be null");
            }
        }
    }

    /**
     * The whole piece map for a world.
     *
     * @param worldName the world's display name.
     * @param cells     one cell per piece, in index order.
     * @param seeders   distinct seeders currently in the swarm.
     */
    public record PieceMap(String worldName, List<PieceCell> cells, int seeders) {
        public PieceMap {
            worldName = worldName == null ? "" : worldName;
            cells = cells == null ? List.of() : List.copyOf(cells);
            if (seeders < 0) {
                throw new IllegalArgumentException("seeders must be >= 0");
            }
        }

        /** @return total number of pieces. */
        public int total() {
            return cells.size();
        }

        /** @return count of pieces in the given state. */
        public long count(PieceState state) {
            return cells.stream().filter(c -> c.state() == state).count();
        }

        /** @return pieces held locally as a permille (0..1000) of the total; 1000 when empty. */
        public int heldPermille() {
            if (cells.isEmpty()) {
                return 1000;
            }
            return (int) (count(PieceState.HELD) * 1000L / cells.size());
        }
    }

    private PieceMapView() {
    }

    /** Build a {@link PieceMap} from a per-piece state array (index = array position). */
    public static PieceMap map(String worldName, List<PieceState> states, int seeders) {
        List<PieceCell> cells = new ArrayList<>(states == null ? 0 : states.size());
        if (states != null) {
            for (int i = 0; i < states.size(); i++) {
                PieceState s = states.get(i);
                cells.add(new PieceCell(i, s == null ? PieceState.MISSING : s));
            }
        }
        return new PieceMap(worldName, cells, seeders);
    }

    /** The colour policy for a piece state — the widget maps this {@link Semantic} to a fill colour. */
    public static Semantic semanticOf(PieceState state) {
        return switch (state) {
            case HELD -> Semantic.WORLD_HEALTHY;
            case VERIFYING -> Semantic.VALIDATING;
            case SYNCING -> Semantic.DEGRADED;
            case MISSING -> Semantic.UNASSIGNED;
            case RARE -> Semantic.REPLICA;
            case LOCKED -> Semantic.CRITICAL;
            case ENCRYPTED_NO_KEY -> Semantic.WORLD_DEAD;
        };
    }

    /** The aggregates line: {@code "New World · 42.0% held · 128/305 pieces · 4 seeders"}. */
    public static String aggregates(PieceMap map) {
        int permille = map.heldPermille();
        String pct = (permille / 10) + "." + (permille % 10) + "%";
        return map.worldName() + " · " + pct + " held · "
                + map.count(PieceState.HELD) + "/" + map.total() + " pieces · "
                + map.seeders() + " seeders";
    }
}
