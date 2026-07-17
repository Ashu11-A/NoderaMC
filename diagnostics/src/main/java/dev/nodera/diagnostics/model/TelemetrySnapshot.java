package dev.nodera.diagnostics.model;

import dev.nodera.core.identity.NodeId;

/**
 * The single immutable per-sample aggregate (Task 18) — the value type that separates capture from
 * presentation. Every surface (tab list, boss bar, chat, action bar) renders from a
 * {@code TelemetrySnapshot} via {@link dev.nodera.diagnostics.view.DiagnosticsView}; no surface reads
 * runtime state directly.
 *
 * @param tick      the server tick at which the sample was taken.
 * @param self      this peer's stable id.
 * @param bootstrap {@code true} if this peer is the bootstrap/full-archival peer.
 * @param session   session membership + peer links.
 * @param net       aggregate traffic statistics.
 * @param regions   region ownership (placeholder until Task 6).
 * @param entities  controlled entities (placeholder until Task 12).
 * @param health    session health.
 * @Thread-context immutable record, any thread.
 */
public record TelemetrySnapshot(
        long tick,
        NodeId self,
        boolean bootstrap,
        SessionInfo session,
        NetStats net,
        RegionOwnership regions,
        EntityControl entities,
        HealthStat health) {

    /** Compact constructor substitutes non-null defaults for the optional sub-records. */
    public TelemetrySnapshot {
        session = session == null
                ? new SessionInfo(0L, null, false, 1, "peer", java.util.List.of())
                : session;
        net = net == null
                ? new NetStats(0, 0, 0, 0, 0, 0, 0, 0, java.util.Map.of())
                : net;
        regions = regions == null ? RegionOwnership.empty() : regions;
        entities = entities == null ? EntityControl.empty() : entities;
        health = health == null ? HealthStat.healthy() : health;
    }
}
