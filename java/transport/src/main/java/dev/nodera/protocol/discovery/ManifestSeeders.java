package dev.nodera.protocol.discovery;

import dev.nodera.core.Bytes;
import dev.nodera.core.identity.NodeId;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * "These peers hold at least one piece of the blob {@code manifestRoot}" — one row of the tracker's
 * seeder index (Task 20).
 *
 * <p>The tracker reports seeders at <b>manifest</b> granularity while the inventory keeps them at
 * <b>piece</b> granularity. That split is deliberate: a tracker answer is a directory lookup that
 * must stay small enough to hand to a UI listing dozens of worlds, whereas rarest-first selection
 * needs the full bitmaps and asks the holders directly (Task 19's {@code ContentAvailability}).
 *
 * <p>The seeder list is canonicalised — de-duplicated and sorted by node id — so the encoded form
 * is byte-stable and two trackers with the same knowledge answer identically.
 *
 * <p>Not itself a {@code NoderaMessage}: it is a body component encoded inline inside
 * {@link TrackerResponse}, like {@code PeerEntry} inside {@code MembershipUpdate}.
 *
 * <p>Thread-context: immutable record, safe for any thread.
 *
 * @param manifestRoot the blob's manifest root.
 * @param seeders      peers holding at least one of its pieces, de-duplicated and sorted.
 */
public record ManifestSeeders(Bytes manifestRoot, List<NodeId> seeders) {

    private static final Comparator<NodeId> BY_ID = Comparator.comparing(n -> n.value().toString());

    /**
     * Compact constructor: validates and canonicalises the seeder list.
     *
     * @throws IllegalArgumentException if an argument is null.
     */
    public ManifestSeeders {
        Objects.requireNonNull(manifestRoot, "manifestRoot");
        Objects.requireNonNull(seeders, "seeders");
        LinkedHashSet<NodeId> unique = new LinkedHashSet<>();
        for (NodeId id : seeders) {
            unique.add(Objects.requireNonNull(id, "seeder"));
        }
        List<NodeId> sorted = new ArrayList<>(unique);
        sorted.sort(BY_ID);
        seeders = List.copyOf(sorted);
    }

    /** @return how many peers seed this manifest. */
    public int seederCount() {
        return seeders.size();
    }

    @Override
    public String toString() {
        return "ManifestSeeders[" + manifestRoot.toShortHex(6) + " x" + seeders.size() + "]";
    }
}
