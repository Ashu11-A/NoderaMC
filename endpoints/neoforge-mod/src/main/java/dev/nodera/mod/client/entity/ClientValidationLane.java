package dev.nodera.mod.client.entity;

import dev.nodera.mod.common.RegionSeedSpool;
import dev.nodera.endpoint.lane.LiveRegionOwnershipProvider;
import dev.nodera.endpoint.lane.ValidationLane;
import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.HashService;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.core.region.DimensionKey;
import dev.nodera.core.region.PlayerView;
import dev.nodera.core.state.StateRoot;
import dev.nodera.endpoint.lane.LanePlan;
import dev.nodera.mod.common.NoderaPeerService;
import dev.nodera.peer.validation.EntityLaneBootstrap;
import dev.nodera.peer.validation.WorkerValidationService;
import dev.nodera.simulation.engine.FlatWorldRegionEngine;
import dev.nodera.storage.GenesisManifest;
import dev.nodera.storage.event.InMemoryCertificateStore;
import dev.nodera.transport.PeerAddress;
import dev.nodera.transport.PeerTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The player's own validation lane (the no-host processing rule made real on every client): when
 * the session broadcasts the region-ownership plan ({@link LanePlan}), this client
 * derives the <b>identical</b> plan from the same pure inputs, activates every region where its
 * node is <i>primary or validator</i>, registers the other members as committee peers, and from
 * then on <b>re-executes and votes on its regions' batches with THE engine</b> over the P2P mesh.
 * Actions captured elsewhere for a region this player primaries arrive as {@code ActionForward}
 * and are proposed <i>by this player's node</i> — every player processes the events of the
 * regions assigned to them, and no member's vote counts more than any other's.
 *
 * <p>Thread-context: {@link #apply} on the client main thread; the validation service runs on the
 * client peer's runtime/transport threads. {@link #stop} on logout.
 */
public final class ClientValidationLane {

    private static final Logger LOG = LoggerFactory.getLogger("NoderaClientLane");

    private static WorkerValidationService service;
    private static dev.nodera.peer.view.LocalReplicaView replicaView;
    private static dev.nodera.mod.common.RegionSeedSpool regionSeedSpool;
    private static int activeRegions;

    private ClientValidationLane() {
    }

    /** Derive the plan from the broadcast inputs and activate this player's region set. */
    public static synchronized void apply(LanePlan plan) {
        // The client half of the root switch. A session running this build broadcasts no plan at
        // all, so this is unreachable in a homogeneous network — and that is exactly why it is
        // here: a plan from an older or hostile peer must not start a lane this build has switched
        // off. See ValidationLane for the release decision.
        if (!dev.nodera.endpoint.lane.ValidationLane.deterministicValidationEnabled()) {
            LOG.debug("ignoring a region-ownership plan — deterministic validation is off");
            return;
        }
        // The hosting JVM's server lane already validates there — a second in-JVM lane under the
        // client peer's identity duplicates work and competes for the app-message handler.
        if (NoderaPeerService.get().isHosting()) {
            return;
        }
        NodeIdentity identity = NoderaPeerService.get().clientIdentity();
        PeerTransport transport = NoderaPeerService.get().clientDataTransport();
        var runtime = NoderaPeerService.get().clientRuntime();
        if (identity == null || transport == null || runtime == null) {
            LOG.warn("plan broadcast before the client peer was up — ignored");
            return;
        }
        // A broadcast that does not include this node is stale (login/announce/replan races emit
        // a plan computed before this player's announce landed) — keep the current lane; the
        // re-plan the announce triggered broadcasts the inclusive plan next.
        String selfUuid = identity.nodeId().value().toString();
        if (plan.members().stream().noneMatch(m -> m.nodeIdUuid().equals(selfUuid))) {
            LOG.debug("stale plan without this node ignored ({} member(s))", plan.members().size());
            return;
        }
        stopLocked();
        HashService hashes = new HashService();
        GenesisManifest genesis = new GenesisManifest(
                plan.worldSeed(), plan.rulesVersion(), plan.registryFingerprint(),
                StateRoot.of(Bytes.unsafeWrap(Base64.getDecoder().decode(plan.genesisRootB64()))));
        WorkerValidationService lane = new WorkerValidationService(
                identity, transport,
                new FlatWorldRegionEngine(plan.rulesVersion(), plan.registryFingerprint(), hashes),
                hashes, new InMemoryCertificateStore(hashes),
                plan.worldSeed(), plan.rulesVersion(), plan.registryFingerprint(), 5_000L);

        Bytes actionSigner = Bytes.unsafeWrap(
                Base64.getDecoder().decode(plan.actionSignerKeyB64()));
        Map<NodeId, PlayerView> views = new LinkedHashMap<>();
        for (LanePlan.Member m : plan.members()) {
            NodeId node = new NodeId(UUID.fromString(m.nodeIdUuid()));
            views.putIfAbsent(node, PlayerView.fromBlock(
                    DimensionKey.of(m.dimNamespace(), m.dimPath()),
                    m.blockX(), m.blockZ(), m.viewDistance()));
            if (!node.equals(identity.nodeId()) && !m.route().isBlank()) {
                lane.registerPeer(node, PeerAddress.of(node, m.route()),
                        Bytes.unsafeWrap(Base64.getDecoder().decode(m.publicKeyB64())));
            }
            // Per-joiner identities (L-50): each actor is admissible under its OWN member node's
            // key — the plan's per-member key IS the actor's signer identity. The session's
            // interim signer stays co-registered only while the capture point rides the vanilla
            // session (T16 retires it); registerActor is additive, any key verifies.
            NodeId actorId = new NodeId(UUID.fromString(m.actorUuid()));
            lane.registerActor(actorId,
                    Bytes.unsafeWrap(Base64.getDecoder().decode(m.publicKeyB64())));
            lane.registerActor(actorId, actionSigner);
        }

        // The always-on peers of this session, and the other half of the seat: an address to send
        // each one a proposal, and a key to verify the vote that comes back.
        java.util.LinkedHashSet<NodeId> residents =
                residentSeatPool(plan.residents(), identity.nodeId(), views.keySet());
        for (LanePlan.Resident r : plan.residents()) {
            NodeId node = new NodeId(UUID.fromString(r.nodeIdUuid()));
            if (residents.contains(node)) {
                lane.registerPeer(node, PeerAddress.of(node, r.route()),
                        Bytes.unsafeWrap(Base64.getDecoder().decode(r.publicKeyB64())));
            }
        }

        int mine = 0;
        for (EntityLaneBootstrap.PlannedRegion planned : EntityLaneBootstrap.plan(
                views, identity.nodeId(), plan.gameTime(), plan.committeeSize(), residents)) {
            // `lease.contains` is "primary or validator", which is exactly this test — and unlike
            // spelling it out, it keeps the membership scan out of the per-region loop's bytecode.
            if (planned.lease().contains(identity.nodeId())) {
                lane.activateRegion(
                        EntityLaneBootstrap.initialSnapshot(planned.region()), planned.lease());
                mine++;
            }
        }
        // L-16: the prediction/rollback overlay — every commit the lane applies reconciles the
        // local replica view; the renderer binds to render() (the GUI half of the row).
        dev.nodera.peer.view.LocalReplicaView view = new dev.nodera.peer.view.LocalReplicaView(
                new FlatWorldRegionEngine(plan.rulesVersion(), plan.registryFingerprint(), hashes),
                hashes, plan.worldSeed(), plan.rulesVersion(), plan.registryFingerprint());
        // Two things happen on every commit: the overlay reconciles (L-16), and the snapshot is
        // offered to the always-on worker so the region outlives this process (worker L-41). The
        // spool decides whether to actually push — it throttles per region and never touches the
        // lane's thread — so this stays one call each.
        dev.nodera.mod.common.RegionSeedSpool spool =
                dev.nodera.mod.common.RegionSeedSpool.companion();
        regionSeedSpool = spool;
        lane.onCommit((snapshot, root) -> {
            view.committed(snapshot, root);
            spool.offer(snapshot);
        });
        runtime.onApplicationMessage(lane::onMessage);
        service = lane;
        replicaView = view;
        activeRegions = mine;
        // The joiner's HUD region panel shows THIS player's real ownership (L-31 regions half).
        dev.nodera.endpoint.lane.LiveRegionOwnershipProvider.activate(lane, identity.nodeId());
        LOG.info("client validation lane active on {} region(s) — this player re-executes and "
                        + "votes for its own region set ({} member node(s) + {} resident peer(s) "
                        + "in the plan)", mine, views.size(), residents.size());
    }

    /**
     * The resident validator pool this client plans with, from the pool the session broadcast.
     *
     * <p>Residents are a <b>plan input</b>, not a decoration: {@code ViewOwnershipPlanner} staffs
     * each committee's leftover validator seats from this list, so a member that derives the plan
     * without it derives different leases. The failure was silent and cost a live diagnosis — this
     * player primaries a region, a resident re-executes the same batch and votes, and the vote is
     * dropped as coming from a node that is not in the committee the client computed (network L-30).
     * Passing the pool is what makes the host's computation and this one the same computation.
     *
     * <p>Filtered, because the pool arrives from the network and a seat has requirements. Self is
     * dropped (this node is ranked geometrically by its own view, not seated as a resident), a node
     * that also appears as a player is dropped for the same reason, a routeless entry is dropped
     * because a validator a proposal cannot reach is a seat that silently never votes, and a keyless
     * one is dropped because a vote that cannot be verified cannot be counted. Those are the same
     * four rules the host applies when it builds the pool, which is the point — a filter that
     * disagreed with the host's would re-open the divergence from the other side. Order is
     * preserved: the planner's seat assignment reads the pool in order, so a reordering here would
     * be a different plan.
     *
     * <p>A {@code LinkedHashSet} rather than a list: the planner reads the pool in order, so the
     * order is part of the plan and cannot be sorted away, while the caller also asks it "is this
     * resident seated?" once per broadcast entry. A list would answer that with a linear scan per
     * entry.
     *
     * @param residents   the broadcast pool.
     * @param self        this node.
     * @param playerNodes every node that carries a field of view in the same plan.
     * @return the seatable residents, in broadcast order.
     * @Thread-context any thread; pure.
     */
    static java.util.LinkedHashSet<NodeId> residentSeatPool(
            List<LanePlan.Resident> residents, NodeId self,
            java.util.Set<NodeId> playerNodes) {
        java.util.LinkedHashSet<NodeId> pool = new java.util.LinkedHashSet<>();
        for (LanePlan.Resident r : residents) {
            NodeId node = new NodeId(UUID.fromString(r.nodeIdUuid()));
            if (node.equals(self) || playerNodes.contains(node) || r.route().isBlank()
                    || r.publicKeyB64().isBlank()) {
                continue;
            }
            pool.add(node); // a repeated resident is one seat; the host's pool is keyed by node id
        }
        return pool;
    }

    /** Tear the lane down (logout / new plan). */
    public static synchronized void stop() {
        stopLocked();
    }

    /**
     * The client's local-replica view (L-16): {@code predict} on capture, {@code render} in the
     * renderer bind. Null while no validation lane is active.
     */
    public static synchronized dev.nodera.peer.view.LocalReplicaView replicaView() {
        return replicaView;
    }

    private static void stopLocked() {
        if (service != null) {
            dev.nodera.endpoint.lane.LiveRegionOwnershipProvider.deactivate(service);
            var runtime = NoderaPeerService.get().clientRuntime();
            if (runtime != null) {
                runtime.onApplicationMessage(null);
            }
            service = null;
            replicaView = null;
            if (regionSeedSpool != null) {
                regionSeedSpool.close();
                regionSeedSpool = null;
            }
            activeRegions = 0;
        }
    }
}
