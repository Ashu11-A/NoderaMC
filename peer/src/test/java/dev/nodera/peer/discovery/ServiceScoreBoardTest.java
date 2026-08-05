package dev.nodera.peer.discovery;

import dev.nodera.core.Bytes;
import dev.nodera.core.identity.NodeId;
import dev.nodera.protocol.service.ServiceDirectoryEntry;
import dev.nodera.protocol.service.ServiceKind;
import dev.nodera.protocol.service.ServiceLifecycle;
import dev.nodera.protocol.service.ServiceObservation;
import dev.nodera.protocol.service.ServiceRecord;
import dev.nodera.protocol.service.ServiceScore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The peer's own scoring: what it measured beats what it was told, availability beats latency, and a
 * service that says it is leaving is not chosen.
 */
final class ServiceScoreBoardTest {

    private static final long NOW = 1_700_000_000_000L;
    private static final UUID NETWORK = new UUID(1, 2);

    private static NodeId service(int n) {
        return new NodeId(new UUID(n, n));
    }

    private static ServiceRecord record(int n, ServiceLifecycle lifecycle, int used, int ceiling) {
        return new ServiceRecord(service(n), Bytes.unsafeWrap(new byte[44]),
                ServiceKind.RENDEZVOUS, lifecycle, NETWORK,
                List.of("rdv" + n + ".example:25601"), "0.1.0",
                used, ceiling, 0, 0, 0,
                NOW, NOW + 300_000L,
                lifecycle == ServiceLifecycle.DRAINING ? NOW + 30_000L : 0L);
    }

    private static ServiceDirectoryEntry entry(int n, int availability, int rttP95) {
        return new ServiceDirectoryEntry(record(n, ServiceLifecycle.SERVING, 0, 0),
                Bytes.unsafeWrap(new byte[64]),
                new ServiceScore(availability, rttP95 / 2, rttP95, 1_000, 1_000, 3, 0)
                        .withComposite());
    }

    @Test
    void the_composite_matches_the_shared_formula_and_not_the_transmitted_number() {
        // The non-authority argument in one assertion: a tracker inflating a composite changes
        // nothing, because the peer recomputes from the components.
        ServiceDirectoryEntry honest = entry(1, 900, 100);
        ServiceScoreBoard board = new ServiceScoreBoard();
        assertThat(board.scoreOf(honest, NOW))
                .isEqualTo(ServiceScore.composite(900, 100, 1_000, 1_000));

        ServiceDirectoryEntry liar = new ServiceDirectoryEntry(honest.record(), honest.signature(),
                new ServiceScore(0, 5_000, 5_000, 1_000, 1_000, 1, 1_000));
        assertThat(board.scoreOf(liar, NOW)).isLessThan(honest.score().compositePermille());
    }

    @Test
    void availability_outranks_latency() {
        // Registration and discovery are latency-tolerant, so a slow-but-up relay must beat a
        // fast-but-flaky one; otherwise selection chases RTT onto relays that keep dropping.
        ServiceScoreBoard board = new ServiceScoreBoard();
        int slowButUp = board.scoreOf(entry(1, 1_000, 400), NOW);
        int fastButFlaky = board.scoreOf(entry(2, 500, 10), NOW);
        assertThat(slowButUp).isGreaterThan(fastButFlaky);
    }

    @Test
    void a_peers_own_measurements_override_the_trackers_aggregate() {
        // The whole reason a peer scores locally: a relay with excellent global availability that
        // this peer cannot reach is useless to this peer.
        ServiceScoreBoard board = new ServiceScoreBoard();
        ServiceDirectoryEntry globallyGreat = entry(1, 1_000, 20);
        for (int i = 0; i < 10; i++) {
            board.record(service(1), ServiceKind.RENDEZVOUS, false, -1, NOW);
        }
        assertThat(board.scoreOf(globallyGreat, NOW))
                .as("a service this peer never reached scores zero however well others rate it")
                .isZero();
    }

    @Test
    void a_partially_reachable_service_scores_between_the_extremes() {
        ServiceScoreBoard board = new ServiceScoreBoard();
        for (int i = 0; i < 10; i++) {
            board.record(service(1), ServiceKind.RENDEZVOUS, i < 8, i < 8 ? 50 : -1, NOW);
        }
        assertThat(board.measuredAvailabilityPermille(service(1))).isEqualTo(800);
        int scored = board.scoreOf(entry(1, 1_000, 20), NOW);
        assertThat(scored)
                .isGreaterThan(0)
                .isLessThan(ServiceScore.composite(1_000, 50, 1_000, 1_000));
    }

