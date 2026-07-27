package dev.nodera.headless;

import dev.nodera.core.identity.NodeCapabilities;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.peer.control.WorkerEvent;
import dev.nodera.peer.control.WorkerEventBus;
import dev.nodera.peer.tunnel.LanBeacon;
import dev.nodera.peer.tunnel.TunnelService;
import dev.nodera.testkit.LoopbackTransport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>Detection is not consent.</b>
 *
 * <p>The worker can see every world opened to LAN on this machine, whether or not the player has
 * anything to do with Nodera. What it does with that is the subject here, and the rule is one line:
 * nothing reaches the network until somebody says yes. These tests drive the decision machine
 * directly — the multicast listener has its own suite — because the rules are the part that can be
 * wrong in a way nobody notices until a world is on the internet that should not be.
 */
final class LanSessionServiceTest {

    private final NodeIdentity identity = NodeIdentity.generate();
    private LoopbackTransport transport;
    private TunnelService tunnel;
    private WorldHostingService hosting;
    private LanSessionService lan;
    private WorkerEventBus events;

    private LanSessionService service() {
        transport = LoopbackTransport.LoopbackNetwork.newNetwork().register(identity.nodeId());
        transport.start();
        tunnel = new TunnelService(identity.nodeId(), transport);
        // No discovery endpoints: the announce paths are guarded on an empty list, so this exercises
        // pure hosting bookkeeping and never touches the network.
        hosting = new WorldHostingService(identity, NodeCapabilities.initial(),
                () -> "127.0.0.1:25620", List.of(), List.of());
        events = new WorkerEventBus();
        lan = new LanSessionService(identity.nodeId(), tunnel, hosting, null, events);
        return lan;
    }

    @AfterEach
    void tearDown() {
        if (lan != null) {
            lan.close();
        }
        if (hosting != null) {
            hosting.close();
        }
        if (tunnel != null) {
            tunnel.close();
        }
        if (transport != null) {
            transport.stop();
        }
    }

    private static LanBeacon beacon(String name, int port) {
        return new LanBeacon(name, port);
    }

    @Test
    @DisplayName("a detected world is offered, and nothing is on the network yet")
    void detectionAloneAnnouncesNothing() {
        LanSessionService lan = service();

        lan.onOpened(beacon("My World", 54321));

        LanSessionService.Session session = lan.session(54321).orElseThrow();
        assertThat(session.state()).isEqualTo(LanSessionService.State.OFFERED);
        assertThat(session.name()).isEqualTo("My World");
        // The two things that would mean it IS on the network. Neither has happened.
        assertThat(tunnel.publishedPort(session.sessionIdHex())).isEmpty();
        assertThat(hosting.hostedWorlds()).isEmpty();
    }

    @Test
    @DisplayName("saying yes publishes the tunnel and announces the world")
    void sharingPutsItOnTheNetwork() {
        LanSessionService lan = service();
        lan.onOpened(beacon("My World", 54321));

        assertThat(lan.share(54321)).isNull();

        LanSessionService.Session session = lan.session(54321).orElseThrow();
        assertThat(session.state()).isEqualTo(LanSessionService.State.SHARED);
        assertThat(tunnel.publishedPort(session.sessionIdHex())).contains(54321);
        assertThat(hosting.hostedWorlds())
                .extracting(WorldHostingService.HostedWorld::name)
                .containsExactly("My World");
        // The joinable signal a browser reads: a live game endpoint, and no content behind it.
        assertThat(hosting.hostedWorlds().iterator().next().mcRoute()).isEqualTo("127.0.0.1:54321");
    }

    @Test
    @DisplayName("saying no keeps the world listed so it can be shared later")
    void decliningIsNotForgetting() {
        LanSessionService lan = service();
        lan.onOpened(beacon("My World", 54321));

        assertThat(lan.decline(54321)).isNull();

        // "No" answers the prompt, not the question. A declined world that vanished from the UI
        // would make "open it later" impossible without closing and reopening the world.
        LanSessionService.Session session = lan.session(54321).orElseThrow();
        assertThat(session.state()).isEqualTo(LanSessionService.State.DECLINED);
        assertThat(hosting.hostedWorlds()).isEmpty();

        assertThat(lan.share(54321)).isNull();
        assertThat(lan.session(54321).orElseThrow().state())
                .isEqualTo(LanSessionService.State.SHARED);
    }

