package dev.nodera.peer.discovery;

import dev.nodera.core.identity.NodeIdentity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A configuration push may add trackers; it may not remove the operator's.
 *
 * <p>The companion app sends its whole settings document when it attaches, and {@code
 * network.default_trackers} is a live key, so the worker applied the app's list on the spot — by
 * replacing what it had. A worker started with {@code NODERA_TRACKER_ENDPOINTS} pointing at a LAN
 * tracker therefore lost that tracker the moment an app connected to it.
 *
 * <p>Live consequence, on a two-player dev stack sharing one tracker: both players' workers were
 * moved onto the app's own tracker, each hosted world announced to a service that never answered
 * ("world 'Ashu' is on NO tracker — 0 of 1 answered"), and neither player's Worlds tab listed the
 * other's world. The only node that could still read the directory was the spare worker, the one
 * with no app attached.
 */
final class PinnedTrackerEndpointsTest {

    private static TrackerClient.Endpoint endpoint(String host, int port) {
        return new TrackerClient.Endpoint(host, port, TrackerClient.Transport.TCP);
    }

    private static TrackerClient client(TrackerClient.Endpoint... startup) {
        return new TrackerClient(List.of(startup), NodeIdentity.generate());
    }

    @Test
    @DisplayName("a pushed list is added to the pinned one, never substituted for it")
    void aPushedListCannotDropAPinnedTracker() {
        TrackerClient.Endpoint lan = endpoint("10.0.0.101", 25600);
        TrackerClient.Endpoint official = endpoint("tracker.example", 6969);
        TrackerClient tracker = client(lan);
        tracker.pinEndpoints(List.of(lan));

        tracker.setEndpoints(List.of(official));

        assertThat(tracker.endpoints())
                .as("the app's tracker is used, and the operator's is still there")
                .containsExactlyInAnyOrder(lan, official);
        assertThat(tracker.pinnedEndpoints()).containsExactly(lan);
    }

    @Test
    @DisplayName("an empty push leaves the operator's trackers standing")
    void anEmptyPushDoesNotSilenceTheNode() {
        TrackerClient.Endpoint lan = endpoint("10.0.0.101", 25600);
        TrackerClient tracker = client(lan);
        tracker.pinEndpoints(List.of(lan));

        tracker.setEndpoints(List.of());

        assertThat(tracker.endpoints())
                .as("\"announce nowhere\" is a legitimate app setting, but not for a pinned node")
                .containsExactly(lan);
    }

    @Test
    @DisplayName("with nothing pinned the push still wins outright")
    void anAppLaunchedWorkerIsStillFullyControlledByItsApp() {
        TrackerClient.Endpoint first = endpoint("tracker.example", 6969);
        TrackerClient.Endpoint second = endpoint("other.example", 6969);
        TrackerClient tracker = client(first);

        tracker.setEndpoints(List.of(second));

        assertThat(tracker.endpoints())
                .as("a worker the app launched has no operator list to protect")
                .containsExactly(second);
        assertThat(tracker.pinnedEndpoints()).isEmpty();
    }
}
