package dev.nodera.peer.metric;

import dev.nodera.core.identity.NodeId;
import dev.nodera.transport.PeerAddress;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The per-peer table is bounded by the side that fills it (issue #218).
 *
 * <p>Eviction used to live inside {@link PeerTrafficMeter#snapshot()}, which had no production
 * caller: the only path that removed anything from the table was reachable only from a readout
 * nobody read. A worker with no companion app attached therefore kept one entry per peer it had ever
 * exchanged a frame with, for the life of the process — the leak was real and the lid existed.
 *
 * <p>These tests never read the meter while they are filling it. Everything they assert is produced
 * by {@code recordTx} alone, because "does the table shrink when somebody looks at it" is exactly
 * the question that was already answered yes and did not matter.
 */
final class PeerTrafficMeterTest {

    /** Comfortably past both the retention and the sweep interval. */
    private static final long PAST_RETENTION =
            PeerTrafficMeter.IDLE_EVICT_NANOS + PeerTrafficMeter.SWEEP_INTERVAL_NANOS + 1L;

    private final AtomicLong clock = new AtomicLong(1_000_000_000L);
    private final PeerTrafficMeter meter = new PeerTrafficMeter(clock::get);

    @Test
    @DisplayName("an idle peer's counters are dropped without anything reading the meter")
    void an_idle_peer_is_dropped_without_a_reader() {
        PeerAddress quiet = peer(1);
        PeerAddress chatty = peer(2);

        meter.recordTx(quiet, 100);
        clock.addAndGet(PAST_RETENTION);
        // The only thing that happens between the two `quiet` samples is other traffic. If that is
        // not enough to sweep, the 100 bytes below survive and the totals add up to 107.
        meter.recordTx(chatty, 1);
        meter.recordTx(quiet, 7);

        assertThat(meter.byNode().get(quiet.nodeId()))
                .extracting(PeerTrafficMeter.PeerTraffic::totalTxBytes)
                .as("the idle entry was evicted, so this peer starts over")
                .isEqualTo(7L);
    }

    @Test
    @DisplayName("a node that meets a thousand peers keeps none of the ones that went quiet")
    void the_table_does_not_grow_while_nothing_reads_it() {
        int met = 1_000;
        for (int i = 0; i <= met; i++) {
            // Each peer speaks once, long after the last one did. Every entry ahead of the current
            // one is past its retention by the time the next frame is recorded — including, on the
            // last pass, peer `met - 1` itself.
            clock.addAndGet(PAST_RETENTION);
            meter.recordTx(peer(i), 64);
        }

        // The clock does not move again, so no further sweep is due (the last one claimed the next
        // slot a whole interval ahead) and this loop cannot evict anything itself. Any peer whose
        // entry survived the run therefore reports 64 + 1 bytes; an evicted one reports 1.
        for (int i = 0; i < met; i++) {
            meter.recordTx(peer(i), 1);
        }

        var table = meter.byNode();
        assertThat(table).hasSize(met + 1);
        for (int i = 0; i < met; i++) {
            assertThat(table.get(peer(i).nodeId()).totalTxBytes())
                    .as("peer %d must have been evicted while it was idle", i)
                    .isEqualTo(1L);
        }
    }

    @Test
    @DisplayName("a peer that keeps talking is never evicted, however long the node runs")
    void a_busy_peer_survives_every_sweep() {
        PeerAddress busy = peer(7);
        for (int i = 0; i < 50; i++) {
            clock.addAndGet(PAST_RETENTION);
            meter.recordTx(busy, 10);
        }

        assertThat(meter.byNode().get(busy.nodeId()))
                .extracting(PeerTrafficMeter.PeerTraffic::totalTxBytes)
                .as("eviction is for silence, not for age")
                .isEqualTo(500L);
    }

    private static PeerAddress peer(int index) {
        return PeerAddress.of(new NodeId(new UUID(0xB0BL, index)), "127.0.0.1:" + (30_000 + index));
    }
}
