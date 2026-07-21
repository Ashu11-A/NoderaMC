package dev.nodera.diagnostics.source;

import dev.nodera.diagnostics.model.EntityControl;
import dev.nodera.diagnostics.model.HealthStat;
import dev.nodera.diagnostics.model.NetStats;
import dev.nodera.diagnostics.model.RegionOwnership;
import dev.nodera.diagnostics.model.SessionInfo;
import dev.nodera.diagnostics.model.TelemetrySnapshot;
import dev.nodera.core.identity.NodeId;

/**
 * Mutable accumulator a {@link DiagnosticsSource} writes into while
 * {@link dev.nodera.diagnostics.DiagnosticsCollector#sample} assembles a
 * {@link TelemetrySnapshot} (Task 18).
 *
 * <p>The collector owns the {@code tick}, {@code self}, {@code bootstrap}, {@code net} and
 * {@code health} fields (driver-supplied or derived); sources contribute {@code session},
 * {@code regions}, and {@code entities}. Every field defaults so a build with no sources still
 * yields a valid (empty) snapshot.
 *
 * <p>Thread-context: not thread-safe; used on the single collector sample thread.
 */
public final class SnapshotBuilder {

    private long tick;
    private NodeId self;
    private boolean bootstrap;
    private SessionInfo session;
    private NetStats net;
    private RegionOwnership regions = RegionOwnership.empty();
    private EntityControl entities = EntityControl.empty();
    private HealthStat health = HealthStat.healthy();

    /** @param tick the server tick at the sample. */
    public void tick(long tick) {
        this.tick = tick;
    }

    /** @param self this peer's stable id (collector-supplied). */
    public void self(NodeId self) {
        this.self = self;
    }

    /** @param bootstrap {@code true} if this peer is the bootstrap peer (collector-supplied). */
    public void bootstrap(boolean bootstrap) {
        this.bootstrap = bootstrap;
    }

    /** @param session the session membership + peer links (contributed by the runtime source). */
    public void session(SessionInfo session) {
        this.session = session;
    }

    /** @param net the aggregate traffic stats (collector-supplied from meter + rate windows). */
    public void net(NetStats net) {
        this.net = net;
    }

    /** @param regions region ownership (contributed by {@link RegionOwnershipProvider}). */
    public void regions(RegionOwnership regions) {
        this.regions = regions == null ? RegionOwnership.empty() : regions;
    }

    /** @param entities controlled entities (contributed by {@link EntityControlProvider}). */
    public void entities(EntityControl entities) {
        this.entities = entities == null ? EntityControl.empty() : entities;
    }

    /** @param health the derived session health (collector-supplied). */
    public void health(HealthStat health) {
        this.health = health == null ? HealthStat.healthy() : health;
    }

    /** @return the session contributed so far (may be {@code null} if no source set one). */
    public SessionInfo session() {
        return session;
    }

    /**
     * @return the immutable snapshot. {@code self} may be {@code null} only if no source supplied
     *         one (a headless test path); production always sets it.
     */
    public TelemetrySnapshot build() {
        return new TelemetrySnapshot(tick, self, bootstrap, session, net, regions, entities, health);
    }
}
