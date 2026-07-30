package dev.nodera.headless;

import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.core.state.StateRoot;

import java.util.Collection;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * Seeds every region this worker commits into the content plane (worker L-41).
 *
 * <p>The archive lane already keeps a hosted world's <i>save</i> alive after the driving game
 * closes. This is the other half of that promise: the validated lane's canonical region state,
 * seeded on the always-on process rather than on whichever game happened to hold the seat, so a
 * joiner can fetch the region it is standing in without pulling a whole world and without the
 * committing node still being online.
 *
 * <p><b>The one real decision here is attribution.</b> A {@link RegionSnapshot} knows its region
 * and its dimension; it does not know which world it belongs to, because a region id is a
 * coordinate and a world id is an identity. So this class can only file a commit under a world
 * when there is exactly one world it could be: hosting none means there is nothing to attribute it
 * to, and hosting several means guessing — and a snapshot filed under the wrong world would be
 * advertised to peers fetching a different world entirely, which is worse than not seeding it.
 * That case is reported once rather than every commit, because it is a deployment shape, not an
 * event.
 *
 * @Thread-context called on the validation lane's commit path; must not block. Seeding is a split
 *                 plus a store write, and a fault in it is contained — a region that fails to seed
 *                 costs availability, never the commit that produced it.
 */
public final class CommittedRegionSeeder implements BiConsumer<RegionSnapshot, StateRoot> {

    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger("NoderaWorker");

    private final Supplier<Collection<String>> hostedWorldIds;
    private final BiConsumer<String, RegionSnapshot> seed;

    /** Suppresses the "cannot attribute" line after the first, per distinct hosted-world count. */
    private volatile int reportedAmbiguity = -1;

    /**
     * @param hostedWorldIds the worlds this node hosts, read per commit so a world hosted after
     *                       startup is picked up without a restart.
     * @param seed           what to do with an attributed snapshot — normally
     *                       {@code archive::seedRegion}.
     */
    public CommittedRegionSeeder(Supplier<Collection<String>> hostedWorldIds,
                                 BiConsumer<String, RegionSnapshot> seed) {
        if (hostedWorldIds == null || seed == null) {
            throw new IllegalArgumentException("hostedWorldIds and seed must not be null");
        }
        this.hostedWorldIds = hostedWorldIds;
        this.seed = seed;
    }

    @Override
    public void accept(RegionSnapshot snapshot, StateRoot root) {
        if (snapshot == null) {
            return;
        }
        List<String> worlds;
        try {
            Collection<String> current = hostedWorldIds.get();
            worlds = current == null ? List.of() : List.copyOf(current);
        } catch (RuntimeException degraded) {
            return;
        }
        if (worlds.size() != 1) {
            reportAmbiguity(worlds.size(), snapshot);
            return;
        }
        reportedAmbiguity = -1;
        try {
            seed.accept(worlds.get(0), snapshot);
        } catch (RuntimeException degraded) {
            // A region that could not be seeded is a region peers must fetch from somebody else.
            // That is a worse world to be in, not a broken one, and it must never propagate into
            // the commit path that produced the snapshot.
            LOG.warn("could not seed committed region {} v{}: {}",
                    snapshot.region(), snapshot.version().value(), degraded.toString());
        }
    }

    private void reportAmbiguity(int hosted, RegionSnapshot snapshot) {
        if (reportedAmbiguity == hosted) {
            return;
        }
        reportedAmbiguity = hosted;
        if (hosted == 0) {
            LOG.debug("not seeding committed region {} — this node hosts no world to file it under",
                    snapshot.region());
        } else {
            LOG.info("not seeding committed regions — this node hosts {} worlds and a region "
                    + "snapshot carries no world id, so attribution would be a guess", hosted);
        }
    }
}
