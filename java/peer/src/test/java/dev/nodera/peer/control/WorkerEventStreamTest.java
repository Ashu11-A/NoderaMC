package dev.nodera.peer.control;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The worker announcing what happened, and a client that was not there catching up.
 *
 * <p>The property this suite exists for is the second half. A companion app is started by a person,
 * and the event it most needs — a world being opened to LAN — is caused by the same person, seconds
 * away, in whichever order they feel like. A bus without replay works only when the app connected
 * first, which is a coin toss, and the failure is silent: nothing on screen and nothing wrong.
 */
final class WorkerEventStreamTest {

    private ControlServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.close();
        }
    }

    private static ControlHandler handlerFor(WorkerEventBus bus) {
        return new ControlHandler() {
            @Override
            public String workerVersion() {
                return "event-test";
            }

            @Override
            public WorkerEventBus events() {
                return bus;
            }
        };
    }

    private Socket stream(String request) throws Exception {
        Socket s = new Socket();
        s.connect(new InetSocketAddress("127.0.0.1", server.boundPort()), 2000);
        s.setSoTimeout(5000);
        s.getOutputStream().write((request + "\n").getBytes(StandardCharsets.UTF_8));
        s.getOutputStream().flush();
        return s;
    }

    private static BufferedReader reader(Socket s) throws Exception {
        return new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
    }

    private static WorkerEvent lanOpened(int port) {
        return WorkerEvent.named(WorkerEvent.LAN_OPENED)
                .with("session", "abc")
                .with("world", "My World")
                .with("port", port)
                .build();
    }

    @Test
    @DisplayName("an event published while a client is listening reaches it unasked")
    void liveEventsArePushed() throws Exception {
        WorkerEventBus bus = new WorkerEventBus();
        server = new ControlServer("127.0.0.1", 0, handlerFor(bus));
        server.start();

        try (Socket s = stream(ControlProtocol.EVENTS + " 2 0")) {
            BufferedReader in = reader(s);
            bus.publish(lanOpened(54321));

            String line = in.readLine();
            assertThat(line).contains("\"event\":\"lan.opened\"");
            // The prompt has to render without a follow-up query, so everything it needs rides here.
            assertThat(line).contains("\"port\":\"54321\"").contains("\"world\":\"My World\"");
            assertThat(line).contains("\"seq\":1");
        }
    }

    @Test
    @DisplayName("a client that connects afterwards still learns what it missed")
    void thePastIsReplayed() throws Exception {
        WorkerEventBus bus = new WorkerEventBus();
        server = new ControlServer("127.0.0.1", 0, handlerFor(bus));
        server.start();

        // The player opened the world, and only then opened the app. This is the ordering that a
        // memoryless event bus silently loses.
        bus.publish(lanOpened(1111));
        bus.publish(lanOpened(2222));

        try (Socket s = stream(ControlProtocol.EVENTS + " 2 0")) {
            BufferedReader in = reader(s);
            assertThat(in.readLine()).contains("\"port\":\"1111\"");
            assertThat(in.readLine()).contains("\"port\":\"2222\"");
        }
    }

    @Test
    @DisplayName("a reconnecting client resumes rather than re-reading its own history")
    void resumingFromASequenceSkipsWhatWasSeen() throws Exception {
        WorkerEventBus bus = new WorkerEventBus();
        server = new ControlServer("127.0.0.1", 0, handlerFor(bus));
        server.start();
        bus.publish(lanOpened(1111));
        bus.publish(lanOpened(2222));

        try (Socket s = stream(ControlProtocol.EVENTS + " 2 1")) {
            BufferedReader in = reader(s);
            // Without this, a dropped socket would reopen a prompt the user already answered.
            assertThat(in.readLine()).contains("\"port\":\"2222\"").contains("\"seq\":2");
        }
    }

    @Test
    @DisplayName("two clients both get everything, and neither blocks the other")
    void eventsFanOut() throws Exception {
        WorkerEventBus bus = new WorkerEventBus();
        server = new ControlServer("127.0.0.1", 0, handlerFor(bus));
        server.start();

        try (Socket first = stream(ControlProtocol.EVENTS + " 2 0");
             Socket second = stream(ControlProtocol.EVENTS + " 2 0")) {
            BufferedReader a = reader(first);
            BufferedReader b = reader(second);
            // A two-player dev stack runs two apps against two workers, but a single node with two
            // windows open on it is an ordinary thing too.
            bus.publish(lanOpened(4242));

            assertThat(a.readLine()).contains("\"port\":\"4242\"");
            assertThat(b.readLine()).contains("\"port\":\"4242\"");
            assertThat(bus.subscriberCount()).isEqualTo(2);
        }
    }

    @Test
    @DisplayName("a worker that announces nothing refuses, rather than holding a silent socket")
    void aWorkerWithNoBusDeclines() throws Exception {
        server = new ControlServer("127.0.0.1", 0, new ControlHandler() {
            @Override
            public String workerVersion() {
                return "no-events";
            }
        });
        server.start();

        try (Socket s = stream(ControlProtocol.EVENTS + " 2 0")) {
            // Refusing lets a client fall back to watching state. A silent open socket would look
            // exactly like a node where nothing ever happens.
            assertThat(reader(s).readLine()).startsWith(ControlProtocol.ERR);
        }
    }

    @Test
    @DisplayName("the bus never blocks on a subscriber that stops reading")
    void aStalledSubscriberIsDroppedNotWaitedFor() {
        WorkerEventBus bus = new WorkerEventBus();
        WorkerEventBus.Subscription stalled = bus.subscribe(0);

        // Publishing happens on a multicast receive loop and on the runtime's state thread. Neither
        // may be held up by a dashboard that stopped draining its socket.
        for (int i = 0; i < WorkerEventBus.SUBSCRIBER_QUEUE + 10; i++) {
            bus.publish(lanOpened(i + 1));
        }

        assertThat(stalled.isOpen()).isFalse();
        assertThat(bus.subscriberCount()).isZero();
        assertThat(bus.lastSequence()).isEqualTo(WorkerEventBus.SUBSCRIBER_QUEUE + 10);
    }

    @Test
    @DisplayName("history is bounded, so a long-running node does not grow one")
    void theBacklogIsBounded() throws Exception {
        WorkerEventBus bus = new WorkerEventBus();
        for (int i = 0; i < WorkerEventBus.HISTORY + 50; i++) {
            bus.publish(lanOpened(i + 1));
        }

        try (WorkerEventBus.Subscription late = bus.subscribe(0)) {
            // The oldest events are gone; the newest are what a prompt could still act on.
            WorkerEventBus.Published first = late.next(1, TimeUnit.SECONDS);
            assertThat(first).isNotNull();
            assertThat(first.seq()).isEqualTo(51);
        }
    }

    @Test
    @DisplayName("an event renders as one JSON line with its sequence")
    void theWireShape() {
        String json = WorkerEvent.named(WorkerEvent.LAN_SHARED)
                .with("world", "Bob's \"world\"")
                .with("port", 25565)
                .build()
                .toJson(9);

        assertThat(json).startsWith("{\"seq\":9,\"event\":\"lan.shared\"");
        // A world name is user-supplied text; an unescaped quote would break every reader.
        assertThat(json).contains("Bob's \\\"world\\\"");
        assertThat(json).contains("\"port\":\"25565\"");
    }
}
