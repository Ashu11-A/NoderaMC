package dev.nodera.testkit.peer;

import dev.nodera.core.crypto.HashService;
import dev.nodera.core.identity.NodeCapabilities;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.core.identity.PeerRole;
import dev.nodera.diagnostics.metric.TrafficMeter;
import dev.nodera.headless.WorkerControlHandler;
import dev.nodera.headless.WorldArchiveService;
import dev.nodera.headless.WorldDeletionService;
import dev.nodera.headless.WorldHostingService;
import dev.nodera.headless.WorldKeyStore;
import dev.nodera.headless.WorldRegistryStore;
import dev.nodera.headless.WorldReplicationService;
import dev.nodera.headless.WorldTombstoneStore;
import dev.nodera.peer.PeerRuntime;
import dev.nodera.peer.PeerRuntimeConfig;
import dev.nodera.peer.control.ControlServer;
import dev.nodera.peer.discovery.TrackerClient;
import dev.nodera.peer.validation.WorkerValidationService;
import dev.nodera.protocol.NoderaMessage;
import dev.nodera.protocol.membership.PeerEntry;
import dev.nodera.protocol.wire.WireCodec;
import dev.nodera.simulation.RegionEngine;
import dev.nodera.simulation.engine.FlatWorldRegionEngine;
import dev.nodera.simulation.rules.FlatWorldRules;
import dev.nodera.storage.ContentStore;
import dev.nodera.storage.event.InMemoryCertificateStore;
import dev.nodera.storage.event.InMemoryContentStore;
import dev.nodera.testkit.LoopbackTransport;
import dev.nodera.testkit.LoopbackTransport.LoopbackNetwork;
import dev.nodera.transport.MessageHandler;
import dev.nodera.transport.PeerAddress;
import dev.nodera.transport.PeerTransport;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * The one mesh an integration test stands up.
 *
 * <h2>Why this exists</h2>
 *
 * <p>Twenty-five {@code *IT.java} files each built their own {@link LoopbackNetwork}, their own
 * per-node factory of thirty to eighty lines, and their own teardown. {@code docs/peer/REFACTORING.md}
 * measured the result — {@code SeedRegionVerbIT} 57.2 % duplicated, {@code OwnershipGossipIT} and
 * {@code GrantGossipIT} "mirror each other almost line-for-line" — and named three fixtures that
 * would end it. This is those three, and the register's names map onto it as:
 *
 * <ul>
 *   <li>{@code ControlSocketHarness} → {@link WorkerNode}, which reuses the harness package's
 *       existing {@link dev.nodera.testkit.harness.ControlClient} rather than adding a fourth
 *       implementation of the control wire;</li>
 *   <li>{@code LoopbackMeshHarness} → {@link #messageNode} + {@link MeshNode} + {@link #mesh};</li>
 *   <li>the committee-lane factory the registers do not name, but which nine validation ITs each
 *       wrote out → {@link #validationNode}.</li>
 * </ul>
 *
 * <h2>What it deliberately does not do</h2>
 *
 * <p>It has no defaults for anything two tests disagreed about. The engine, the vote timeout, the
 * world seed, the capability roles and the control timeout are all named parameters, because a
 * shared fixture that quietly settles one of those differences changes what a test means while
 * leaving it green.
 *
 * <h2>Lifecycle</h2>
 *
 * <p>Everything the harness creates is closed by {@link #close}, in reverse order of creation, each
 * failure swallowed so one bad teardown cannot mask the test's own result. Use it from a
 * {@code @AfterEach} or as a try-with-resources.
 *
 * <p>Thread-context: construction and teardown from the test thread; the nodes it returns deliver
 * on their transports' own executors.
 */
public final class PeerTestHarness implements AutoCloseable {

    /** The keep-alive shape nearly every mesh IT runs on: fast enough to observe inside a test. */
    public static final PeerRuntimeConfig FAST =
            new PeerRuntimeConfig(Duration.ofMillis(100), Duration.ofMillis(500));

    private final LoopbackNetwork network = LoopbackNetwork.newNetwork();
    private final HashService hashes = new HashService();
    private final Deque<AutoCloseable> teardown = new ArrayDeque<>();

    private PeerTestHarness() {}

    /** @return a fresh harness owning a fresh loopback network. */
    public static PeerTestHarness create() {
        return new PeerTestHarness();
    }

    /** @return the network every node here is registered on. */
    public LoopbackNetwork network() {
        return network;
    }

    /** @return the harness's shared hash service — one instance, as production has one. */
    public HashService hashes() {
        return hashes;
    }

    /**
     * Register something to close at teardown.
     *
     * <p>Closed in reverse registration order, so a service registered after the transport it sits
     * on is shut down before it.
     *
     * @param closeable what to close.
     */
    public void onClose(AutoCloseable closeable) {
        teardown.push(closeable);
    }

    /**
     * Register and start a transport for {@code self}.
     *
     * @param self the node id to register.
     * @return the started transport.
     */
    public LoopbackTransport transport(NodeId self) {
        LoopbackTransport transport = network.register(self);
        transport.start();
        onClose(transport::stop);
        return transport;
    }

    /** As {@link #transport(NodeId)}, for an identity. */
    public LoopbackTransport transport(NodeIdentity self) {
        return transport(self.nodeId());
    }

    // -------------------------------------------------------------------------------------------
    // The committee lane
    // -------------------------------------------------------------------------------------------

    /** @return a builder for a committee member with a freshly generated identity. */
    public ValidationNodeBuilder validationNode() {
        return validationNode(NodeIdentity.generate());
    }

    /**
     * @param identity the member's identity — named when a test needs the same node twice, as the
     *                 committee-collapse case does when its validators come back.
     * @return a builder for a committee member.
     */
    public ValidationNodeBuilder validationNode(NodeIdentity identity) {
        return new ValidationNodeBuilder(identity);
    }

    /** Builds a {@link ValidationNode}. Every option is one a real suite disagreed about. */
    public final class ValidationNodeBuilder {

        private final NodeIdentity identity;
        private NodeCapabilities capabilities = NodeCapabilities.initial();
        private RegionEngine engine;
        private long worldSeed = RegionFixtures.WORLD_SEED;
        private long voteTimeoutMillis = 5_000L;
        private boolean withRuntime = true;
        private MeshNode.MessageSink interceptor;

        private ValidationNodeBuilder(NodeIdentity identity) {
            this.identity = identity;
        }

        /** What this member announces it can do. */
        public ValidationNodeBuilder capabilities(NodeCapabilities value) {
            this.capabilities = value;
            return this;
        }

        /**
         * The engine this member re-executes with.
         *
         * <p>Named because it is the whole point of one suite: a member whose engine reports a root
         * it did not compute is how a divergence is manufactured.
         *
         * @param value the engine; defaults to the honest flat-world one.
         */
        public ValidationNodeBuilder engine(RegionEngine value) {
            this.engine = value;
            return this;
        }

        /** The world seed the engine executes against. Defaults to {@link RegionFixtures#WORLD_SEED}. */
        public ValidationNodeBuilder worldSeed(long value) {
            this.worldSeed = value;
            return this;
        }

        /**
         * How long a proposal waits for its quorum.
         *
         * <p>This is the parameter a careless consolidation flattens. Five seconds is the default
         * because most suites need a round to survive a slow CI runner; the committee-collapse
         * suite needs 700 ms so that a proposal which <i>cannot</i> reach quorum resolves inside
         * the test rather than after it, and the Byzantine suite needs 2 s for the same reason.
         *
         * @param value the vote timeout.
         */
        public ValidationNodeBuilder voteTimeoutMillis(long value) {
            this.voteTimeoutMillis = value;
            return this;
        }

        /**
         * Build without a {@link PeerRuntime}, wiring the service straight onto the transport.
         *
         * <p>The halo lane does this: it is testing what a commit emits, not what a session
         * negotiates, and a runtime in the way only adds keep-alive traffic to the frames it counts.
         */
        public ValidationNodeBuilder withoutRuntime() {
            this.withRuntime = false;
            return this;
        }

        /**
         * Observe every decoded inbound message before the service sees it.
         *
         * @param sink called with each message; the service is still invoked afterwards.
         */
        public ValidationNodeBuilder observing(MeshNode.MessageSink sink) {
            this.interceptor = sink;
            return this;
        }

        /** @return the built, started node. */
        public ValidationNode build() {
            LoopbackTransport transport = network.register(identity.nodeId());
            InMemoryCertificateStore certificates = new InMemoryCertificateStore(hashes);
            RegionEngine regionEngine = engine != null ? engine : honestEngine();
            WorkerValidationService service = new WorkerValidationService(identity, transport,
                    regionEngine, hashes, certificates, worldSeed,
                    FlatWorldRules.RULES_VERSION, FlatWorldRules.registryFingerprint(),
                    voteTimeoutMillis);

            PeerRuntime runtime = null;
            if (withRuntime) {
                transport.start();
                onClose(transport::stop);
                runtime = PeerRuntime.bootstrap(identity, capabilities, transport,
                        () -> MeshNode.ROUTE, FAST, null);
                onClose(runtime::stop);
                MeshNode.MessageSink sink = interceptor;
                runtime.onApplicationMessage(sink == null
                        ? service::onMessage
                        : (from, message) -> {
                            sink.accept(from, message);
                            service.onMessage(from, message);
                        });
            } else {
                transport.setHandler(decoding(interceptor, service::onMessage));
                transport.start();
                onClose(transport::stop);
            }
            return new ValidationNode(identity, transport, runtime, service, certificates);
        }
    }

    /** @return a fresh honest flat-world engine over this harness's hashes. */
    public FlatWorldRegionEngine honestEngine() {
        return new FlatWorldRegionEngine(FlatWorldRules.RULES_VERSION,
                FlatWorldRules.registryFingerprint(), hashes);
    }

    // -------------------------------------------------------------------------------------------
    // The gossip lane
    // -------------------------------------------------------------------------------------------

    /**
     * A node carrying one service that speaks the wire, with its membership list wired in.
     *
     * @param factory  builds the service over the node's identity, transport and membership view.
     * @param dispatch where the node's decoded inbound messages go.
     * @param <S>      the service type.
     * @return the built, started node.
     */
    public <S> MeshNode<S> messageNode(MeshNode.ServiceFactory<S> factory,
                                       Function<S, MeshNode.MessageSink> dispatch) {
        return messageNode(NodeIdentity.generate(), factory, dispatch);
    }

    /** As {@link #messageNode(MeshNode.ServiceFactory, Function)}, with a named identity. */
    public <S> MeshNode<S> messageNode(NodeIdentity identity, MeshNode.ServiceFactory<S> factory,
                                       Function<S, MeshNode.MessageSink> dispatch) {
        LoopbackTransport transport = network.register(identity.nodeId());
        MeshNode<S> node = new MeshNode<>(identity, transport);
        S service = factory.create(identity, transport, node::peers);
        node.bind(service);
        transport.setHandler(decoding(null, dispatch.apply(service)));
        transport.start();
        onClose(transport::stop);
        return node;
    }

    /**
     * Put every node in {@code nodes} into every other's membership list.
     *
     * @param nodes the mesh.
     */
    public void mesh(List<? extends MeshNode<?>> nodes) {
        for (MeshNode<?> node : nodes) {
            for (MeshNode<?> other : nodes) {
                if (node != other) {
                    node.peers().add(other.entry());
                }
            }
        }
    }

    /**
     * A handler that decodes a frame and hands it on, dropping anything it cannot read.
     *
     * <p>Dropping is right rather than lax: these meshes carry keep-alives and membership frames a
     * gossip service has no opinion about, and a decode failure on one of those is not this
     * service's business.
     */
    private static MessageHandler decoding(MeshNode.MessageSink observer,
                                           MeshNode.MessageSink target) {
        return new MessageHandler() {
            @Override
            public void onMessage(PeerAddress from, byte[] frame) {
                NoderaMessage message;
                try {
                    message = WireCodec.decode(frame);
                } catch (RuntimeException notOurs) {
                    return;
                }
                if (observer != null) {
                    observer.accept(from, message);
                }
                target.accept(from, message);
            }

            @Override
            public void onPeerDown(PeerAddress peer) {
                // A gossip lane has nothing to unwind when a peer goes away.
            }
        };
    }

    // -------------------------------------------------------------------------------------------
    // The control lane
    // -------------------------------------------------------------------------------------------

    /**
     * @param label the version string the worker reports — what a state document is identified by.
     * @return a builder for an always-on worker behind a real control endpoint.
     */
    public WorkerNodeBuilder workerNode(String label) {
        return new WorkerNodeBuilder(label);
    }

    /**
     * Builds a {@link WorkerNode}.
     *
     * <p>Every lane is opt-in. A worker built with none of them is what four of the verb ITs need:
     * hosting registry, control endpoint, nothing else.
     */
    public final class WorkerNodeBuilder {

        private final String label;
        private NodeIdentity identity = NodeIdentity.generate();
        private EnumSet<PeerRole> roles = EnumSet.of(PeerRole.FULL_ARCHIVE);
        /**
         * The keep-alive shape. Not a builder option: no control-lane suite has ever wanted
         * anything but the production default here — {@link PeerTestHarness#FAST} belongs to the mesh
         * lanes, whose assertions are about frames arriving — so there is nothing for a setter to
         * disagree about yet. Add one back with the suite that needs it, not before.
         */
        private final PeerRuntimeConfig runtimeConfig = PeerRuntimeConfig.defaults();
        private Duration controlTimeout = Duration.ofSeconds(30);
        private Path stateDir;
        private boolean sharedTracker;
        private ContentStore contentStore;
        private PeerTransport contentTransport;
        private boolean replicate;
        private boolean configSeams;
        private java.util.function.Supplier<List<PeerEntry>> deletionMembers;

        /** Set during {@link #build()}, so the handler factory can read what it created. */
        private TrackerClient sharedTrackerClient;

        private WorkerNodeBuilder(String label) {
            this.label = label;
        }

        /** The worker's identity, when a test needs to name it. */
        public WorkerNodeBuilder identity(NodeIdentity value) {
            this.identity = value;
            return this;
        }

        /** The roles the worker announces. Defaults to {@link PeerRole#FULL_ARCHIVE} alone. */
        public WorkerNodeBuilder roles(PeerRole first, PeerRole... rest) {
            this.roles = EnumSet.of(first, rest);
            return this;
        }

        /**
         * How long a control request waits for its reply.
         *
         * <p>Named because the re-key verb derives an Argon2id key on the request thread and needs
         * a minute; every other verb answers immediately and a minute there would only lengthen a
         * failure.
         */
        public WorkerNodeBuilder controlTimeout(Duration value) {
            this.controlTimeout = value;
            return this;
        }

        /**
         * Give the worker durable local state: a {@link WorldKeyStore} and a
         * {@link WorldRegistryStore} beneath {@code dir}, and the shared tracker they announce
         * through. This is what makes a worker able to administer a world rather than only serve one.
         *
         * @param dir the worker's state directory.
         */
        public WorkerNodeBuilder stateDir(Path dir) {
            this.stateDir = dir;
            this.sharedTracker = true;
            return this;
        }

        /** Give the worker one shared {@link TrackerClient} across all its lanes. */
        public WorkerNodeBuilder sharedTracker() {
            this.sharedTracker = true;
            return this;
        }

        /** Give the worker an archive lane over an in-memory content store. */
        public WorkerNodeBuilder inMemoryArchive() {
            return archive(new InMemoryContentStore(hashes));
        }

        /**
         * Give the worker an archive lane over {@code store}.
         *
         * @param store where the archive's blobs live — on disk when the test moves them.
         */
        public WorkerNodeBuilder archive(ContentStore store) {
            this.contentStore = store;
            return this;
        }

        /**
         * Serve archive content over {@code transport} rather than the mesh one.
         *
         * <p>The configuration suite watches what a paused node <i>tried to send</i>, which is an
         * observation a recording transport makes and a socket does not.
         */
        public WorkerNodeBuilder contentTransport(PeerTransport transport) {
            this.contentTransport = transport;
            return this;
        }

        /** Give the worker a replication lane at its default budget and sweep. */
        public WorkerNodeBuilder withReplication() {
            this.replicate = true;
            return this;
        }

        /**
         * Wire the {@code NODERA-CONFIG} seams — the live objects a configuration push re-bounds.
         *
         * <p>Requires {@link #withReplication()} and an archive — checked in {@link #build()}, which
         * says which call is missing. Without them the verb answers the keys behind the absent seam
         * {@code rejected}, which is the handler being honest and a test asserting {@code applied}
         * failing for a reason that is not the product's.
         */
        public WorkerNodeBuilder withConfigSeams() {
            this.configSeams = true;
            return this;
        }

        /**
         * Give the worker a deletion lane relaying to {@code members}.
         *
         * @param members the peers a tombstone is relayed to.
         */
        public WorkerNodeBuilder withDeletion(java.util.function.Supplier<List<PeerEntry>> members) {
            this.deletionMembers = members;
            return this;
        }

        /**
         * @return the built, started worker with its control endpoint bound on an ephemeral port.
         * @throws IOException if the control endpoint could not bind.
         */
        public WorkerNode build() throws IOException {
            requireStatedPreconditions();
            NodeCapabilities capabilities = NodeCapabilities.initial().withRoles(roles);
            LoopbackTransport transport = network.register(identity.nodeId());
            transport.start();
            onClose(transport::stop);
            PeerRuntime runtime = PeerRuntime.bootstrap(identity, capabilities, transport,
                    () -> MeshNode.ROUTE, runtimeConfig, null);
            onClose(runtime::stop);

            TrackerClient tracker = sharedTracker ? new TrackerClient(List.of(), identity) : null;
            sharedTrackerClient = tracker;
            if (tracker != null) {
                onClose(tracker::close);
            }

            WorldKeyStore keys = stateDir == null
                    ? null : new WorldKeyStore(stateDir.resolve("world-keys"));
            WorldRegistryStore registry = stateDir == null
                    ? null : new WorldRegistryStore(stateDir.resolve("worlds.dat"));

            WorldArchiveService archive = null;
            if (contentStore != null) {
                PeerTransport carrier = contentTransport != null ? contentTransport : transport;
                archive = tracker != null
                        ? new WorldArchiveService(identity, carrier, contentStore, tracker)
                        : new WorldArchiveService(identity, carrier, contentStore, List.of());
                onClose(archive::close);
            }

            WorldHostingService hosting = hosting(capabilities, runtime, tracker, archive, registry,
                    keys);
            onClose(hosting::close);

            WorldReplicationService replication = null;
            if (replicate) {
                replication = new WorldReplicationService(identity.nodeId(), tracker, archive,
                        hosting, WorldReplicationService.DEFAULT_BUDGET_BYTES, 300);
                onClose(replication::close);
            }

            WorkerControlHandler handler = handler(capabilities, runtime, hosting, archive,
                    replication, keys);

            WorldDeletionService deletions = null;
            if (deletionMembers != null) {
                deletions = new WorldDeletionService(identity.nodeId(), transport, deletionMembers,
                        hosting, registry, null, null, null);
                deletions.attachStore(new WorldTombstoneStore(stateDir.resolve("deleted")));
                hosting.refuseDeletedWorlds(deletions::isDeleted);
                runtime.onApplicationMessage(deletions::onMessage);
                handler.attachDeletion(deletions);
            }

            ControlServer control = new ControlServer("127.0.0.1", 0, handler);
            control.start();
            onClose(control::close);
            return new WorkerNode(identity, capabilities, transport, runtime, tracker, archive,
                    hosting, replication, deletions, keys, registry, handler, control,
                    controlTimeout);
        }

        /**
         * The options whose own javadoc states a requirement, checked before anything is built.
         *
         * <p>Each requirement was stated and enforced nowhere, so failing it named the harness's
         * internals instead of the missing call: {@code withDeletion} dereferenced a null
         * {@code stateDir} building the tombstone store, {@code withConfigSeams} dereferenced a null
         * archive reaching for its content lane, and a config plane without a replication lane came
         * up perfectly happy and then answered every replication key {@code rejected} — a
         * disagreement between two builder calls, reported as a product verdict.
         */
        private void requireStatedPreconditions() {
            if (deletionMembers != null) {
                Objects.requireNonNull(stateDir,
                        "withDeletion(...) needs stateDir(...): the tombstone store lives under it");
            }
            if (configSeams) {
                Objects.requireNonNull(contentStore, "withConfigSeams() needs archive(...) or "
                        + "inMemoryArchive(): the content lane is one of the seams it re-bounds");
                if (!replicate) {
                    throw new IllegalStateException("withConfigSeams() needs withReplication(): "
                            + "without it every replication key answers rejected, not applied");
                }
            }
        }

        /**
         * The control handler, built through the constructor that matches this worker's SHAPE.
         *
         * <p>{@link WorkerControlHandler} publishes one overload per embedding — no config plane,
         * a config plane, a config plane plus a key store — and each declines the verbs it has no
         * seam for rather than reporting a success it did not perform. Passing {@code null} through
         * the widest overload for every worker produces the same object, so it looks equivalent;
         * it is not. It makes the narrower overloads unreachable, and the structural report caught
         * exactly that: collapsing every worker here onto the thirteen-argument constructor left
         * the eleven-argument one — whose only caller in the tree was the configuration suite —
         * referenced by nothing at all.
         *
         * <p>So the shape decides, which is also what each suite did before it moved here.
         *
         * <p><b>The shape is the union of the seams, not the first one matched.</b> The ladder used
         * to test {@code keys} first and then pass {@code null} into the config slot of the
         * thirteen-argument constructor, so {@code stateDir(dir).withReplication().withConfigSeams()}
         * built a worker that answered {@code NODERA-CONFIG} with {@code NODERA-ERR unsupported} —
         * the harness silently dropping one of the two lanes the test asked for. No suite combined
         * them, so nothing caught it; a suite that did would have read the failure as the product's.
         * The seams are therefore built once, up front, and the ladder only chooses which
         * constructor is wide enough to carry the ones that are present. Every shape the builder can
         * express is carried by some constructor, so there is no combination left to reject.
         */
        private WorkerControlHandler handler(NodeCapabilities capabilities, PeerRuntime runtime,
                                             WorldHostingService hosting,
                                             WorldArchiveService archive,
                                             WorldReplicationService replication,
                                             WorldKeyStore keys) {
            TrafficMeter meter = new TrafficMeter();
            WorkerControlHandler.ConfigSeams config = configSeams
                    ? new WorkerControlHandler.ConfigSeams(archive.content(), replication, null,
                            tracker(), diskStore())
                    : null;
            if (keys != null) {
                return new WorkerControlHandler(label, identity, capabilities, runtime, meter,
                        hosting, null, archive, null, null, config, null, keys);
            }
            if (config != null) {
                return new WorkerControlHandler(label, identity, capabilities, runtime, meter,
                        hosting, null, archive, null, null, config);
            }
            return new WorkerControlHandler(label, identity, capabilities, runtime, meter, hosting,
                    null, archive);
        }

        /** The shared tracker this worker announces through, or {@code null} if it has none. */
        private TrackerClient tracker() {
            return sharedTrackerClient;
        }

        /**
         * The archive directory a configuration push may relocate, or {@code null} when this
         * worker's content lives in memory and cannot be moved.
         */
        private dev.nodera.storage.fs.FsContentStore diskStore() {
            return contentStore instanceof dev.nodera.storage.fs.FsContentStore fs ? fs : null;
        }

        private WorldHostingService hosting(NodeCapabilities capabilities, PeerRuntime runtime,
                                            TrackerClient tracker, WorldArchiveService archive,
                                            WorldRegistryStore registry, WorldKeyStore keys) {
            java.util.function.Function<String,
                    List<dev.nodera.protocol.content.ManifestHolding>> holdings =
                    archive != null ? archive::holdingsFor : worldId -> List.of();
            if (tracker == null) {
                return new WorldHostingService(identity, capabilities, runtime::selfRoute,
                        List.of(), List.of(), holdings);
            }
            return new WorldHostingService(identity, capabilities, runtime::selfRoute, tracker,
                    List.of(), holdings, registry, keys);
        }
    }

    /**
     * Close everything this harness created, newest first, swallowing failures.
     *
     * <p>Best-effort on purpose: a teardown that threw would replace the test's own failure with
     * its own, which is how a real defect gets reported as a closed socket.
     */
    @Override
    public void close() {
        List<AutoCloseable> ordered = new ArrayList<>(teardown);
        teardown.clear();
        for (AutoCloseable closeable : ordered) {
            try {
                closeable.close();
            } catch (Exception ignored) {
                // teardown is best-effort
            }
        }
    }
}
