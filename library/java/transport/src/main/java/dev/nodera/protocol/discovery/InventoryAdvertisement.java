package dev.nodera.protocol.discovery;

import dev.nodera.core.Bytes;
import dev.nodera.core.identity.NodeId;
import dev.nodera.protocol.NoderaMessage;
import dev.nodera.protocol.content.ManifestHolding;

import java.util.List;
import java.util.Objects;

/**
 * A peer's periodic "here is what I hold, in this world" gossip (Task 20) — the feed that keeps
 * every tracker's {@code ArchiveInventory} current.
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
