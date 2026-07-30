package dev.nodera.transport.rendezvous;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Parsing a rendezvous route as an operator actually writes one.
 *
 * <p>These exist because this class disagreed with {@link dev.nodera.peer.discovery.TrackerClient}
 * about what a route looks like, and nothing anywhere compared them. `tcp://host:port` is the
 * documented form in every example config, in `docker/compose.yml`, in `services/official.json` and
 * in the tracker's own parser — and this one split on the last colon and kept `tcp://host` as the
 * hostname.
 *
 * <p>Caught on a live run. The worker's STATE reported, for the same machine at the same moment,
 * with the relay answering protocol healthchecks:
 *
 * <pre>
 *   trackers    host="150.230.84.206"        reachable=true   47ms
 *   rendezvous  host="tcp://150.230.84.206"  reachable=false
 * </pre>
 *
 * A peer pointed at the official service list therefore registered with no relay at all, and had no
 * way to report why — the failure looked like an unreachable server rather than an unparsed string.
 */
class RendezvousEndpointTest {

    @Test
    @DisplayName("the documented tcp:// form parses to the host alone")
    void stripsTheScheme() {
        RendezvousEndpoint parsed = RendezvousEndpoint.parse("tcp://150.230.84.206:7500");

        assertEquals("150.230.84.206", parsed.host());
        assertEquals(7500, parsed.port());
    }

    @Test
    @DisplayName("a hostname is not turned into something a resolver cannot read")
    void stripsTheSchemeFromANamedHost() {
        RendezvousEndpoint parsed = RendezvousEndpoint.parse("tcp://rendezvous.noderamc.org:7500");

        assertEquals("rendezvous.noderamc.org", parsed.host());
    }

    @Test
    @DisplayName("udp names the same host this transport reaches over tcp")
    void acceptsUdpAsAName() {
        // The route list is shared between surfaces; refusing this entry would break a list that is
        // entirely correct.
        assertEquals("relay.example.org", RendezvousEndpoint.parse("udp://relay.example.org:7500").host());
    }

    @Test
    @DisplayName("a bare host:port still means exactly what it always did")
    void aBareRouteIsUnchanged() {
        RendezvousEndpoint parsed = RendezvousEndpoint.parse("127.0.0.1:25601");

        assertEquals("127.0.0.1", parsed.host());
        assertEquals(25601, parsed.port());
    }

    @Test
    @DisplayName("an IPv6 literal keeps its address and loses its brackets")
    void ipv6KeepsItsAddress() {
        // The brackets are what separate the address's colons from the port's, so they have to
        // survive the scheme being removed.
        assertEquals("2001:db8::1", RendezvousEndpoint.parse("tcp://[2001:db8::1]:7500").host());
        assertEquals("::1", RendezvousEndpoint.parse("[::1]:7500").host());
    }

    @Test
    @DisplayName("an unknown scheme is named, not resolved")
    void anUnknownSchemeIsRefused() {
        // Silently treating `https://relay:7500` as a hostname is how the original bug hid: the
        // failure surfaced later as an unreachable host rather than as a bad route.
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> RendezvousEndpoint.parse("https://relay.example.org:7500"));
        assertEquals(true, e.getMessage().contains("https"), e.getMessage());
    }

    @Test
    @DisplayName("a malformed route is still refused")
    void malformedRoutesAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> RendezvousEndpoint.parse("tcp://nope"));
        assertThrows(IllegalArgumentException.class, () -> RendezvousEndpoint.parse("host:"));
        assertThrows(IllegalArgumentException.class, () -> RendezvousEndpoint.parse("tcp://h:abc"));
    }
}
