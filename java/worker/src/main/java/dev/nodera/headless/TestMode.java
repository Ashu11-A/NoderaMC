package dev.nodera.headless;

import dev.nodera.peer.control.WorkerEvent;
import dev.nodera.peer.control.WorkerEventBus;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The worker's integration-run mode: {@code nodera-headless --test-mode --role player1}.
 *
 * <h2>Why a worker needs a mode at all</h2>
 *
 * <p>An integration run is a claim about two nodes: "player one broke a block, and player two's node
 * independently validated it". Until the run could name the two nodes, that claim was made by
 * remembering which control port had been started first — and a port number is not an identity. Give
 * the port plan a different base, or start the spare peer first, and every cross-node assertion
 * silently swapped its subjects while continuing to pass.
 *
 * <p>So a worker in a run is told which player it belongs to. The role is reported back over
 * {@link dev.nodera.peer.control.ControlProtocol#TEST}, which lets the harness say
 * {@code stack.worker(PLAYER_TWO)} and get the right node however the ports are numbered.
 *
 * <h2>Why it is a command-line flag and nothing else</h2>
 *
 * <p>Test mode adds a remote-control surface: {@code DRIVE} publishes actions for the attached game
 * to execute. A production node must not be able to grow that surface, so it is enabled only by an
 * argument on a process somebody deliberately started — not by a config file another program can
 * write, not by an environment variable inherited from a parent, and not by a peer. A worker started
 * without the flag answers {@code NODERA-ERR unsupported} to every test verb, which is the same
 * answer it gives to a verb that does not exist.
 *
 * <h2>What DRIVE does, and what it deliberately does not</h2>
 *
 * <p>The worker never touches the game. It publishes the action on the same
 * {@link WorkerEventBus} the mod already subscribes to over {@code NODERA-EVENTS}, so a driven
 * action travels the production path — worker to mod, over the existing wire, into the existing
 * handler. A test-only side channel into the game would prove that the side channel works.
 *
 * <p>Thread-context: immutable configuration; {@link #handle} is called on control-connection
 * threads and only touches the thread-safe event bus.
 */
public final class TestMode {

    /** The event name a driven action is published under; the mod matches on it. */
    public static final String DRIVE_EVENT = "test.drive";

    private static final TestMode DISABLED = new TestMode(false, "spare", false);

    private final boolean enabled;
    private final String role;
    private final boolean debug;
    private volatile WorkerEventBus events;

    private TestMode(boolean enabled, String role, boolean debug) {
        this.enabled = enabled;
        this.role = role;
        this.debug = debug;
    }

    /**
     * Parse the worker's command line.
     *
     * <p>Unknown arguments are an error rather than a shrug: a typo in {@code --role} that started a
     * healthy-looking worker answering as the wrong player is precisely the failure this whole
     * mechanism exists to prevent.
     *
     * <pre>
     *   --test-mode            answer the integration verbs (off by default)
     *   --role &lt;name&gt;    player1 | player2 | player3 | spare   (implies --test-mode)
     *   --debug                verbose worker logging for the run
     *   --help                 usage
     * </pre>
     *
     * @throws IllegalArgumentException on an unknown argument or an unknown role.
     */
    public static TestMode fromArgs(String[] args) {
        boolean enabled = false;
        boolean debug = false;
        String role = "spare";
        // A set rather than a list: the membership test sits inside the argument loop, and a
        // linear scan inside a loop is the shape the structural report calls quadratic — correctly,
        // even when n is four. Cheap to write the way that stays right if the list grows.
        Set<String> known = new LinkedHashSet<>(List.of("player1", "player2", "player3", "spare"));
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "--test-mode" -> enabled = true;
                case "--debug" -> debug = true;
                case "--role" -> {
                    if (i + 1 >= args.length) {
                        throw new IllegalArgumentException("--role needs a value: " + known);
                    }
                    role = args[++i].trim().toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
                    if (!known.contains(role)) {
                        throw new IllegalArgumentException("unknown --role '" + role + "': " + known);
                    }
                    enabled = true;
                }
                case "--help", "-h" -> throw new IllegalArgumentException(usage());
                default -> throw new IllegalArgumentException(
                        "unknown argument '" + arg + "'.\n" + usage());
            }
        }
        return enabled ? new TestMode(true, role, debug) : DISABLED;
    }

    /** The usage text, also printed for {@code --help}. */
    public static String usage() {
        return """
                nodera-headless [--test-mode] [--role player1|player2|player3|spare] [--debug]

                  --test-mode   answer NODERA-TEST (ROLE / READY / DRIVE) for an integration run.
                                Off by default: a production node has no remote-control surface.
                  --role NAME   which player this node belongs to. Implies --test-mode.
                  --debug       verbose logging for the run.

                Everything else about a worker is configured through the environment
                (NODERA_CONTROL_PORT, NODERA_P2P_PORT, NODERA_STATE_DIR, …).""";
    }

    /** Attach the event stream a {@code DRIVE} action is published on. */
    public void bind(WorkerEventBus bus) {
        this.events = bus;
    }

    /** @return {@code true} if this worker is part of an integration run. */
    public boolean enabled() {
        return enabled;
    }

    /** The role name as it appears on the command line and in the state document. */
    public String role() {
        return role;
    }

    /** @return {@code true} if the run asked for verbose logging. */
    public boolean debug() {
        return debug;
    }

    /**
     * Answer one {@link dev.nodera.peer.control.ControlProtocol#TEST} request.
     *
     * @param action {@code ROLE}, {@code READY} or {@code DRIVE} (upper-cased by the server).
     * @param rest   the remainder of the line.
     * @return the reply line, or {@code null} when this worker is not in test mode.
     */
    public String handle(String action, String rest) {
        if (!enabled) {
            return null;
        }
        return switch (action) {
            case "ROLE" -> "NODERA-OK " + role;
            case "READY" -> "NODERA-OK ready " + role;
            case "DRIVE" -> drive(rest);
            default -> "NODERA-ERR unknown test action '" + action + "' (ROLE|READY|DRIVE)";
        };
    }

    /**
     * Publish an action for this role's player.
     *
     * <p>Declining an empty action rather than publishing it is the difference between a scenario
     * that fails with "the drive was empty" and one that waits five minutes for a player to do
     * nothing.
     */
    private String drive(String action) {
        if (action == null || action.isBlank()) {
            return "NODERA-ERR drive needs an action";
        }
        WorkerEventBus bus = events;
        if (bus == null) {
            return "NODERA-ERR this worker has no event stream to drive through";
        }
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("role", role);
        attributes.put("action", action);
        long seq = bus.publish(new WorkerEvent(DRIVE_EVENT, System.currentTimeMillis(), attributes));
        return "NODERA-OK " + seq;
    }

    /** The fragment the worker's state document carries so a run can see the role it reports. */
    public String stateFragment() {
        return enabled ? "\"test_role\":\"" + role + "\"" : "";
    }
}
