package dev.nodera.peer.discovery;

import dev.nodera.core.Bytes;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.protocol.NoderaMessage;
import dev.nodera.protocol.codec.MessageCodec;
import dev.nodera.protocol.wire.WireCodec;
import dev.nodera.protocol.service.ServiceAnnounceAck;
import dev.nodera.protocol.service.ServiceDirectoryEntry;
import dev.nodera.protocol.service.ServiceDirectoryQuery;
import dev.nodera.protocol.service.ServiceDirectoryResponse;
import dev.nodera.protocol.service.ServiceDrainNotice;
import dev.nodera.protocol.service.ServiceKind;
import dev.nodera.protocol.service.ServiceLifecycle;
import dev.nodera.protocol.service.ServiceRecord;
import dev.nodera.protocol.service.ServiceScore;
import dev.nodera.protocol.service.ServiceScoreReport;
import dev.nodera.transport.Frames;
import dev.nodera.transport.Reachability;
import dev.nodera.transport.rendezvous.RendezvousEndpoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The discovery lane end to end on the peer side: ask a real tracker socket which rendezvous exist,
 * verify what comes back, select several, and migrate when one says it is leaving.
 *
 * <p>The tracker here is a stub rather than the Rust binary — the binary's own side of this exchange
 * is proven in {@code nodera-tracker}'s dispatch tests, and what needs proving here is that the peer
 * decodes the answer, refuses a forged row, keeps more than one endpoint, and moves on a drain notice.
 */
final class RendezvousDirectoryTest {

    private static final UUID NETWORK = new UUID(1, 2);

    private final List<StubTracker> stubs = new CopyOnWriteArrayList<>();

    @AfterEach
    void closeStubs() {
        for (StubTracker stub : stubs) {
            stub.close();
        }
    }

    /**
     * A tracker that answers a directory query with a fixed list and records the score reports it
     * receives.
     */
    private final class StubTracker implements AutoCloseable {
        private final ServerSocket server;
        private final AtomicBoolean running = new AtomicBoolean(true);
        /**
         * Whether this tracker answers. See {@link #silence()} — the "off" state a test needs is
         * "does not answer", which is not the same thing as "socket closed".
         */
        private final AtomicBoolean answering = new AtomicBoolean(true);
        private final List<ServiceDirectoryEntry> answer;
        private final List<ServiceScoreReport> reports = new CopyOnWriteArrayList<>();
        private final List<ServiceDirectoryQuery> queries = new CopyOnWriteArrayList<>();
        private final Thread thread;

        StubTracker(List<ServiceDirectoryEntry> answer) throws IOException {
            this.answer = answer;
            this.server = new ServerSocket(0);
            this.thread = new Thread(this::serve, "stub-tracker");
            thread.setDaemon(true);
            thread.start();
            stubs.add(this);
        }

        private void serve() {
            while (running.get()) {
                try (Socket socket = server.accept()) {
                    if (!answering.get()) {
                        // Accepted and dropped. The caller sees a connection that yields no frame,
                        // which is what "this tracker is not answering" looks like on the wire.
                        continue;
                    }
                    var frame = Frames.read(socket.getInputStream());
                    if (frame.isEmpty()) {
                        continue;
                    }
                    NoderaMessage request = WireCodec.decode(frame.get());
                    NoderaMessage reply;
                    if (request instanceof ServiceDirectoryQuery query) {
                        queries.add(query);
                        reply = new ServiceDirectoryResponse(answer);
                    } else if (request instanceof ServiceScoreReport report) {
                        reports.add(report);
                        reply = new ServiceAnnounceAck(true, 120, "", List.of());
                    } else {
                        reply = new ServiceAnnounceAck(false, 120, "unexpected", List.of());
                    }
                    Frames.write(socket.getOutputStream(), WireCodec.encode(reply));
                    socket.getOutputStream().flush();
                } catch (IOException | RuntimeException e) {
                    if (running.get()) {
                        continue;
                    }
                    return;
                }
            }
        }

        TrackerClient.Endpoint endpoint() {
            return new TrackerClient.Endpoint("127.0.0.1", server.getLocalPort());
        }

        /**
         * Stop answering, but keep the port bound.
         *
         * <p>This is what a test means by "the tracker is down", and closing the socket is not a
         * reliable way to say it. Closing releases the port the moment the OS is ready, so the
         * assertion that follows depends on two things the test does not control: that
         * {@code close()} interrupts a blocked {@code accept()} before the next connect lands, and
         * that nothing else on the machine binds the freed ephemeral port in between. Either one
         * going the other way turns "unreachable" back into "answered", which is exactly the
         * intermittent failure this replaced —
         * {@code an_unreachable_tracker_leaves_the_previous_selection_in_place} failed with
         * {@code Expecting empty but was: [127.0.0.1:25601]} in one CI run and passed in another on
         * the same commit.
         *
         * <p>Holding the port and refusing to answer is deterministic: the connect always succeeds,
         * the frame never arrives, and no other process can take the port and reply on our behalf.
         */
        void silence() {
            answering.set(false);
        }

