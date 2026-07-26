package dev.nodera.telemetry;

import dev.nodera.diagnostics.model.PeerLink;
import dev.nodera.diagnostics.model.RegionOwnership;
import dev.nodera.diagnostics.model.TelemetrySnapshot;

import java.util.ArrayList;
import java.util.List;

/**
 * Projects the diagnostics snapshot the node already builds for its own screens into the windowed,
 * bucketed events the telemetry pipeline accepts.
 *
 * <p>This is the boundary between the two consumers of the same measurements, and it is deliberately
 * not shared code. The HUD reads exact numbers about the machine it runs on, because that is the
 * machine's own business. Telemetry reads <b>buckets</b>, behind a consent gate, because a
 * byte-exact traffic figure sampled often enough is an identifier. Making the projection its own
 * class means the privacy properties do not depend on which caller happened to be asking.
 *
 * <p><b>Windowed, not per-sample.</b> The node samples every tick; sending an event per sample would
 * be a denial-of-service against the receiver and a per-second activity trace of one installation.
 * The projector is given the previous window's cumulative counters and reports the delta once per
 * interval.
 *
 * @Thread-context instances are confined to the worker's telemetry thread.
 */
public final class SnapshotProjector {

    /** Cumulative counters from the previous window — the subtrahend for the next delta. */
    private long previousBytesTx;
    private long previousBytesRx;
    private boolean primed;

    /**
     * Produce the events for one window.
     *
     * <p>Returns an empty list when consent does not permit collection <b>and does not touch the
     * snapshot at all</b> — the acceptance criterion for network task 12 is that a denied node
     * constructs no event object, so the check is the first statement, not a filter at the end.
     */
    public List<TelemetryEvent> project(TelemetrySnapshot snapshot, TickHealth health,
                                        TelemetryConsent consent, long windowSeconds,
                                        long nowMillis) {
        if (!consent.collects() || snapshot == null) {
            return List.of();
        }
        List<TelemetryEvent> events = new ArrayList<>(3);
        events.add(traffic(snapshot, windowSeconds, nowMillis));
        events.add(ownership(snapshot, nowMillis));
        events.add(tick(snapshot, health == null ? TickHealth.unknown() : health, windowSeconds,
                nowMillis));
        return events;
    }

    /**
     * Tick health for the window, supplied by the caller rather than read from the snapshot.
     *
     * <p>The diagnostics snapshot carries a coarse {@code Health} state, not a rate: TPS lives in
     * {@link dev.nodera.diagnostics.metric.TpsMeter}, keyed per node and region. Passing it in keeps
     * this class free of the question "which region's TPS?" — the worker answers that, because it is
     * the thing that knows which regions it holds.
     *
     * @param ticksPerSecond aggregate commit rate; {@code 0} when nothing is being ticked.
     * @param lagEvents      sustained-skew events observed in this window.
     */
    public record TickHealth(double ticksPerSecond, long lagEvents) {

        /** A node that is not ticking anything — the honest reading for an idle worker. */
        public static TickHealth unknown() {
            return new TickHealth(0, 0);
        }
    }

    /**
     * Traffic moved in this window, in megabyte buckets, with the peer counts that explain it.
     *
     * <p>The first window after a start is reported as zero rather than as the counters' absolute
     * values: a node that has been running for a week would otherwise report a week of traffic as
     * one window and skew every rate in the warehouse.
     */
    private TelemetryEvent traffic(TelemetrySnapshot snapshot, long windowSeconds, long nowMillis) {
        long bytesTx = snapshot.net().bytesTx();
        long bytesRx = snapshot.net().bytesRx();
        long deltaTx = primed ? Math.max(0, bytesTx - previousBytesTx) : 0;
        long deltaRx = primed ? Math.max(0, bytesRx - previousBytesRx) : 0;
        previousBytesTx = bytesTx;
        previousBytesRx = bytesRx;
        primed = true;

        int peers = snapshot.session().peers().size();
        int relayed = 0;
        for (PeerLink peer : snapshot.session().peers()) {
            // A relayed route is the one reachability fact worth counting: the ratio of relayed to
            // direct peers is how the rendezvous lane's health looks from the peer side.
            if (peer.route() != null && peer.route().startsWith("relay")) {
                relayed++;
            }
        }
        return TelemetryEvent.named(TelemetryRegistry.NET_TRAFFIC, nowMillis)
                .number("up_mb_bucket", Buckets.megabytes(deltaTx))
                .number("down_mb_bucket", Buckets.megabytes(deltaRx))
                .number("peers", Buckets.clamp(peers, 0, 4096))
                .number("relayed_peers", Buckets.clamp(relayed, 0, 4096))
                .number("window_seconds", Buckets.clamp(windowSeconds, 1, 86_400))
                .build();
    }

    /**
     * How many regions this node holds and how far their heads got through consensus.
     *
     * <p>The certification value is the population-level version of the honest distinction network
     * task 11 introduced for the HUD: a committee of one ({@code solo}) is a shortfall no amount of
     * waiting fixes, and telling it apart from {@code pending} in aggregate is what makes a
     * network-wide population problem visible as one.
     */
    private TelemetryEvent ownership(TelemetrySnapshot snapshot, long nowMillis) {
        RegionOwnership regions = snapshot.regions();
        int owned = regions.primary().size();
        int certified = regions.countCertification(RegionOwnership.Certification.CERTIFIED);
        int solo = regions.countCertification(RegionOwnership.Certification.SOLO);
        String certification = certified > 0 ? "certified" : solo > 0 ? "solo" : "pending";
        return TelemetryEvent.named(TelemetryRegistry.REGION_OWNERSHIP, nowMillis)
                .number("regions_owned", Buckets.clamp(owned, 0, 4096))
                .number("committee_size", Buckets.clamp(snapshot.session().memberCount(), 0, 64))
                .enumeration("certification", certification)
                .build();
    }

    /** Tick health for the window: a TPS bucket and the region count it was measured over. */
    private TelemetryEvent tick(TelemetrySnapshot snapshot, TickHealth health, long windowSeconds,
                                long nowMillis) {
        return TelemetryEvent.named(TelemetryRegistry.ENGINE_TICK, nowMillis)
                .number("tps_bucket", Buckets.tps(health.ticksPerSecond()))
                .number("lag_events", Buckets.clamp(health.lagEvents(), 0, 100_000))
                .number("regions", Buckets.clamp(snapshot.regions().primary().size(), 0, 4096))
                .number("window_seconds", Buckets.clamp(windowSeconds, 1, 86_400))
                .build();
    }
}
