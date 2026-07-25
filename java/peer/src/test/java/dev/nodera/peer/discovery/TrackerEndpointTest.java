package dev.nodera.peer.discovery;

import dev.nodera.peer.discovery.TrackerClient.Endpoint;
import dev.nodera.peer.discovery.TrackerClient.Transport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The scheme-aware tracker endpoint: a peer may reach a tracker over TCP (the complete surface) or
 * UDP (the cheap one), and every route already written in an operator's config must keep working.
 */
@DisplayName("TrackerClient.Endpoint")
final class TrackerEndpointTest {

    @Test
    @DisplayName("a bare host:port stays TCP, so no existing config changes meaning")
    void bareRouteIsTcp() {
        Endpoint endpoint = Endpoint.parse("127.0.0.1:25600");
        assertEquals("127.0.0.1", endpoint.host());
        assertEquals(25_600, endpoint.port());
        assertEquals(Transport.TCP, endpoint.transport());
    }

    @Test
    @DisplayName("tcp:// and udp:// select the surface")
    void schemesSelectTheTransport() {
        assertEquals(Transport.TCP, Endpoint.parse("tcp://tracker.example:25600").transport());
        assertEquals(Transport.UDP, Endpoint.parse("udp://tracker.example:25600").transport());
        // The scheme is case-insensitive; operators write config by hand.
        assertEquals(Transport.UDP, Endpoint.parse("UDP://tracker.example:25600").transport());
    }

    @Test
    @DisplayName("a literal IPv6 route keeps its address and loses only the brackets")
    void ipv6RoutesParse() {
        Endpoint endpoint = Endpoint.parse("udp://[::1]:25600");
        assertEquals("::1", endpoint.host());
        assertEquals(25_600, endpoint.port());
        assertEquals(Transport.UDP, endpoint.transport());
    }

    @Test
    @DisplayName("an unknown scheme is refused at parse time, not discovered as a dead endpoint")
    void unknownSchemeIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> Endpoint.parse("http://x.example:80"));
        assertThrows(IllegalArgumentException.class, () -> Endpoint.parse("quic://x.example:80"));
    }

    @Test
    @DisplayName("malformed routes are still refused with the scheme in play")
    void malformedRoutesAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> Endpoint.parse("udp://nohost"));
        assertThrows(IllegalArgumentException.class, () -> Endpoint.parse("udp://host:"));
        assertThrows(IllegalArgumentException.class, () -> Endpoint.parse("udp://host:notaport"));
        assertThrows(IllegalArgumentException.class, () -> Endpoint.parse("tcp://host:70000"));
    }

    @Test
    @DisplayName("asTcp gives a UDP endpoint its fallback surface at the same address")
    void asTcpKeepsHostAndPort() {
        Endpoint udp = Endpoint.parse("udp://tracker.example:25600");
        Endpoint fallback = udp.asTcp();
        assertEquals(udp.host(), fallback.host());
        assertEquals(udp.port(), fallback.port());
        assertEquals(Transport.TCP, fallback.transport());
        // Already-TCP endpoints are returned as themselves rather than re-wrapped.
        assertEquals(fallback, fallback.asTcp());
    }

    @Test
    @DisplayName("toString round-trips through parse, so config can be rewritten from a value")
    void toStringRoundTrips() {
        for (String route : new String[] {"tcp://a.example:1", "udp://b.example:65535"}) {
            assertEquals(route, Endpoint.parse(route).toString());
        }
        // A bare route normalises to its explicit tcp:// form.
        assertEquals("tcp://c.example:25600", Endpoint.parse("c.example:25600").toString());
    }
}
