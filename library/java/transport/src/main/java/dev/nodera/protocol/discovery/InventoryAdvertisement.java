package dev.nodera.protocol.discovery;

import dev.nodera.core.Bytes;
import dev.nodera.core.identity.NodeId;
import dev.nodera.protocol.NoderaMessage;
import dev.nodera.protocol.content.ManifestHolding;

import java.util.List;
import java.util.Objects;

/**
 * A peer's periodic "here is what I hold, in this world" gossip (Task 20).
 *
 * <p><b>Nothing sends this today.</b> The Java-side tracker cache it fed, {@code ArchiveInventory},
 * was deleted on 2026-08-06 (Plan 11 round 2, issue #210) — only the repair lane consulted it, and
 * that lane went with it. The same holdings still reach a tracker, by a different route: peers put
 * the {@link ManifestHolding} list on their {@code TrackerAnnounce}, and the Rust tracker keeps it
 * in the peer registry ({@code tracker/src/registry.rs}) to answer {@code ManifestSeeders} queries.
 * Tag 29 is frozen in {@code WireRegistry} and the codec still round-trips this record; treat it as
 * a reserved shape, not as live protocol.
 *
 * <p>It carries the same {@link ManifestHolding} shape as Task 19's {@code ContentAvailability},
 * with one addition that earns it a separate message type: the <b>genesis hash</b>. A network may
 * host several torrent worlds at once, so an inventory feed that did not say <i>which world</i> a
 * manifest belongs to would force every receiver to resolve manifests to worlds by lookup — and
 * would silently mis-index a manifest the receiver has never seen. {@code ContentAvailability}
 * does not need the field because it answers a question already scoped to one manifest.
 *
 * <p>Advertisements are advisory and untrusted: a peer can claim to hold anything. The claim costs
 * the liar a wasted request when a fetcher tries and the pieces fail to verify (Task 19), and Task
 * 22's reliability scoring is what makes repeated lying expensive.
 *
 * <p>Thread-context: immutable record, safe for any thread.
 *
 * @param genesisHash the world these holdings belong to.
 * @param holder      the advertising peer.
 * @param holdings    its per-manifest piece bitmaps.
 */
public record InventoryAdvertisement(Bytes genesisHash, NodeId holder, List<ManifestHolding> holdings)
        implements NoderaMessage {

    /**
     * Compact constructor.
     *
     * @throws IllegalArgumentException if an argument is null or {@code genesisHash} is empty.
     */
    public InventoryAdvertisement {
        Objects.requireNonNull(genesisHash, "genesisHash");
        Objects.requireNonNull(holder, "holder");
        Objects.requireNonNull(holdings, "holdings");
        if (genesisHash.isEmpty()) {
            throw new IllegalArgumentException("genesisHash must not be empty");
        }
        holdings = List.copyOf(holdings);
    }

    @Override
    public String toString() {
        return "InventoryAdvertisement[" + genesisHash.toShortHex(6) + " " + holder
                + " manifests=" + holdings.size() + "]";
    }
}
