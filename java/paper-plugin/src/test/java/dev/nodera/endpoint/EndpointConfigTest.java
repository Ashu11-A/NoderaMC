package dev.nodera.endpoint;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The endpoint's configuration contract. The file under test is the one
 * `scripts/lib/e2e-server.sh` actually writes, so a change to either side that the other does not
 * expect fails here rather than on a server somebody is watching.
 */
class EndpointConfigTest {

    /** Byte-for-byte the shape the harness stages, with the ports it uses. */
    private static final String HARNESS_FILE = """
            peer:
              mode: embedded
              control-port: 25610
              identity-file: identity.bin
              p2p:
                bind: 127.0.0.1
                port: 25620
                advertise: 127.0.0.1
            world:
              id: "cafebabe"
              custody: FULL
              password: "hunter2"
              listed: true
            discovery:
              trackers: ["127.0.0.1:25600"]
              rendezvous: ["127.0.0.1:25601"]
            lane:
              entity-capture: true
              mob-capture-dimensions: ["minecraft:overworld"]
              archive-stream-interval-ticks: 2400
            mods:
              required: []
              allowed: []
            """;

    @Test
    @DisplayName("the file the harness writes parses into what it says")
    void theHarnessFileIsUnderstoodExactly() {
        EndpointConfig config = EndpointConfig.parse(HARNESS_FILE);

        assertThat(config.peerMode()).isEqualTo(EndpointConfig.PeerMode.EMBEDDED);
        assertThat(config.controlPort()).isEqualTo(25610);
        assertThat(config.p2pBind()).isEqualTo("127.0.0.1");
        assertThat(config.p2pPort()).isEqualTo(25620);
        assertThat(config.p2pAdvertise()).isEqualTo("127.0.0.1");
        assertThat(config.worldId()).isEqualTo("cafebabe");
        assertThat(config.custody()).isEqualTo(EndpointConfig.Custody.FULL);
        assertThat(config.listed()).isTrue();
        assertThat(config.trackers()).containsExactly("127.0.0.1:25600");
        assertThat(config.rendezvous()).containsExactly("127.0.0.1:25601");
        assertThat(config.entityCapture()).isTrue();
        assertThat(config.mobCaptureDimensions()).containsExactly("minecraft:overworld");
        assertThat(config.problems()).isEmpty();
    }

    @Test
    @DisplayName("an empty file is every default, and every default is usable")
    void omissionsTakeDefaults() {
        EndpointConfig config = EndpointConfig.parse("");

        assertThat(config.peerMode()).isEqualTo(EndpointConfig.PeerMode.EMBEDDED);
        assertThat(config.controlPort()).isEqualTo(EndpointConfig.DEFAULT_CONTROL_PORT);
        assertThat(config.p2pPort()).isEqualTo(EndpointConfig.DEFAULT_P2P_PORT);
        assertThat(config.custody()).isEqualTo(EndpointConfig.Custody.VIEW);
        assertThat(config.listed()).isFalse();
        assertThat(config.problems())
                .as("a file that says nothing must not be a file that refuses to start")
                .isEmpty();
        assertThat(EndpointConfig.parse(null).problems()).isEmpty();
    }

    @Test
    @DisplayName("full custody with no world id is fine until the world is also announced")
    void fullCustodyOnlyNeedsAnIdWhenListed() {
        // An endpoint is routinely configured before its world has an id — the host flow assigns
        // one from the certified genesis. The harness stages exactly this shape, and an earlier,
        // stricter rule refused to enable against it on a real Paper server.
        assertThat(EndpointConfig.parse("""
                world:
                  custody: FULL
                """).problems()).isEmpty();

        EndpointConfig announcing = EndpointConfig.parse("""
                world:
                  custody: FULL
                  listed: true
                discovery:
                  trackers: ["127.0.0.1:25600"]
                """);

        assertThat(announcing.problems()).hasSize(1);
        EndpointConfig.Problem problem = announcing.problems().get(0);
        assertThat(problem.key()).isEqualTo("world.id");
        assertThat(problem.message()).contains("Set world.id").contains("world.listed: false");
    }

    @Test
    @DisplayName("asking to be listed with nowhere to announce is refused")
    void listedNeedsATracker() {
        EndpointConfig config = EndpointConfig.parse("""
                world:
                  id: "abc"
                  listed: true
                """);

        assertThat(config.problems()).singleElement()
                .satisfies(p -> assertThat(p.key()).isEqualTo("discovery.trackers"));
    }

    @Test
    @DisplayName("one port cannot be both sockets")
    void controlAndP2pPortsMustDiffer() {
        EndpointConfig config = EndpointConfig.parse("""
                peer:
                  control-port: 25610
                  p2p:
                    port: 25610
                """);

        assertThat(config.problems()).singleElement()
                .satisfies(p -> assertThat(p.key()).isEqualTo("peer.p2p.port"));
    }

    @Test
    @DisplayName("a port that is not a number is reported, not thrown")
    void malformedNumbersBecomeProblems() {
        EndpointConfig config = EndpointConfig.parse("""
                peer:
                  control-port: "twenty-five thousand"
                """);

        assertThat(config.problems()).isNotEmpty();
        assertThat(config.problems().get(0).key()).isEqualTo("peer.control-port");
        // The point is that parsing did not throw during enable: an operator gets a message about
        // their file, not a stack trace in a log they did not write.
    }

    @Test
    @DisplayName("a comment is a comment, but a hash inside a quoted password is not")
    void commentsDoNotEatValues() {
        EndpointConfig config = EndpointConfig.parse("""
                # the whole line is a comment
                peer:
                  mode: external   # trailing comment
                world:
                  id: "abc#123"
                """);

        assertThat(config.peerMode()).isEqualTo(EndpointConfig.PeerMode.EXTERNAL);
        assertThat(config.worldId())
                .as("a password or id containing '#' must survive comment stripping")
                .isEqualTo("abc#123");
    }

    @Test
    @DisplayName("unknown keys are ignored so a newer file does not stop an older plugin")
    void forwardCompatibility() {
        EndpointConfig config = EndpointConfig.parse("""
                peer:
                  mode: external
                  future-setting: 42
                something-entirely-new:
                  nested: true
                """);

        assertThat(config.peerMode()).isEqualTo(EndpointConfig.PeerMode.EXTERNAL);
        assertThat(config.problems()).isEmpty();
    }

    @Test
    @DisplayName("an empty inline list is empty, not a list containing nothing-shaped strings")
    void emptyListsParseCleanly() {
        EndpointConfig config = EndpointConfig.parse("""
                discovery:
                  trackers: []
                  rendezvous: [ ]
                """);

        assertThat(config.trackers()).isEmpty();
        assertThat(config.rendezvous()).isEmpty();
    }
}
