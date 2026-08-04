package dev.nodera.core.net;

import dev.nodera.core.net.NetworkAddresses.CandidateNic;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Choosing an advertise address on a machine that has more than one plausible one.
 *
 * <p>These are the arrangements that broke a live run and that no CI runner can be made to have:
 * a container bridge and a VPN tunnel sitting alongside the real NIC, in an enumeration order
 * nobody controls. The selector is pure precisely so they can be written down.
 *
 * <p>Thread-context: ordinary JUnit.
 */
class NetworkAddressesTest {

    private static CandidateNic up(String name, String address) {
        return new CandidateNic(name, List.of(address), true, false, false);
    }

    @Test
    void prefersTheRealNicOverABridgeAndAVpn() {
        // Enumeration order deliberately puts the wrong answers first — that is the order the field
        // machine reported, and "first site-local address wins" picked the VPN from it.
        List<CandidateNic> machine = List.of(
                up("docker0", "172.17.0.1"),
                up("br-89d70d6a9f06", "172.18.0.1"),
                up("vo_c_HSoxzce_d", "10.200.1.1"),
                up("wlo1", "192.168.1.68"));

        assertThat(NetworkAddresses.selectAdvertiseHost(machine)).hasValue("192.168.1.68");
    }

    @Test
    void picksAWiredNicWhenThatIsWhatIsThere() {
        assertThat(NetworkAddresses.selectAdvertiseHost(List.of(
                up("docker0", "172.17.0.1"),
                up("eth0", "10.0.5.20"))))
                .hasValue("10.0.5.20");
    }

    @Test
    void refusesToAdvertiseAnAddressNobodyCanDial() {
        // Only a bridge and a tunnel. Answering with either would produce a node that announces
        // successfully and is unreachable — the failure mode this whole class exists to end. Empty
        // sends the caller to loopback, which fails locally and visibly instead.
        assertThat(NetworkAddresses.selectAdvertiseHost(List.of(
                up("docker0", "172.17.0.1"),
                up("tun0", "10.200.1.1"))))
                .isEmpty();
    }

    @Test
    void ignoresInterfacesThatAreDownLoopbackOrSubInterfaces() {
        List<CandidateNic> machine = List.of(
                new CandidateNic("eth0", List.of("192.168.1.10"), false, false, false),
                new CandidateNic("lo", List.of("127.0.0.1"), true, true, false),
                new CandidateNic("eth1:1", List.of("192.168.1.11"), true, false, true),
                up("eth2", "192.168.1.12"));

        assertThat(NetworkAddresses.selectAdvertiseHost(machine)).hasValue("192.168.1.12");
    }

    @Test
    void ignoresPublicAndLinkLocalAddresses() {
        assertThat(NetworkAddresses.selectAdvertiseHost(List.of(
                up("eth0", "8.8.8.8"),
                up("eth1", "169.254.10.1"),
                up("eth2", "172.20.3.4"))))
                .hasValue("172.20.3.4");
    }

    @Test
    void anUnnamedButOrdinaryInterfaceIsStillUsable() {
        // Not a recognised NIC family and not a refused one — better to use it than to give up.
        assertThat(NetworkAddresses.selectAdvertiseHost(List.of(up("net0", "192.168.9.9"))))
                .hasValue("192.168.9.9");
    }

    @Test
    void anExplicitAddressIsNeverSecondGuessed() {
        assertThat(NetworkAddresses.resolveHost("203.0.113.7")).isEqualTo("203.0.113.7");
    }

    @Test
    void autoAndBlankBothMeanDetect() {
        // Whatever this machine has, the contract is the same: a usable address or loopback, never
        // null and never an exception.
        assertThat(NetworkAddresses.resolveHost("auto")).isNotBlank();
        assertThat(NetworkAddresses.resolveHost("")).isNotBlank();
        assertThat(NetworkAddresses.resolveHost(null)).isNotBlank();
    }
}