    @Test
    @DisplayName("declining a world already on the network takes it off")
    void decliningAfterSharingWithdrawsIt() {
        LanSessionService lan = service();
        lan.onOpened(beacon("My World", 54321));
        lan.share(54321);
        String id = lan.session(54321).orElseThrow().sessionIdHex();

        assertThat(lan.decline(54321)).isNull();

        assertThat(tunnel.publishedPort(id)).isEmpty();
        assertThat(hosting.hostedWorlds()).isEmpty();
    }

    @Test
    @DisplayName("stopping returns a world to offered rather than declined")
    void stoppingIsNotDeclining() {
        LanSessionService lan = service();
        lan.onOpened(beacon("My World", 54321));
        lan.share(54321);

        assertThat(lan.stop(54321)).isNull();

        // Two different intentions: "not right now" leaves the offer standing, "no" records an
        // answer. Collapsing them would make the UI unable to tell what to show.
        assertThat(lan.session(54321).orElseThrow().state())
                .isEqualTo(LanSessionService.State.OFFERED);
        assertThat(hosting.hostedWorlds()).isEmpty();
    }

    @Test
    @DisplayName("closing the world withdraws it, whatever was decided")
    void closingTheWorldWithdrawsEverything() {
        LanSessionService lan = service();
        lan.onOpened(beacon("My World", 54321));
        lan.share(54321);
        String id = lan.session(54321).orElseThrow().sessionIdHex();

        lan.onClosed(beacon("My World", 54321));

        // A session whose game has gone is not joinable however it was announced. Leaving it listed
        // would send every browser at a socket that is not there.
        assertThat(lan.session(54321)).isEmpty();
        assertThat(tunnel.publishedPort(id)).isEmpty();
        assertThat(hosting.hostedWorlds()).isEmpty();
    }

    @Test
    @DisplayName("re-opening a world on a new port is a new session")
    void thePortIsTheIdentity() {
        LanSessionService lan = service();
        lan.onOpened(beacon("My World", 54321));
        String first = lan.session(54321).orElseThrow().sessionIdHex();
        lan.onClosed(beacon("My World", 54321));

        lan.onOpened(beacon("My World", 54322));
        String second = lan.session(54322).orElseThrow().sessionIdHex();

        // Minecraft picks a fresh port each time. Reusing the id would advertise a session that
        // resolves to a socket nobody is listening on.
        assertThat(second).isNotEqualTo(first);
        assertThat(lan.session(54321)).isEmpty();
    }

    @Test
    @DisplayName("the same world, still open, is not re-offered on every beacon")
    void repeatedBeaconsDoNotResetTheDecision() {
        LanSessionService lan = service();
        lan.onOpened(beacon("My World", 54321));
        lan.share(54321);

        // Vanilla announces every ~1.5 s. If each beacon reset the state, the modal would reappear
        // forever and a shared world would be un-shared by its own advertisement.
        lan.onOpened(beacon("My World", 54321));
        lan.onOpened(beacon("My World", 54321));

        assertThat(lan.session(54321).orElseThrow().state())
                .isEqualTo(LanSessionService.State.SHARED);
        assertThat(lan.sessions()).hasSize(1);
    }

    @Test
    @DisplayName("acting on a world that is not open says so rather than pretending")
    void actingOnNothingIsAnError() {
        LanSessionService lan = service();

        assertThat(lan.share(1234)).contains("no world is open to LAN");
        assertThat(lan.decline(1234)).contains("no world is open to LAN");
        assertThat(lan.stop(1234)).contains("no world is open to LAN");
    }

    @Test
    @DisplayName("saying yes twice is not an error")
    void sharingIsIdempotent() {
        LanSessionService lan = service();
        lan.onOpened(beacon("My World", 54321));

        assertThat(lan.share(54321)).isNull();
        assertThat(lan.share(54321)).isNull();
        assertThat(hosting.hostedWorlds()).hasSize(1);
    }

    @Test
    @DisplayName("a session id is stable for the same world and unique per node")
    void sessionIdsAreDerivedNotRandom() {
        LanSessionService lan = service();
        lan.onOpened(beacon("My World", 54321));
        String id = lan.session(54321).orElseThrow().sessionIdHex();

        assertThat(lan.byId(id)).isPresent();
        assertThat(lan.byId(id.toUpperCase(java.util.Locale.ROOT))).isPresent();
        assertThat(lan.byId("deadbeef")).isEmpty();
        assertThat(id).hasSize(64);
    }

