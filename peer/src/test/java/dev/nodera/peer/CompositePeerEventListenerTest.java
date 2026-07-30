package dev.nodera.peer;

import dev.nodera.core.identity.NodeId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Fanning one runtime's events to several listeners.
 *
 * <p>The runtime takes a single listener, which was enough while the only consumer was a log line.
 * The gateway handover needs the same {@code onGatewayChanged} the logger does, and neither may
 * cost the other — these tests are about that independence.
 */
final class CompositePeerEventListenerTest {

    private static final NodeId A = new NodeId(new UUID(0, 1));
    private static final NodeId B = new NodeId(new UUID(0, 2));

    private static final class Recording implements PeerEventListener {
        private final List<String> seen = new ArrayList<>();

        @Override
        public void onGatewayChanged(NodeId previous, NodeId current, long epoch) {
            seen.add("gateway:" + previous + "→" + current + "@" + epoch);
        }

        @Override
        public void onPeerJoined(NodeId who) {
            seen.add("joined:" + who);
        }

        @Override
        public void onPeerLeft(NodeId who, String reason) {
            seen.add("left:" + who + ":" + reason);
        }

        @Override
        public void onKeepAlive(NodeId from, long seq) {
            seen.add("keepalive:" + from + ":" + seq);
        }
    }

    @Test
    @DisplayName("every listener sees every event")
    void eventsFanOut() {
        Recording one = new Recording();
        Recording two = new Recording();
        CompositePeerEventListener composite = new CompositePeerEventListener(one, two);

        composite.onGatewayChanged(A, B, 3L);
        composite.onPeerJoined(A);
        composite.onPeerLeft(B, "timeout");
        composite.onKeepAlive(A, 9L);

        assertThat(one.seen).hasSize(4).isEqualTo(two.seen);
        assertThat(one.seen.get(0)).contains("@3");
    }

    @Test
    @DisplayName("a throwing listener costs its own feature, never the others")
    void oneFaultyListenerIsContained() {
        Recording healthy = new Recording();
        PeerEventListener broken = new PeerEventListener() {
            @Override
            public void onGatewayChanged(NodeId previous, NodeId current, long epoch) {
                throw new IllegalStateException("listener on fire");
            }
        };
        // Broken first, so the healthy one is downstream of the fault.
        CompositePeerEventListener composite = new CompositePeerEventListener(broken, healthy);

        assertThatCode(() -> composite.onGatewayChanged(A, B, 1L)).doesNotThrowAnyException();

        // These events arrive on the runtime's own thread: letting one consumer's exception escape
        // would stop the rest hearing anything and take the event loop with it.
        assertThat(healthy.seen).hasSize(1);
    }

    @Test
    @DisplayName("nulls are dropped, so an optional consumer needs no branch at the call site")
    void nullsAreDropped() {
        Recording only = new Recording();
        CompositePeerEventListener composite =
                new CompositePeerEventListener(null, only, null);

        assertThat(composite.size()).isEqualTo(1);
        composite.onPeerJoined(A);
        assertThat(only.seen).containsExactly("joined:" + A);
    }

    @Test
    @DisplayName("no listeners at all is a working no-op")
    void emptyIsSafe() {
        CompositePeerEventListener composite = new CompositePeerEventListener();
        assertThat(composite.size()).isZero();
        assertThatCode(() -> {
            composite.onGatewayChanged(A, B, 1L);
            composite.onPeerLeft(A, "gone");
            composite.onSessionChanged(null);
        }).doesNotThrowAnyException();

        assertThat(new CompositePeerEventListener((PeerEventListener[]) null).size()).isZero();
    }

    @Test
    @DisplayName("the handover really does hear a migration through the composite")
    void theHandoverHearsAMigration() {
        GatewayHandover handover = new GatewayHandover();
        Recording logger = new Recording();
        GatewayHandoverListener gateway =
                new GatewayHandoverListener(handover, A, actions -> { });
        CompositePeerEventListener composite = new CompositePeerEventListener(logger, gateway);

        composite.onGatewayChanged(B, new NodeId(new UUID(0, 3)), 2L);

        // This is the whole reason the composite exists.
        assertThat(handover.isFrozen()).isTrue();
        assertThat(logger.seen).hasSize(1);
    }
}
