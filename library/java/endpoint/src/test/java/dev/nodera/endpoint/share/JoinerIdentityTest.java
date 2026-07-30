package dev.nodera.endpoint.share;

import dev.nodera.endpoint.share.JoinerIdentity;
import dev.nodera.core.Bytes;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The throttle key has one job: stay the same across the thing being throttled. A reconnect changes
 * the connection object and the source port and nothing else, so those are exactly what must not be
 * in the key.
 */
class JoinerIdentityTest {

    @Test
    void theSameAddressOnADifferentPortIsTheSameJoiner() {
        assertThat(JoinerIdentity.of(new InetSocketAddress("203.0.113.7", 40_000)))
                .isEqualTo(JoinerIdentity.of(new InetSocketAddress("203.0.113.7", 51_234)));
    }

    @Test
    void differentAddressesAreDifferentJoiners() {
        assertThat(JoinerIdentity.of(new InetSocketAddress("203.0.113.7", 40_000)))
                .isNotEqualTo(JoinerIdentity.of(new InetSocketAddress("203.0.113.8", 40_000)));
    }

    @Test
    void anUnresolvableHostStillYieldsAPortFreeKeyRatherThanThrowing() {
        Bytes first = JoinerIdentity.of(
                InetSocketAddress.createUnresolved("nowhere.invalid", 25_565));
        Bytes second = JoinerIdentity.of(
                InetSocketAddress.createUnresolved("nowhere.invalid", 51_234));

        assertThat(first).isNotNull().isEqualTo(second);
    }

    @Test
    void noAddressIsNoKeyRatherThanASharedOne() {
        // A shared "unknown" key would let one unattributable joiner lock out every other one.
        assertThat(JoinerIdentity.of(null)).isNull();
    }
}
