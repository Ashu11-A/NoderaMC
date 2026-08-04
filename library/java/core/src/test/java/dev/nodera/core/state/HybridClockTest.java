package dev.nodera.core.state;

import dev.nodera.core.identity.NodeId;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The clock's whole job is to keep "newer" meaning the same thing on two machines that disagree
 * about the time. These are the disagreements it has to survive.
 */
final class HybridClockTest {

    private static NodeId node() {
        return NodeId.random();
    }

    @Test
    void readingsFromOneClockStrictlyIncrease() {
        HybridClock clock = new HybridClock(node(), () -> 1000L); // a clock that never moves
        List<Hlc> readings = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            readings.add(clock.now());
        }
        for (int i = 1; i < readings.size(); i++) {
            assertThat(readings.get(i).isAfter(readings.get(i - 1)))
                    .as("reading %d must follow reading %d", i, i - 1)
                    .isTrue();
        }
    }

    @Test
    void aBackwardsWallClockDoesNotReissueOldReadings() {
        AtomicLong wall = new AtomicLong(10_000);
        HybridClock clock = new HybridClock(node(), wall::get);
        Hlc before = clock.now();

        // NTP correction, a resumed laptop, a container's clock being set at start-up.
        wall.set(1_000);
        Hlc after = clock.now();

        assertThat(after.isAfter(before)).isTrue();
        assertThat(after.wallMillis())
                .as("the wall component never goes backwards, or the ordering it provides is a lie")
                .isEqualTo(before.wallMillis());
    }

    @Test
    void observingARemoteReadingDragsThisClockForward() {
        HybridClock slow = new HybridClock(node(), () -> 1_000L);
        Hlc fromTheFuture = new Hlc(9_000_000L, 3, node().value());

        slow.observe(fromTheFuture);
        Hlc next = slow.now();

        assertThat(next.isAfter(fromTheFuture))
                .as("a node that cannot answer 'after' to what it has seen will insist its own "
                        + "stale edits are the freshest")
                .isTrue();
    }

    @Test
    void twoNodesAtTheSameInstantStillOrderDeterministically() {
        NodeId a = node();
        NodeId b = node();
        Hlc fromA = new Hlc(5_000, 7, a.value());
        Hlc fromB = new Hlc(5_000, 7, b.value());

        // Whichever way it goes, BOTH peers must go the same way, or the region diverges.
        assertThat(fromA.compareTo(fromB)).isNotZero();
        assertThat(Integer.signum(fromA.compareTo(fromB)))
                .isEqualTo(-Integer.signum(fromB.compareTo(fromA)));
    }

    @Test
    void observingNullOrOlderReadingsChangesNothing() {
        HybridClock clock = new HybridClock(node(), () -> 5_000L);
        Hlc first = clock.now();

        clock.observe(null);
        clock.observe(new Hlc(1, 1, node().value()));

        assertThat(clock.now().isAfter(first)).isTrue();
    }
}
