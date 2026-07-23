package dev.nodera.mod.client.entity;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.HashService;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.core.region.DimensionKey;
import dev.nodera.core.region.PlayerView;
import dev.nodera.core.state.StateRoot;
import dev.nodera.mod.common.NoderaLanePlanPayload;
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
import java.util.Map;
import java.util.UUID;

/**
 * The player's own validation lane (the no-host processing rule made real on every client): when
 * the session broadcasts the region-ownership plan ({@link NoderaLanePlanPayload}), this client
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
    private static int activeRegions;

    private ClientValidationLane() {
    }

    /** Derive the plan from the broadcast inputs and activate this player's region set. */
    public static synchronized void apply(NoderaLanePlanPayload plan) {
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
        for (NoderaLanePlanPayload.Member m : plan.members()) {
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

        int mine = 0;
        for (EntityLaneBootstrap.PlannedRegion planned : EntityLaneBootstrap.plan(
                views, identity.nodeId(), plan.gameTime(), plan.committeeSize())) {
            if (planned.locallyPrimary()
                    || planned.lease().validators().contains(identity.nodeId())) {
                lane.activateRegion(
                        EntityLaneBootstrap.initialSnapshot(planned.region()), planned.lease());
                mine++;
            }
        }
        runtime.onApplicationMessage(lane::onMessage);
        service = lane;
        activeRegions = mine;
        // The joiner's HUD region panel shows THIS player's real ownership (L-31 regions half).
        dev.nodera.mod.server.entity.LiveRegionOwnershipProvider.activate(lane, identity.nodeId());
        LOG.info("client validation lane active on {} region(s) — this player re-executes and "
                + "votes for its own region set ({} member node(s) in the plan)", mine, views.size());
    }

    /** Tear the lane down (logout / new plan). */
    public static synchronized void stop() {
        stopLocked();
    }

    /** @return regions this player currently validates (diagnostics). */
    public static synchronized int activeRegionCount() {
        return service == null ? 0 : activeRegions;
    }

    private static void stopLocked() {
        if (service != null) {
            dev.nodera.mod.server.entity.LiveRegionOwnershipProvider.deactivate(service);
            var runtime = NoderaPeerService.get().clientRuntime();
            if (runtime != null) {
                runtime.onApplicationMessage(null);
            }
            service = null;
            activeRegions = 0;
        }
    }
}
