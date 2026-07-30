package dev.nodera.protocol.content;

import dev.nodera.core.Bytes;
import dev.nodera.core.identity.NodeId;
import dev.nodera.protocol.NoderaMessage;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * "This is what I hold" — a peer's piece-level holdings, advertised to the swarm (Task 19).
 *
 * <p>Consumed by the fetching side's holder set (so {@code PieceSelector} can compute rarest-first
 * order), by Task 20's {@code ArchiveInventory} ("who has what", the tracker's seeder index), and
 * by Task 21's placement audit (expected holders vs actual).
 *
 * <p>The holdings list is canonicalised on construction — sorted by {@code manifestRoot} hex, with
 * a later entry for the same root replacing an earlier one — so an advertisement's encoded form is
 * byte-stable and a peer cannot smuggle contradictory duplicate claims for one manifest past a
 * receiver that only reads the first occurrence.
 *
 * <p>Thread-context: immutable record, safe for any thread.
 *
 * @param holder   the advertising peer.
 * @param holdings its per-manifest piece bitmaps, sorted by manifest root.
 */
public record ContentAvailability(NodeId holder, List<ManifestHolding> holdings)
        implements NoderaMessage {

    private static final Comparator<ManifestHolding> BY_ROOT =
            Comparator.comparing(h -> h.manifestRoot().toHex());

    /**
     * Compact constructor: validates and canonicalises the holdings list.
     *
     * @throws IllegalArgumentException if an argument is null.
     */
    public ContentAvailability {
        Objects.requireNonNull(holder, "holder");
        Objects.requireNonNull(holdings, "holdings");
        List<ManifestHolding> deduped = new ArrayList<>();
        for (ManifestHolding h : holdings) {
            Objects.requireNonNull(h, "holding");
            deduped.removeIf(existing -> existing.manifestRoot().equals(h.manifestRoot()));
            deduped.add(h);
        }
        deduped.sort(BY_ROOT);
        holdings = List.copyOf(deduped);
    }

    /**
     * @param manifestRoot the manifest to look up.
     * @return this peer's holding for that manifest, or {@code null} if it advertises none.
     * @Thread-context any thread.
     */
    public ManifestHolding holdingOf(Bytes manifestRoot) {
        for (ManifestHolding h : holdings) {
            if (h.manifestRoot().equals(manifestRoot)) {
                return h;
            }
        }
        return null;
    }

    @Override
    public String toString() {
        return "ContentAvailability[" + holder + " manifests=" + holdings.size() + "]";
    }
}
