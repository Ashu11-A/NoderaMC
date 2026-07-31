package dev.nodera.core.services;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The gate on "a shipped build must not dial the machine it is running on".
 *
 * <p>Every Java entry point defaulted to {@code 127.0.0.1:25600} until this class existed, which is
 * invisible in a checkout — the dev stack listens there — and wrong on every player's machine and
 * every phone. These assertions are about the list that gets compiled in, so a pull request that
 * puts a loopback or LAN address into the published {@code index.json} fails here rather than on a
 * user's device.
 */
final class DefaultServicesTest {

    @Test
    @DisplayName("the official list is compiled into the jar and names both kinds of service")
    void theOfficialListIsPresent() {
        assertFalse(DefaultServices.officialTrackerEndpoints().isEmpty(),
                "a build with no tracker to announce to is a build that cannot join a network");
        assertFalse(DefaultServices.officialRendezvousEndpoints().isEmpty(),
                "a build with no relay cannot reach a peer behind NAT");
    }

    @Test
    @DisplayName("no official service is an address only its publisher can reach")
    void noOfficialServiceIsLocal() {
        for (String route : concat(DefaultServices.officialTrackerEndpoints(),
                DefaultServices.officialRendezvousEndpoints())) {
            String host = host(route);
            assertFalse(host.equals("127.0.0.1") || host.equals("localhost") || host.equals("::1"),
                    route + " is loopback — on a player's machine that is their machine, and on a "
                            + "phone it is the phone");
            assertFalse(host.startsWith("10.") || host.startsWith("192.168.")
                            || host.startsWith("172.16."),
                    route + " is a private LAN address, reachable on exactly one network");
        }
    }

    /**
     * A relay route is dialled as a bare {@code host:port}. The published list writes {@code tcp://}
     * because that is the form every other consumer uses, and the mod's own config validator refuses
     * a scheme on a rendezvous route — so the strip has to happen, and it has to happen here.
     */
    @Test
    @DisplayName("rendezvous routes arrive without a scheme")
    void rendezvousRoutesCarryNoScheme() {
        for (String route : DefaultServices.officialRendezvousEndpoints()) {
            assertFalse(route.contains("://"), route + " must be a bare host:port");
        }
    }

    @Test
    @DisplayName("development mode returns the localhost stack instead")
    void developmentModeIsTheOnlyWayToGetLoopback() {
        // The property is read live rather than cached, so this test states the rule without having
        // to mutate the environment of a running JVM: whichever mode this run is in, the answer is
        // one of the two lists and never a mixture.
        List<String> trackers = DefaultServices.trackerEndpoints();
        if (DefaultServices.developmentMode()) {
            assertTrue(trackers.contains(DefaultServices.DEVELOPMENT_TRACKER));
        } else {
            assertTrue(trackers.equals(DefaultServices.officialTrackerEndpoints()),
                    "a production run defaults to the official list and to nothing else");
        }
    }

    @Test
    @DisplayName("the joined form is what a NODERA_*_ENDPOINTS variable looks like")
    void routesJoinWithCommas() {
        assertTrue(DefaultServices.joined(List.of("a:1", "b:2")).equals("a:1,b:2"));
    }

    private static List<String> concat(List<String> first, List<String> second) {
        List<String> all = new java.util.ArrayList<>(first);
        all.addAll(second);
        return all;
    }

    private static String host(String route) {
        String address = route.contains("://") ? route.substring(route.indexOf("://") + 3) : route;
        int colon = address.lastIndexOf(':');
        return colon < 0 ? address : address.substring(0, colon);
    }
}
