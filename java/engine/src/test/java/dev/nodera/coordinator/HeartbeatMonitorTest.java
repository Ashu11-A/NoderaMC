package dev.nodera.coordinator;

import dev.nodera.core.identity.NodeId;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HeartbeatMonitorTest {

    @Test
    void nodeLostAfterTimeout() {
        HeartbeatMonitor mon = new HeartbeatMonitor(60);
        NodeId n = CoordFixtures.node(1L);
        mon.heartbeat(n, 100);
        assertThat(mon.isLost(n, 160)).isFalse(); // exactly at timeout boundary (not >)
        assertThat(mon.isLost(n, 161)).isTrue();
        assertThat(mon.lostAsOf(161)).containsExactly(n);
    }

    @Test
    void freshHeartbeatKeepsAlive() {
        HeartbeatMonitor mon = new HeartbeatMonitor(60);
        NodeId n = CoordFixtures.node(1L);
        mon.heartbeat(n, 100);
        mon.heartbeat(n, 200);
        assertThat(mon.isLost(n, 250)).isFalse();
        assertThat(mon.lostAsOf(250)).isEmpty();
    }

    @Test
    void lostListIsDeterministicallyOrdered() {
        HeartbeatMonitor mon = new HeartbeatMonitor(60);
        mon.heartbeat(CoordFixtures.node(3L), 0);
        mon.heartbeat(CoordFixtures.node(1L), 0);
        mon.heartbeat(CoordFixtures.node(2L), 0);
        assertThat(mon.lostAsOf(1000)).containsExactly(
                CoordFixtures.node(1L), CoordFixtures.node(2L), CoordFixtures.node(3L));
    }

    @Test
    void forgetStopsTracking() {
        HeartbeatMonitor mon = new HeartbeatMonitor(60);
        NodeId n = CoordFixtures.node(1L);
        mon.heartbeat(n, 0);
        mon.forget(n);
        assertThat(mon.lostAsOf(1000)).isEmpty();
        assertThat(mon.lastSeen(n)).isNull();
    }
}