    @Test
    void a_failed_probe_is_not_a_fast_probe() {
        // Reachability reports -1 for unreachable; folding that in as 0 ms would make a dead relay
        // the best-scoring one in the directory.
        ServiceScoreBoard board = new ServiceScoreBoard();
        board.record(service(1), ServiceKind.RENDEZVOUS, false, -1, NOW);
        board.record(service(1), ServiceKind.RENDEZVOUS, true, 80, NOW);
        List<ServiceObservation> rows = board.observations(NOW);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).probes()).isEqualTo(2);
        assertThat(rows.get(0).successes()).isEqualTo(1);
        assertThat(rows.get(0).rttP95Millis())
                .as("percentiles come from the successful probes only")
                .isEqualTo(80);
    }

    @Test
    void a_draining_service_is_never_selected() {
        ServiceScoreBoard board = new ServiceScoreBoard();
        ServiceDirectoryEntry draining = new ServiceDirectoryEntry(
                record(1, ServiceLifecycle.DRAINING, 0, 0), Bytes.unsafeWrap(new byte[64]),
                new ServiceScore(1_000, 5, 10, 1_000, 1_000, 9, 0).withComposite());
        assertThat(board.scoreOf(draining, NOW)).isZero();
        assertThat(board.select(List.of(draining), 3, NOW)).isEmpty();
    }

    @Test
    void an_expired_record_is_never_selected() {
        ServiceScoreBoard board = new ServiceScoreBoard();
        ServiceDirectoryEntry stale = entry(1, 1_000, 10);
        assertThat(board.scoreOf(stale, NOW + 300_001L)).isZero();
    }

    @Test
    void selection_returns_several_endpoints_best_first() {
        // A peer that registered with several relays but only used the first would have redundancy on
        // paper and a single point of failure in practice (docs/rendezvous/REFERENCE.md).
        ServiceScoreBoard board = new ServiceScoreBoard();
        List<ServiceDirectoryEntry> directory =
                List.of(entry(1, 500, 200), entry(2, 1_000, 30), entry(3, 900, 60));
        List<ServiceDirectoryEntry> chosen = board.select(directory, 2, NOW);
        assertThat(chosen).hasSize(2);
        assertThat(chosen.get(0).record().service()).isEqualTo(service(2));
        assertThat(chosen.get(1).record().service()).isEqualTo(service(3));
    }

    @Test
    void the_trackers_aggregate_breaks_a_tie_between_relays_this_peer_cannot_separate() {
        // A handful of probes at similar RTT make two relays look identical to one peer. Falling
        // straight to an id tie-break there would discard the network's broader evidence for an
        // arbitrary constant.
        ServiceScoreBoard board = new ServiceScoreBoard();
        for (int n : new int[] {1, 2}) {
            for (int i = 0; i < 5; i++) {
                board.record(service(n), ServiceKind.RENDEZVOUS, true, 25, NOW);
            }
        }
        List<ServiceDirectoryEntry> chosen =
                board.select(List.of(entry(1, 500, 300), entry(2, 1_000, 30)), 1, NOW);
        assertThat(chosen.get(0).record().service()).isEqualTo(service(2));
    }

    @Test
    void two_peers_with_the_same_evidence_choose_the_same_relays() {
        // Ties break on service id. Without it, a swarm's peers scatter across relays for no reason
        // and never share a path with each other.
        List<ServiceDirectoryEntry> directory =
                List.of(entry(3, 900, 50), entry(1, 900, 50), entry(2, 900, 50));
        List<NodeId> first = new ServiceScoreBoard().select(directory, 2, NOW).stream()
                .map(e -> e.record().service()).toList();
        List<NodeId> second = new ServiceScoreBoard().select(directory, 2, NOW).stream()
                .map(e -> e.record().service()).toList();
        assertThat(first).isEqualTo(second).containsExactly(service(1), service(2));
    }

    @Test
    void a_fanout_larger_than_the_directory_returns_what_exists() {
        ServiceScoreBoard board = new ServiceScoreBoard();
        assertThat(board.select(List.of(entry(1, 900, 50)), 5, NOW)).hasSize(1);
        assertThat(board.select(List.of(), 3, NOW)).isEmpty();
    }

    @Test
    void a_fanout_below_one_still_returns_one() {
        ServiceScoreBoard board = new ServiceScoreBoard();
        assertThat(board.select(List.of(entry(1, 900, 50), entry(2, 800, 50)), 0, NOW)).hasSize(1);
    }

    @Test
    void the_sample_window_is_bounded() {
        // A worker runs for weeks; the measurement memory must not grow with uptime.
        ServiceScoreBoard board = new ServiceScoreBoard();
        for (int i = 0; i < ServiceScoreBoard.WINDOW_SAMPLES * 3; i++) {
            board.record(service(1), ServiceKind.RENDEZVOUS, true, 10, NOW + i);
        }
        assertThat(board.observations(NOW).get(0).probes())
                .isEqualTo(ServiceScoreBoard.WINDOW_SAMPLES);
    }

    @Test
    void a_recent_recovery_pulls_the_score_back_up() {
        // The window is a ring, so an outage ages out instead of condemning a relay forever.
        ServiceScoreBoard board = new ServiceScoreBoard();
        for (int i = 0; i < ServiceScoreBoard.WINDOW_SAMPLES; i++) {
            board.record(service(1), ServiceKind.RENDEZVOUS, false, -1, NOW);
        }
        assertThat(board.scoreOf(entry(1, 1_000, 20), NOW)).isZero();
        for (int i = 0; i < ServiceScoreBoard.WINDOW_SAMPLES; i++) {
            board.record(service(1), ServiceKind.RENDEZVOUS, true, 40, NOW);
        }
        assertThat(board.measuredAvailabilityPermille(service(1))).isEqualTo(1_000);
        assertThat(board.scoreOf(entry(1, 1_000, 20), NOW)).isGreaterThan(0);
    }

    @Test
    void capacity_comes_from_the_services_own_record() {
        // Self-reported, and weighted 20 of 100 — enough for an operator to shed load, not enough for
        // a service to flatter itself into first place.
        ServiceScoreBoard board = new ServiceScoreBoard();
        ServiceDirectoryEntry roomy = new ServiceDirectoryEntry(
                record(1, ServiceLifecycle.SERVING, 10, 1_000), Bytes.unsafeWrap(new byte[64]),
                new ServiceScore(900, 50, 50, 0, 1_000, 3, 0).withComposite());
        ServiceDirectoryEntry full = new ServiceDirectoryEntry(
                record(2, ServiceLifecycle.SERVING, 1_000, 1_000), Bytes.unsafeWrap(new byte[64]),
                new ServiceScore(900, 50, 50, 0, 1_000, 3, 0).withComposite());
        assertThat(board.scoreOf(roomy, NOW)).isGreaterThan(board.scoreOf(full, NOW));
    }

    @Test
    void an_unmeasured_service_is_still_selectable_from_the_aggregate() {
        // The position a peer joining the network is in, and the reason the tracker aggregate exists
        // at all: with no measurements of its own, a peer must still be able to choose.
        ServiceScoreBoard board = new ServiceScoreBoard();
        assertThat(board.measuredServices()).isZero();
        assertThat(board.select(List.of(entry(1, 950, 40)), 3, NOW)).hasSize(1);
    }

    @Test
    void forgetting_a_service_drops_its_history() {
        ServiceScoreBoard board = new ServiceScoreBoard();
        board.record(service(1), ServiceKind.RENDEZVOUS, false, -1, NOW);
        assertThat(board.measuredAvailabilityPermille(service(1))).isZero();
        board.forget(service(1));
        assertThat(board.measuredAvailabilityPermille(service(1))).isEqualTo(-1);
        assertThat(board.measuredServices()).isZero();
    }

    @Test
    void observations_carry_counters_and_never_a_verdict() {
        // The shape that lets a tracker aggregate evidence instead of trusting one peer's judgement.
        ServiceScoreBoard board = new ServiceScoreBoard();
        board.record(service(1), ServiceKind.RENDEZVOUS, true, 25, NOW);
        ServiceObservation row = board.observations(NOW).get(0);
        assertThat(row.probes()).isEqualTo(1);
        assertThat(row.successes()).isEqualTo(1);
        assertThat(row.availabilityPermille()).isEqualTo(1_000);
        assertThat(row.observedAtEpochMillis()).isEqualTo(NOW);
        assertThat(row.kind()).isEqualTo(ServiceKind.RENDEZVOUS);
    }
}