        @Override
        public void close() {
            running.set(false);
            try {
                server.close();
            } catch (IOException ignored) {
                // closing a test fixture
            }
            try {
                // Joined so a stub from one test cannot still be serving during the next one.
                thread.join(2_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** A genuinely signed service record, so verification is exercised rather than bypassed. */
    private record SignedService(ServiceRecord record, Bytes signature, NodeIdentity identity) {}

    private static SignedService signedService(int port, ServiceLifecycle lifecycle) {
        NodeIdentity identity = NodeIdentity.generate();
        ServiceRecord record = new ServiceRecord(identity.nodeId(), identity.publicKeyBytes(),
                ServiceKind.RENDEZVOUS, lifecycle, NETWORK,
                List.of("127.0.0.1:" + port), "0.1.0",
                0, 0, 0, 0, 0,
                System.currentTimeMillis(), System.currentTimeMillis() + 300_000L,
                lifecycle == ServiceLifecycle.DRAINING ? System.currentTimeMillis() + 30_000L : 0L);
        return new SignedService(record, identity.sign(record.signedBytes()), identity);
    }

    private static ServiceDirectoryEntry entry(SignedService service, int availability, int rtt) {
        return new ServiceDirectoryEntry(service.record(), service.signature(),
                new ServiceScore(availability, rtt / 2, rtt, 1_000, 1_000, 3, 0).withComposite());
    }

    private RendezvousDirectory directory(StubTracker tracker, List<RendezvousEndpoint> seeds,
            int fanout) {
        TrackerClient trackers =
                new TrackerClient(List.of(tracker.endpoint()), NodeIdentity.generate());
        // Every candidate probes as reachable: what these tests assert is the discovery, verification,
        // selection and migration logic, not whether a port happens to be open on this machine. The
        // reachability-driven half of scoring is covered by ServiceScoreBoardTest.
        return new RendezvousDirectory(trackers, new ServiceScoreBoard(), NETWORK, fanout, seeds,
                (host, port, timeout) -> new Reachability.Probe(true, 25));
    }

    @Test
    void a_peer_with_no_configured_rendezvous_learns_one_from_a_tracker() throws IOException {
        // The requirement in one test: nothing configured but a tracker, and the peer comes back with
        // a rendezvous endpoint it was never told about.
        SignedService relay = signedService(25601, ServiceLifecycle.SERVING);
        StubTracker tracker = new StubTracker(List.of(entry(relay, 1_000, 30)));
        RendezvousDirectory directory = directory(tracker, List.of(), 3);

        assertThat(directory.selected()).isEmpty();
        List<RendezvousEndpoint> found = directory.sweep(System.currentTimeMillis());
        assertThat(found).containsExactly(RendezvousEndpoint.parse("127.0.0.1:25601"));
        assertThat(tracker.queries).hasSize(1);
        assertThat(tracker.queries.get(0).kind()).isEqualTo(ServiceKind.RENDEZVOUS);
    }

    @Test
    void a_forged_row_is_refused_and_never_dialled() throws IOException {
        // A tracker can hide a rendezvous or list an unreachable one. It cannot put words in a
        // service's mouth: the signature is over the service's own canonical bytes.
        SignedService honest = signedService(25601, ServiceLifecycle.SERVING);
        ServiceRecord moved = new ServiceRecord(honest.record().service(),
                honest.record().publicKey(), ServiceKind.RENDEZVOUS, ServiceLifecycle.SERVING,
                NETWORK, List.of("attacker.example:25601"), "0.1.0", 0, 0, 0, 0, 0,
                honest.record().issuedAtEpochMillis(), honest.record().expiresAtEpochMillis(), 0L);
        ServiceDirectoryEntry forged = new ServiceDirectoryEntry(moved, honest.signature(),
                new ServiceScore(1_000, 1, 1, 1_000, 1_000, 9, 0).withComposite());

        assertThat(TrackerClient.verifyServiceEntry(forged)).isFalse();
        StubTracker tracker = new StubTracker(List.of(forged));
        RendezvousDirectory directory = directory(tracker, List.of(), 3);
        assertThat(directory.sweep(System.currentTimeMillis())).isEmpty();
        assertThat(directory.knownServices()).isEmpty();
    }

    @Test
    void several_rendezvous_are_selected_not_just_the_best_one() throws IOException {
        // Registering with several and querying only the first converts redundancy into a silent
        // single point of failure (RENDEZVOUS.md §9.1).
        SignedService best = signedService(25601, ServiceLifecycle.SERVING);
        SignedService second = signedService(25602, ServiceLifecycle.SERVING);
        SignedService third = signedService(25603, ServiceLifecycle.SERVING);
        StubTracker tracker = new StubTracker(List.of(
                entry(best, 1_000, 20), entry(second, 950, 40), entry(third, 900, 60)));
        RendezvousDirectory directory = directory(tracker, List.of(), 2);

        directory.sweep(System.currentTimeMillis());
        assertThat(directory.selectedServices()).hasSize(2);
        assertThat(directory.selectedServices().get(0).record().service())
                .isEqualTo(best.record().service());
    }

    @Test
    void a_draining_service_is_not_selected_even_when_it_scores_best() throws IOException {
        SignedService leaving = signedService(25601, ServiceLifecycle.DRAINING);
        SignedService staying = signedService(25602, ServiceLifecycle.SERVING);
        StubTracker tracker = new StubTracker(List.of(
                entry(leaving, 1_000, 5), entry(staying, 700, 200)));
        RendezvousDirectory directory = directory(tracker, List.of(), 3);

        directory.sweep(System.currentTimeMillis());
        assertThat(directory.selectedServices()).hasSize(1);
        assertThat(directory.selectedServices().get(0).record().service())
                .isEqualTo(staying.record().service());
    }

    @Test
    void a_drain_notice_migrates_the_peer_to_the_replacement_it_names() throws IOException {
        // The seamless-handover requirement. The peer moves off the departing relay using the
        // replacement carried in the notice — no tracker round trip, no waiting for a sweep, and
        // while the old relay is still carrying its existing circuits.
        SignedService leaving = signedService(25601, ServiceLifecycle.SERVING);
        SignedService replacement = signedService(25650, ServiceLifecycle.SERVING);
        StubTracker tracker = new StubTracker(List.of(entry(leaving, 1_000, 30)));
        RendezvousDirectory directory = directory(tracker, List.of(), 3);

        List<RendezvousEndpoint> before = directory.sweep(System.currentTimeMillis());
        assertThat(before).containsExactly(RendezvousEndpoint.parse("127.0.0.1:25601"));

        SignedService draining = drainingVersionOf(leaving);
        ServiceDrainNotice notice = new ServiceDrainNotice(draining.record(),
                draining.signature(), List.of(entry(replacement, 900, 50)),
                ServiceDrainNotice.REASON_UPDATE);

        List<RendezvousEndpoint> after = directory.onDrainNotice(notice);
        assertThat(after)
                .as("the peer moved to the replacement the notice named")
                .containsExactly(RendezvousEndpoint.parse("127.0.0.1:25650"));
        assertThat(directory.isDraining(leaving.record().service())).isTrue();
    }

    @Test
    void a_drain_notice_notifies_listeners_so_a_transport_can_re_register() throws IOException {
        SignedService leaving = signedService(25601, ServiceLifecycle.SERVING);
        SignedService replacement = signedService(25650, ServiceLifecycle.SERVING);
        StubTracker tracker = new StubTracker(List.of(entry(leaving, 1_000, 30)));
        RendezvousDirectory directory = directory(tracker, List.of(), 3);
        List<List<RendezvousEndpoint>> seen = new ArrayList<>();
        directory.onEndpointsChanged(seen::add);

        directory.sweep(System.currentTimeMillis());
        SignedService draining = drainingVersionOf(leaving);
        directory.onDrainNotice(new ServiceDrainNotice(draining.record(), draining.signature(),
                List.of(entry(replacement, 900, 50)), ServiceDrainNotice.REASON_UPDATE));

        assertThat(seen).hasSize(2);
        assertThat(seen.get(1)).containsExactly(RendezvousEndpoint.parse("127.0.0.1:25650"));
    }

    @Test
    void a_drained_service_stays_out_until_it_announces_serving_again() throws IOException {
        // Its own record is the authority on whether it is back, not our memory of the notice.
        SignedService relay = signedService(25601, ServiceLifecycle.SERVING);
        StubTracker tracker = new StubTracker(List.of(entry(relay, 1_000, 30)));
        RendezvousDirectory directory = directory(tracker, List.of(), 3);
        directory.sweep(System.currentTimeMillis());

        SignedService draining = drainingVersionOf(relay);
        directory.onDrainNotice(new ServiceDrainNotice(draining.record(), draining.signature(),
                List.of(), ServiceDrainNotice.REASON_UPDATE));
        assertThat(directory.isDraining(relay.record().service())).isTrue();

        // The tracker still lists it as SERVING (it restarted and re-announced), so the exclusion lifts.
        assertThat(directory.sweep(System.currentTimeMillis()))
                .containsExactly(RendezvousEndpoint.parse("127.0.0.1:25601"));
        assertThat(directory.isDraining(relay.record().service())).isFalse();
    }

    @Test
    void configured_seeds_survive_discovery() throws IOException {
        // An operator who pinned a relay meant it, and a LAN-only deployment has no tracker to ask.
        SignedService discovered = signedService(25650, ServiceLifecycle.SERVING);
        StubTracker tracker = new StubTracker(List.of(entry(discovered, 1_000, 30)));
        RendezvousEndpoint seed = RendezvousEndpoint.parse("127.0.0.1:25601");
        RendezvousDirectory directory = directory(tracker, List.of(seed), 3);

        assertThat(directory.selected()).containsExactly(seed);
        assertThat(directory.sweep(System.currentTimeMillis()))
                .containsExactly(seed, RendezvousEndpoint.parse("127.0.0.1:25650"));
    }

    @Test
    void a_sweep_reports_what_it_measured_back_to_the_tracker() throws IOException {
        // The loop that closes the scoring system: peers measure, trackers aggregate, peers read the
        // aggregate. Without the report the aggregate would only ever be the services' self-praise.
        SignedService relay = signedService(25601, ServiceLifecycle.SERVING);
        StubTracker tracker = new StubTracker(List.of(entry(relay, 1_000, 30)));
        RendezvousDirectory directory = directory(tracker, List.of(), 3);

        directory.sweep(System.currentTimeMillis());
        assertThat(tracker.reports).hasSize(1);
        ServiceScoreReport report = tracker.reports.get(0);
        assertThat(report.observations()).hasSize(1);
        assertThat(report.observations().get(0).service()).isEqualTo(relay.record().service());
        assertThat(report.observations().get(0).probes()).isEqualTo(1);
        assertThat(report.signature().isEmpty())
                .as("a report must be attributable or scoring is the cheapest attack in the system")
                .isFalse();
    }

    @Test
    void an_unreachable_tracker_leaves_the_previous_selection_in_place() throws IOException {
        // Tracker down must degrade discovery only. The relays the peer already knows keep working.
        SignedService relay = signedService(25601, ServiceLifecycle.SERVING);
        StubTracker tracker = new StubTracker(List.of(entry(relay, 1_000, 30)));
        RendezvousDirectory directory = directory(tracker, List.of(), 3);
        directory.sweep(System.currentTimeMillis());
        assertThat(directory.selected()).hasSize(1);

        // Silenced rather than closed: see StubTracker.silence(). The tracker keeps its port and
        // stops answering, so "unreachable" cannot quietly turn back into "answered".
        tracker.silence();
        // No answer, so no candidates, so nothing selected — but the call returns rather than throwing,
        // and a caller holding the previous list keeps using it.
        assertThat(directory.sweep(System.currentTimeMillis())).isEmpty();
    }

    @Test
    void a_malformed_route_does_not_abort_a_sweep() throws IOException {
        // Routes are a service's own claim; one bad entry must not cost the peer the four good relays
        // in the same answer.
        NodeIdentity identity = NodeIdentity.generate();
        ServiceRecord broken = new ServiceRecord(identity.nodeId(), identity.publicKeyBytes(),
                ServiceKind.RENDEZVOUS, ServiceLifecycle.SERVING, NETWORK,
                List.of("this is not a route"), "0.1.0", 0, 0, 0, 0, 0,
                System.currentTimeMillis(), System.currentTimeMillis() + 300_000L, 0L);
        ServiceDirectoryEntry brokenEntry = new ServiceDirectoryEntry(broken,
                identity.sign(broken.signedBytes()),
                new ServiceScore(1_000, 1, 1, 1_000, 1_000, 3, 0).withComposite());
        SignedService good = signedService(25650, ServiceLifecycle.SERVING);

        StubTracker tracker = new StubTracker(List.of(brokenEntry, entry(good, 900, 40)));
        RendezvousDirectory directory = directory(tracker, List.of(), 3);
        assertThat(directory.sweep(System.currentTimeMillis()))
                .containsExactly(RendezvousEndpoint.parse("127.0.0.1:25650"));
    }

    private static SignedService drainingVersionOf(SignedService service) {
        ServiceRecord draining = new ServiceRecord(service.record().service(),
                service.record().publicKey(), ServiceKind.RENDEZVOUS, ServiceLifecycle.DRAINING,
                NETWORK, service.record().routes(), "0.1.0", 0, 0, 0, 0, 0,
                System.currentTimeMillis(), service.record().expiresAtEpochMillis(),
                System.currentTimeMillis() + 30_000L);
        return new SignedService(draining, service.identity().sign(draining.signedBytes()),
                service.identity());
    }
}
