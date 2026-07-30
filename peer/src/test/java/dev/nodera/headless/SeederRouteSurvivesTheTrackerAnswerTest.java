package dev.nodera.headless;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.HashService;
import dev.nodera.core.identity.NodeCapabilities;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.core.identity.WorldHealth;
import dev.nodera.peer.discovery.TrackerClient;
import dev.nodera.peer.discovery.TrackerLookup;
import dev.nodera.protocol.discovery.TrackerResponse;
import dev.nodera.protocol.discovery.TrackerRoutesResponse;
import dev.nodera.protocol.membership.PeerEntry;
import dev.nodera.storage.event.InMemoryContentStore;
import dev.nodera.testkit.LoopbackTransport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L-85: a seeder the tracker names must arrive with the route the tracker already gave for it.
 *
 * <p>The live symptom was a fetch failing with {@code no routable seeder for world d454b2264b84}
 * three seconds after the host had announced that world to the same tracker. Every seeder was
 * known and none was dialable. The cause was that seeder resolution kept only the node id out of
 * each {@code PeerEntry} in the tracker's answer and threw away the {@code route} the entry
 * carries, relying instead on a <em>second</em> query (the routes query) to supply it. A tracker
 * that does not answer that second query — an older build, a dropped datagram — therefore produced
 * exactly that state.
 *
 * <p>The register recorded this row as having no headless proof, because the only tracker
 * implementation was a final socket-owning class that a test could not stand in for. That is what
 * the {@link TrackerLookup} seam changed: each case below hands in a tracker that answers one query
 * and not the other, which is the whole shape of the defect.
 *
 * <p>{@link WorldArchiveService#holdersFor} is the public entry point that runs seeder resolution,
 * and {@link WorldArchiveService#routeOf} reports what the resolution learned — "known" and
 * "routable" read separately, because the bug was precisely the two disagreeing.
 *
 * <p>Thread-context: JUnit test; single-threaded.
 */
final class SeederRouteSurvivesTheTrackerAnswerTest {

    private static final NodeCapabilities CAPS = NodeCapabilities.initial();

    private final HashService hashes = new HashService();
    private WorldArchiveService service;
    private LoopbackTransport transport;

    @AfterEach
    void tearDown() {
        if (service != null) {
            service.close();
        }
        if (transport != null) {
            transport.stop();
        }
    }

    /** A tracker that answers whichever of the two queries the case is about. */
    private static final class FakeTracker implements TrackerLookup {

        private final List<PeerEntry> peers;
        private final List<TrackerRoutesResponse.PeerRoutes> routes;
        private final List<String> asked = new ArrayList<>();

        FakeTracker(List<PeerEntry> peers, List<TrackerRoutesResponse.PeerRoutes> routes) {
            this.peers = peers;
            this.routes = routes;
        }

        @Override
        public List<TrackerClient.Endpoint> endpoints() {
            // Non-empty: resolution returns early on "no tracker to ask", and that early return is
            // not the behaviour under test. The endpoint is never dialed — this class is the
            // tracker.
            return List.of(new TrackerClient.Endpoint("tracker.invalid", 1,
                    TrackerClient.Transport.TCP));
        }

        @Override
        public Optional<TrackerResponse> query(Bytes genesisHash) {
            asked.add("query");
            return Optional.of(new TrackerResponse(genesisHash, "world", peers, List.of(),
                    0, 0, TrackerResponse.RELIABILITY_BPS_SCALE, WorldHealth.HEALTHY, 0));
        }

        @Override
        public TrackerRoutesResponse routes(Bytes genesisHash) {
            asked.add("routes");
            return new TrackerRoutesResponse(genesisHash, routes);
        }

        @Override
        public void close() {
        }
    }

    private WorldArchiveService serviceAgainst(FakeTracker tracker) {
        NodeIdentity identity = NodeIdentity.generate();
        transport = LoopbackTransport.LoopbackNetwork.newNetwork().register(identity.nodeId());
        transport.start();
        service = new WorldArchiveService(identity, transport,
                new InMemoryContentStore(hashes), tracker);
        return service;
    }

    @Test
    @DisplayName("L-85: the route on the tracker's own seeder entry is kept, not asked for twice")
    void theEntrysRouteIsKeptWhenTheRoutesQueryAnswersNothing() {
        NodeId seeder = NodeId.random();
        // The exact live shape: the seeder index names the peer AND carries its dial route, and the
        // routes query answers with nothing at all.
        FakeTracker tracker = new FakeTracker(
                List.of(new PeerEntry(seeder, "203.0.113.7:25620", CAPS, false)),
                List.of());
        Bytes worldId = hashes.sha256("l85-world".getBytes());

        var holders = serviceAgainst(tracker).holdersFor(worldId.toHex());

        assertThat(holders).containsExactly(seeder);
        // Known AND routable. Before the fix this assertion was the failing one: the seeder was in
        // the set and had no address, which is the "no routable seeder" state exactly.
        assertThat(service.routeOf(seeder)).isNotNull();
        assertThat(service.routeOf(seeder).route()).isEqualTo("203.0.113.7:25620");
    }

    @Test
    @DisplayName("L-85: the routes query still supplements a seeder entry that carried no route")
    void theRoutesQueryStillSuppliesWhatTheEntryDidNot() {
        NodeId seeder = NodeId.random();
        // A tracker that names the peer with an unusable route, and answers the routes query with
        // the real one. Keeping the first source must not shadow the second.
        FakeTracker tracker = new FakeTracker(
                List.of(new PeerEntry(seeder, "", CAPS, false)),
                List.of(new TrackerRoutesResponse.PeerRoutes(seeder,
                        List.of("198.51.100.4:25620"))));
        Bytes worldId = hashes.sha256("l85-supplement".getBytes());

        var holders = serviceAgainst(tracker).holdersFor(worldId.toHex());

        assertThat(holders).containsExactly(seeder);
        assertThat(service.routeOf(seeder).route()).isEqualTo("198.51.100.4:25620");
        assertThat(tracker.asked).containsExactly("query", "routes");
    }

    @Test
    @DisplayName("L-85: an mc/ claim is not a dial route, from either source")
    void aMinecraftClaimIsRejectedByBothSources() {
        NodeId fromIndex = NodeId.random();
        NodeId fromRoutes = NodeId.random();
        // `mc/...` is a Minecraft game endpoint a host publishes for the multiplayer list. Dialing
        // one as a P2P route connects the content lane to the game port.
        FakeTracker tracker = new FakeTracker(
                List.of(new PeerEntry(fromIndex,
                        WorldHostingService.MC_ROUTE_PREFIX + "203.0.113.9:25565", CAPS, false)),
                List.of(new TrackerRoutesResponse.PeerRoutes(fromRoutes,
                        List.of(WorldHostingService.MC_ROUTE_PREFIX + "203.0.113.9:25565"))));
        Bytes worldId = hashes.sha256("l85-mc-claim".getBytes());

        var holders = serviceAgainst(tracker).holdersFor(worldId.toHex());

        // The seeder index still names its peer as a holder — an unusable route is not a reason to
        // forget the peer exists — but neither peer becomes routable, and the one known only
        // through the routes query is not promoted to a seeder on an unusable route at all.
        assertThat(holders).containsExactly(fromIndex);
        assertThat(service.routeOf(fromIndex)).isNull();
        assertThat(service.routeOf(fromRoutes)).isNull();
    }
}
