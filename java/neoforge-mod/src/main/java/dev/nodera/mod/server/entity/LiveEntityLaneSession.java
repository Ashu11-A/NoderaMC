package dev.nodera.mod.server.entity;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.HashService;
import dev.nodera.core.region.RegionLease;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.mod.common.NoderaPeerService;
import dev.nodera.peer.validation.DurableActionJournal;
import dev.nodera.peer.validation.EntityLaneResume;
import dev.nodera.peer.validation.DurableInventoryCreditJournal;
import dev.nodera.peer.validation.WorkerValidationService;
import dev.nodera.peer.validation.WorldStoreExternalHeads;
import dev.nodera.peer.validation.WorldStoreTransferJournal;
import dev.nodera.peer.validation.WorldStoreVotePersistence;
import dev.nodera.simulation.engine.FlatWorldRegionEngine;
import dev.nodera.storage.GenesisManifest;
import dev.nodera.storage.rocksdb.RocksWorldStore;
import dev.nodera.transport.PeerAddress;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owns one live Task-12 runtime and every durable resource it borrows from the host peer. */
public final class LiveEntityLaneSession implements AutoCloseable {

    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger("NoderaEntityLane");

    private final NoderaPeerService.HostContext host;
    private final RocksWorldStore store;
    private final LiveEntityLaneRuntime live;
    private final AtomicBoolean active;

    private LiveEntityLaneSession(
            NoderaPeerService.HostContext host,
            RocksWorldStore store,
            LiveEntityLaneRuntime live,
            AtomicBoolean active) {
        this.host = host;
        this.store = store;
        this.live = live;
        this.active = active;
    }

    public static LiveEntityLaneSession open(
            MinecraftServer server,
            GenesisManifest genesis,
            List<RegionBinding> regions,
            List<CommitteePeer> peers,
            Path stateDirectory,
            NoderaPeerService.HostContext host) {
        if (server == null || genesis == null || regions == null || regions.isEmpty()
                || peers == null || stateDirectory == null || host == null) {
            throw new IllegalArgumentException("live entity session arguments must not be null/empty");
        }
        HashService hashes = new HashService();
        RocksWorldStore store = RocksWorldStore.open(
                stateDirectory.resolve("world-store"), genesis, hashes, false);
        AtomicBoolean active = new AtomicBoolean(true);
        LiveEntityLaneRuntime live = null;
        boolean handlerInstalled = false;
        try {
            DurableActionJournal actions = new DurableActionJournal(
                    stateDirectory.resolve("actions.bin"));
            // A dirty shutdown leaves RESERVED actions with no recorded outcome. Certified state is
            // recovered from the world store, never replayed from this journal, so the safe
            // reconciliation is to compensate: abort the reservations while keeping their sequence
            // numbers consumed (the restart watermarks come from retained(), aborted included).
            int compensated = actions.abortPending().size();
            if (compensated > 0) {
                LOG.warn("Compensated {} uncommitted live action(s) from a dirty shutdown", compensated);
            }
            DurableInventoryCreditJournal credits = new DurableInventoryCreditJournal(
                    stateDirectory.resolve("inventory-credits.bin"));
            WorldStoreTransferJournal transfers = new WorldStoreTransferJournal(store);
            ServerEntityWorldView world = new ServerEntityWorldView(credits);
            WorldStoreVotePersistence votes = new WorldStoreVotePersistence(
                    store, hashes, stateDirectory.resolve("votes.bin"));
            WorldStoreExternalHeads externalHeads = new WorldStoreExternalHeads(
                    store, hashes, stateDirectory.resolve("external-heads.bin"));
            WorkerValidationService validation = new WorkerValidationService(
                    host.identity(), host.transport(),
                    new FlatWorldRegionEngine(
                            genesis.rulesVersion(), genesis.registryFingerprint(), hashes),
                    hashes, store.certificates(), genesis.worldSeed(), genesis.rulesVersion(),
                    genesis.registryFingerprint(), 5_000L, votes,
                    LiveEntityLaneRuntime.admission(server), world, transfers, actions,
                    task -> server.execute(() -> {
                        if (active.get()) {
                            task.run();
                        }
                    }));
            validation.setExternalCommitListener(externalHeads::externalCommitted);
            for (CommitteePeer peer : peers) {
                validation.registerPeer(
                        peer.address().nodeId(), peer.address(), peer.publicKey());
            }
            live = new LiveEntityLaneRuntime(validation, world, host.identity(), actions);
            for (RegionBinding region : regions) {
                // Reopen resume (issue #34 / L-50): prefer the store head — the latest
                // quorum-committed or external-committed snapshot — over the caller's derived
                // genesis snapshot, so a reopened session continues from where the last one
                // committed instead of re-feeding INITIAL all-AIR state.
                RegionSnapshot base = EntityLaneResume.resumeHead(
                                votes, externalHeads, region.snapshot().region())
                        .orElse(region.snapshot());
                if (!base.version().equals(region.snapshot().version())) {
                    LOG.info("Resuming region {} from store head v{} (genesis would be v{})",
                            region.snapshot().region(), base.version().value(),
                            region.snapshot().version().value());
                }
                live.activate(region.level(), base, region.lease());
            }
            host.runtime().onApplicationMessage((from, message) -> {
                if (active.get()) {
                    validation.onMessage(from, message);
                }
            });
            handlerInstalled = true;
            validation.recoverTransfers(transfers.recoverable(), transfers.completed());
            live.install();
            LiveEntityControlProvider.activate(live);
            LiveRegionOwnershipProvider.activate(validation, host.identity().nodeId());
            return new LiveEntityLaneSession(host, store, live, active);
        } catch (RuntimeException failure) {
            active.set(false);
            if (handlerInstalled) {
                host.runtime().onApplicationMessage(null);
            }
            if (live != null) {
                live.close();
            }
            store.close();
            throw failure;
        }
    }

    public LiveEntityLaneRuntime runtime() {
        return live;
    }

    /**
     * Register an additional admissible signer key for an actor on this session's validation
     * lane (L-50 per-joiner identities: each member's own node key signs for its player;
     * additive with the interim session signer).
     */
    public void registerActorKey(dev.nodera.core.identity.NodeId actor, Bytes publicKey) {
        live.validation().registerActor(actor, publicKey);
    }

    @Override
    public void close() {
        if (!active.compareAndSet(true, false)) {
            return;
        }
        LiveEntityControlProvider.deactivate(live);
        LiveRegionOwnershipProvider.deactivate(live.validation());
        host.runtime().onApplicationMessage(null);
        live.close();
        store.close();
    }

    public record RegionBinding(
            ServerLevel level, RegionSnapshot snapshot, RegionLease lease) {
        public RegionBinding {
            if (level == null || snapshot == null || lease == null
                    || !snapshot.region().equals(lease.region())) {
                throw new IllegalArgumentException("region binding values must agree");
            }
        }
    }

    public record CommitteePeer(PeerAddress address, Bytes publicKey) {
        public CommitteePeer {
            if (address == null || address.nodeId() == null || publicKey == null) {
                throw new IllegalArgumentException("committee peer values must not be null");
            }
        }
    }
}
