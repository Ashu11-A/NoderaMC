package dev.nodera.testkit.harness;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Where the live stack listens, and what it puts in everybody else's config.
 *
 * <p>These four variables exist so a peer that is NOT this machine — a phone on the same Wi-Fi —
 * can join a scripted run. The risk they carry is the opposite one: that adding them quietly moved
 * every ordinary run off loopback. Every scenario, and every CI job, depends on the defaults, so the
 * defaults are what is asserted here.
 *
 * <p>The test reads the same environment the harness does rather than pretending it can change it —
 * Java has no supported way to set a variable for its own process, and a test that shelled out to
 * prove this would be testing a different JVM's environment. So it asserts loopback when nothing is
 * exported and the exported value when something is, which is the whole contract either way.
 *
 * <p>Thread-context: ordinary JUnit.
 */
class HarnessAddressingTest {

    @Test
    void servicesBindAndAdvertiseLoopbackUnlessTheEnvironmentSaysOtherwise() {
        assertThat(Topology.serviceBindAddress())
                .isEqualTo(expected("NODERA_SERVICE_BIND_ADDR"));
        assertThat(Topology.serviceAdvertiseAddress())
                .isEqualTo(expected("NODERA_SERVICE_ADVERTISE_ADDR"));
    }

    @Test
    void workersBindAndAdvertiseLoopbackUnlessTheEnvironmentSaysOtherwise() {
        assertThat(Topology.p2pBindAddress()).isEqualTo(expected("NODERA_P2P_BIND_ADDR"));
        assertThat(Topology.p2pAdvertiseAddress()).isEqualTo(expected("NODERA_P2P_ADVERTISE_ADDR"));
    }

    @Test
    void theEndpointsHandedToEveryPeerCarryTheAdvertisedAddress() {
        Topology topology = Topology.standard();
        String advertised = Topology.serviceAdvertiseAddress();

        assertThat(topology.trackerEndpoints())
                .containsExactly(advertised + ":" + topology.trackerPort());
        assertThat(topology.rendezvousEndpoints())
                .containsExactly(advertised + ":" + topology.rendezvousPort());
    }

    @Test
    void isLanBoundAgreesWithTheAddressesItIsDerivedFrom() {
        boolean loopbackOnly = "127.0.0.1".equals(Topology.serviceBindAddress())
                && "127.0.0.1".equals(Topology.serviceAdvertiseAddress());

        assertThat(Topology.isLanBound()).isEqualTo(!loopbackOnly);
    }

    private static String expected(String variable) {
        String value = System.getenv(variable);
        return value == null || value.isBlank() ? "127.0.0.1" : value.trim();
    }
}
