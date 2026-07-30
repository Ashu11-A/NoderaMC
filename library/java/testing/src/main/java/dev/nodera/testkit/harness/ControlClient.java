package dev.nodera.testkit.harness;

import dev.nodera.peer.control.CompanionClient;
import java.time.Duration;
import java.util.Optional;

/**
 * A client for a worker's loopback control socket — the wire the companion app and the mod speak.
 *
 * <h2>Why the harness talks the same protocol as the product</h2>
 *
 * <p>Every live assertion about a node's state ("is it seeding?", "does it know this world?", "is
 * it above quorum?") is answered by {@code NODERA-STATE}. Asking through the same socket the Tauri
 * dashboard uses means the test cannot pass on a state the product cannot see: there is no back
 * door, no test-only accessor, and no second serialisation of the truth to drift from the first.
 *
 * <p>Thread-context: stateless; one short-lived socket per call, exactly like the real clients.
 */
public final class ControlClient {

    private final int port;
    private final Duration timeout;

    /**
     * The one control client. This class used to open its own socket and frame its own request —
     * a third implementation of the same one-line-out/one-line-back wire, beside the mod's and the
     * Paper plugin's. What is test-specific here is the WAITING (deadlines, polling, readable
     * failures), not the protocol, so only that is left.
     */
    private final CompanionClient client;

    public ControlClient(int port) {
        this(port, Duration.ofSeconds(30));
    }

    public ControlClient(int port, Duration timeout) {
        this.port = port;
        this.timeout = timeout;
        this.client = new CompanionClient("127.0.0.1", port, (int) timeout.toMillis());
    }

    public int port() {
        return port;
    }

    /**
     * Send one verb and read the single-line reply.
     *
     * @param verb e.g. {@code "NODERA-STATE 2"}.
     * @return the reply, or empty when the worker is not answering (not yet up, or gone).
     */
    public Optional<String> ask(String verb) {
        return Optional.ofNullable(client.exchange(verb));
    }

    /**
     * Send one verb and require a reply.
     *
     * @throws HarnessException if the worker said nothing — a state assertion made against silence
     *                          is the failure mode that made "the feature is broken" mean "the
     *                          process was busy".
     */
    public String require(String verb) {
        return ask(verb).orElseThrow(() -> new HarnessException(
                "the worker on control port " + port + " did not answer '" + verb + "'"));
    }

    /** The worker's whole state document. */
    public String state() {
        return require("NODERA-STATE 2");
    }

    /** @return {@code true} if the worker answers its presence probe. */
    public boolean isUp() {
        return ask("NODERA-PROBE 2").filter(reply -> reply.startsWith("NODERA-OK")).isPresent();
    }

    /**
     * Wait until the worker answers its probe.
     *
     * @throws HarnessException naming the port that stayed silent.
     */
    public void awaitUp(Duration deadline) {
        long limit = System.nanoTime() + deadline.toNanos();
        while (System.nanoTime() < limit) {
            if (isUp()) {
                return;
            }
            Topology.sleep(Duration.ofSeconds(1));
        }
        throw new HarnessException("the worker on control port " + port + " never answered "
                + "NODERA-PROBE within " + deadline.toSeconds() + "s");
    }

    /**
     * Wait until the worker's state contains {@code needle}.
     *
     * @param needle a fragment of the state document, e.g. {@code "\"connected_worlds\":[{"}.
     * @param what   what the caller was proving, for the failure message.
     */
    public void awaitState(String needle, Duration deadline, String what) {
        long limit = System.nanoTime() + deadline.toNanos();
        String last = "<no reply>";
        while (System.nanoTime() < limit) {
            Optional<String> state = ask("NODERA-STATE 2");
            if (state.isPresent()) {
                last = state.get();
                if (last.contains(needle)) {
                    return;
                }
            }
            Topology.sleep(Duration.ofSeconds(2));
        }
        throw new HarnessException(what + " — the worker on control port " + port
                + " never reported '" + needle + "'. Last state: " + abbreviate(last));
    }

    /** The role a test-mode worker was launched with, if it reports one. */
    public Optional<String> role() {
        return ask("NODERA-TEST ROLE").map(String::trim).filter(reply -> !reply.startsWith("NODERA-ERR"));
    }

    private static String abbreviate(String value) {
        return value.length() <= 400 ? value : value.substring(0, 400) + "…";
    }
}
