package dev.nodera.diagnostics.metric;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link MessageCounters} per-type tallies (Task 18 acceptance #1).
 */
final class MessageCountersTest {

    @Test
    void talliesPerTypePerDirection() {
        MessageCounters c = new MessageCounters();
        c.recordTx("SessionKeepAlive");
        c.recordTx("SessionKeepAlive");
        c.recordRx("SessionKeepAlive");
        c.recordRx("MembershipUpdate");

        Map<String, long[]> snap = c.snapshot();
        assertThat(snap).containsKey("SessionKeepAlive");
        assertThat(snap.get("SessionKeepAlive")).containsExactly(2L, 1L);
        assertThat(snap.get("MembershipUpdate")).containsExactly(0L, 1L);
    }

    @Test
    void unseenKeyReportsZero() {
        MessageCounters c = new MessageCounters();
        assertThat(c.tx("Nope")).isZero();
        assertThat(c.rx("Nope")).isZero();
        assertThat(c.snapshot()).isEmpty();
    }

    @Test
    void snapshotIsSortedByKey() {
        MessageCounters c = new MessageCounters();
        c.recordRx("Zebra");
        c.recordRx("Alpha");
        c.recordRx("Mike");
        assertThat(c.snapshot().keySet()).containsExactly("Alpha", "Mike", "Zebra");
    }
}
