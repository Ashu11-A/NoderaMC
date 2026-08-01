package dev.nodera.core.services;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The gate on "a shipped build must not dial the machine it is running on".
 *
 * <p>Every Java entry point defaulted to {@code 127.0.0.1:25600} until this class existed, which is
 * invisible in a checkout — the dev stack listens there — and wrong on every player's machine and
 * every phone. These assertions are about the list that gets compiled in, so a pull request that
 * puts a loopback or LAN address into the published {@code index.json} fails here rather than on a
 * user's device.
 *
 * <p>The list is read from the <em>generated resource</em> rather than through an accessor on
 * {@link DefaultServices}. Two reasons, and the second is the important one. It is mode-independent,
 * so this gate bites the same whether or not the run has {@code NODERA_DEV} set. And it needs no
 * production API that only a test would ever call: this class used to reach for
 * {@code officialTrackerEndpoints()}, which nothing shipped ever called, and the structural report
 * was right to name it dead. A test that has to grow the production surface to observe something is
 * usually observing the wrong thing — here, what actually matters is the file in the jar.
 */
final class DefaultServicesTest {

    /** The resource the {@code generateOfficialServices} task writes. */
    private static final String RESOURCE = "/dev/nodera/core/official-services.list";

    @Test
    @DisplayName("the official list is compiled into the jar and names both kinds of service")
    void theOfficialListIsPresent() {
        assertFalse(compiled("tracker").isEmpty(),
                "a build with no tracker to announce to is a build that cannot join a network");
        assertFalse(compiled("rendezvous").isEmpty(),
                "a build with no relay cannot reach a peer behind NAT");
    }

    @Test
    @DisplayName("no official service is an address only its publisher can reach")
    void noOfficialServiceIsLocal() {
        List<String> routes = new ArrayList<>(compiled("tracker"));
        routes.addAll(compiled("rendezvous"));
        for (String route : routes) {
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
     * a scheme on a rendezvous route — so the strip has to happen, and it has to happen in
     * {@link DefaultServices}.
     *
     * <p>Asserted through the accessor, not the resource: the stripping is the behaviour under test,
     * and the resource is its input. It holds in either mode — the development relay carries no
     * scheme either — so there is nothing to guard.
     */
    @Test
    @DisplayName("rendezvous routes arrive without a scheme")
    void rendezvousRoutesCarryNoScheme() {
        for (String route : DefaultServices.rendezvousEndpoints()) {
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
            assertTrue(trackers.equals(compiled("tracker")),
                    "a production run defaults to the compiled list and to nothing else");
        }
    }

    @Test
    @DisplayName("the joined form is what a NODERA_*_ENDPOINTS variable looks like")
    void routesJoinWithCommas() {
        assertTrue(DefaultServices.joined(List.of("a:1", "b:2")).equals("a:1,b:2"));
    }

    /**
     * The routes of one kind, read straight out of the generated resource.
     *
     * <p>Deliberately a second, dumber parser than the one in {@link DefaultServices}: if both sides
     * of this gate shared an implementation, a parsing bug would agree with itself and the test
     * would pass on a jar that ships nothing.
     *
     * <p>Returned exactly as written, scheme and all. {@link DefaultServices} strips {@code tcp://}
     * from relay routes and leaves it on tracker routes, and reproducing that here would make the
     * comparison in {@code developmentModeIsTheOnlyWayToGetLoopback} test nothing.
     *
     * @param kind {@code tracker} or {@code rendezvous}.
     * @return the routes, exactly as written in the file.
     */
    private static List<String> compiled(String kind) {
        List<String> routes = new ArrayList<>();
        try (InputStream in = DefaultServicesTest.class.getResourceAsStream(RESOURCE)) {
            assertNotNull(in, RESOURCE + " is missing — the generateOfficialServices task did not "
                    + "run, so this build has no services at all");
            try (BufferedReader reader =
                    new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                        continue;
                    }
                    int space = trimmed.indexOf(' ');
                    if (space > 0 && trimmed.substring(0, space).equals(kind)) {
                        routes.add(trimmed.substring(space + 1).trim());
                    }
                }
            }
        } catch (Exception e) {
            throw new AssertionError("cannot read " + RESOURCE, e);
        }
        return routes;
    }

    private static String host(String route) {
        String address = route.contains("://") ? route.substring(route.indexOf("://") + 3) : route;
        int colon = address.lastIndexOf(':');
        return colon < 0 ? address : address.substring(0, colon);
    }
}