    /** Drain everything the bus has, so a test can assert on the sequence of announcements. */
    private java.util.List<String> announced() {
        java.util.List<String> names = new java.util.ArrayList<>();
        try (WorkerEventBus.Subscription subscription = events.subscribe(0)) {
            WorkerEventBus.Published published;
            while ((published = subscription.next(50, java.util.concurrent.TimeUnit.MILLISECONDS))
                    != null) {
                names.add(published.event().name());
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        return names;
    }

    @Test
    @DisplayName("detecting a world is ANNOUNCED, which is what raises the app's prompt")
    void detectionIsAnnounced() {
        LanSessionService lan = service();

        lan.onOpened(beacon("My World", 54321));

        // The whole reason this event exists: a client that had to notice by diffing state snapshots
        // would need to have been connected at the instant the player pressed the button.
        assertThat(announced()).containsExactly(WorkerEvent.LAN_OPENED);
    }

    @Test
    @DisplayName("a repeated beacon is not a repeated announcement")
    void onlyTheFirstBeaconAnnounces() {
        LanSessionService lan = service();

        lan.onOpened(beacon("My World", 54321));
        lan.onOpened(beacon("My World", 54321));
        lan.onOpened(beacon("My World", 54321));

        // Vanilla re-broadcasts every ~1.5 s. An event per beacon would be a modal that reopens
        // itself forever.
        assertThat(announced()).containsExactly(WorkerEvent.LAN_OPENED);
    }

    @Test
    @DisplayName("every decision is announced, so a second window follows along")
    void decisionsAreAnnounced() {
        LanSessionService lan = service();
        lan.onOpened(beacon("My World", 54321));
        lan.share(54321);
        lan.stop(54321);
        lan.decline(54321);
        lan.onClosed(beacon("My World", 54321));

        assertThat(announced()).containsExactly(
                WorkerEvent.LAN_OPENED,
                WorkerEvent.LAN_SHARED,
                WorkerEvent.LAN_STOPPED,
                WorkerEvent.LAN_DECLINED,
                WorkerEvent.LAN_CLOSED);
    }

    @Test
    @DisplayName("the announcement carries what a prompt needs to render")
    void theEventIsSelfContained() throws Exception {
        LanSessionService lan = service();
        lan.onOpened(beacon("Bob's World", 54321));

        try (WorkerEventBus.Subscription subscription = events.subscribe(0)) {
            WorkerEvent event = subscription
                    .next(1, java.util.concurrent.TimeUnit.SECONDS).event();
            // A prompt that had to make a round trip before it could show a name would appear blank
            // for a beat, on a dialog the user is about to answer.
            assertThat(event.attribute("world")).isEqualTo("Bob's World");
            assertThat(event.attribute("port")).isEqualTo("54321");
            assertThat(event.attribute("session")).hasSize(64);
            assertThat(event.attribute("state")).isEqualTo("offered");
        }
    }

    @Test
    @DisplayName("a service with nobody listening still works")
    void theBusIsOptional() {
        transport = LoopbackTransport.LoopbackNetwork.newNetwork().register(identity.nodeId());
        transport.start();
        tunnel = new TunnelService(identity.nodeId(), transport);
        hosting = new WorldHostingService(identity, NodeCapabilities.initial(),
                () -> "127.0.0.1:25620", List.of(), List.of());
        lan = new LanSessionService(identity.nodeId(), tunnel, hosting, null, null);

        lan.onOpened(beacon("My World", 54321));

        assertThat(lan.session(54321)).isPresent();
        assertThat(lan.share(54321)).isNull();
    }

    @Test
    @DisplayName("shutting down takes every shared world off the network")
    void closingTheServiceWithdrawsSharedWorlds() {
        LanSessionService lan = service();
        lan.onOpened(beacon("A", 1111));
        lan.onOpened(beacon("B", 2222));
        lan.share(1111);
        lan.share(2222);

        lan.close();
        this.lan = null; // already closed; keep the teardown from doing it twice

        assertThat(hosting.hostedWorlds()).isEmpty();
        assertThat(tunnel.publishedPort("anything")).isEmpty();
    }
}
