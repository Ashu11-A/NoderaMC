package dev.nodera.transport;

import dev.nodera.core.identity.NodeId;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Value-semantics and factory coverage for {@link PeerAddress} (Task 4 acceptance #5).
 *
 * <p>Thread-context: single test thread.
 */
final class PeerAddressTest {

    private static final NodeId ID_A =
            new NodeId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
    private static final NodeId ID_B =
            new NodeId(UUID.fromString("00000000-0000-0000-0000-000000000002"));

    @Test
    void serverFactoryNoArgsUsesServerRouteWithNullNodeId() {
        PeerAddress addr = PeerAddress.server();
        assertThat(addr.route()).isEqualTo("server");
        assertThat(addr.nodeId()).isNull();
    }

    @Test
    void serverFactoryWithNodeIdUsesServerRouteAndCarriesId() {
        PeerAddress addr = PeerAddress.server(ID_A);
        assertThat(addr.route()).isEqualTo("server");
        assertThat(addr.nodeId()).isEqualTo(ID_A);
    }

    @Test
    void ofFactoryIsEquivalentToConstructor() {
        PeerAddress a = new PeerAddress(ID_A, "relay-token-42");
        PeerAddress b = PeerAddress.of(ID_A, "relay-token-42");
        assertThat(b).isEqualTo(a);
    }

    @Test
    void valueSemanticsKeyOnNodeIdAndRoute() {
        PeerAddress a1 = PeerAddress.of(ID_A, "r1");
        PeerAddress a1Dup = PeerAddress.of(ID_A, "r1");
        PeerAddress a1OtherRoute = PeerAddress.of(ID_A, "r2");
        PeerAddress b1 = PeerAddress.of(ID_B, "r1");

        assertThat(a1).isEqualTo(a1Dup);
        assertThat(a1.hashCode()).isEqualTo(a1Dup.hashCode());
        assertThat(a1).isNotEqualTo(a1OtherRoute);
        assertThat(a1).isNotEqualTo(b1);
        assertThat(a1).isNotEqualTo(null);
        assertThat(a1).isNotEqualTo("not-a-peer-address");
    }

    @Test
    void preHandshakeAndPostHandshakeServerAddressesAreDistinct() {
        PeerAddress pre = PeerAddress.server();
        PeerAddress post = PeerAddress.server(ID_A);
        assertThat(pre).isNotEqualTo(post);
    }

    @Test
    void toStringMentionsRoute() {
        PeerAddress addr = PeerAddress.of(ID_A, "server");
        String s = addr.toString();
        assertThat(s).contains("server");
    }

    @Test
    void allowsNullNodeIdForPreHandshakeServerCase() {
        PeerAddress addr = new PeerAddress(null, "server");
        assertThat(addr.nodeId()).isNull();
        assertThat(addr.route()).isEqualTo("server");
    }

    @Test
    void rejectsNullRoute() {
        assertThatThrownBy(() -> new PeerAddress(ID_A, null))
                .isInstanceOf(NullPointerException.class);
    }
}
