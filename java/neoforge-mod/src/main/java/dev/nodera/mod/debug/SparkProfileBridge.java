package dev.nodera.mod.debug;

import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Drives the spark profiler on the CLIENT, so a player-hosted world can be profiled.
 *
 * <p><b>Why this exists.</b> The scripted suites profile a server by sending {@code /spark …}
 * over RCON. A player-hosted world has no RCON: the integrated server lives inside the client
 * JVM, and spark registers its client command tree as {@code sparkc} through
 * {@code RegisterClientCommandsEvent}, reachable only from the chat box. That leaves the single
 * most important configuration in Nodera — the one where a player hosts and most of the mod's
 * server-side logic actually runs — the only one nothing could measure. The mod is already
 * inside that JVM, so the mod dispatches the commands.
 *
 * <p><b>Inert by default.</b> Nothing happens unless {@code -Dnodera.spark.profile=<seconds>} is
 * set, which the e2e harness passes through {@code NODERA_SPARK_PROFILE}. This is a diagnostic
 * lane, not a feature: an ordinary game never reads the property, never schedules a tick check
 * that does anything, and never dispatches a command.
 *
 * <p><b>Every entry point catches {@link Throwable}.</b> NeoForge's EventBus does not isolate
 * listener exceptions — an unguarded throw from a client event handler takes the game down with
 * it. A profiler bridge must never be able to do that, and the most likely failure here is the
 * mundane one: spark is not installed, so {@code sparkc} is an unknown command.
 *
 * <p>Documented in {@code docs/minecraft/spark/11-nodera-neoforge.md}.
 */
@ApiStatus.Internal
public final class SparkProfileBridge {

    private static final Logger LOG = LoggerFactory.getLogger("NoderaSpark");

    /** Seconds to profile for; {@code <= 0} disables the bridge entirely. */
    public static final String PROPERTY_SECONDS = "nodera.spark.profile";
    /** Optional: only record ticks longer than this many milliseconds. */
    public static final String PROPERTY_TICKS_OVER = "nodera.spark.ticksOver";

    /** Client ticks per second — the unit the countdown is actually measured in. */
    static final int TICKS_PER_SECOND = 20;

    /**
     * Ticks to wait after login before starting. A capture that begins while the world is still
     * loading chunks profiles world loading, which is real work but not the work anyone asked
     * about, and it drowns the steady-state cost we are trying to see.
     */
    static final int WARMUP_TICKS = 5 * TICKS_PER_SECOND;

    private static final Countdown COUNTDOWN = new Countdown();

    private SparkProfileBridge() {
    }

    /**
     * Register the bridge's listeners. Safe to call when the bridge is disabled — it returns
     * without subscribing anything, so a normal game carries no per-tick cost at all.
     *
     * @return {@code true} if the bridge armed itself
     */
    public static boolean register(net.neoforged.bus.api.IEventBus gameBus) {
        try {
            int seconds = configuredSeconds(System.getProperty(PROPERTY_SECONDS));
            if (seconds <= 0) {
                return false;
            }
            COUNTDOWN.arm(seconds * TICKS_PER_SECOND);
            gameBus.addListener(SparkProfileBridge::onLoggingIn);
            gameBus.addListener(SparkProfileBridge::onClientTick);
            LOG.info("spark bridge armed: will profile {}s once the world is loaded", seconds);
            return true;
        } catch (Throwable t) {
            LOG.warn("spark bridge failed to arm; continuing without it", t);
            return false;
        }
    }

    private static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        try {
            COUNTDOWN.onWorldEntered();
        } catch (Throwable t) {
            LOG.warn("spark bridge: login hook failed", t);
        }
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        try {
            switch (COUNTDOWN.tick()) {
                case START -> dispatch(startCommand(System.getProperty(PROPERTY_TICKS_OVER)));
                case STOP -> dispatch("sparkc profiler stop --save-to-file --comment nodera/client");
                case NOTHING -> { }
            }
        } catch (Throwable t) {
            // Disarm rather than log once per tick forever.
            COUNTDOWN.disarm();
            LOG.warn("spark bridge: disarmed after a failure", t);
        }
    }

    /**
     * Send a client command. spark's client tree is registered through
     * {@code RegisterClientCommandsEvent}, and NeoForge intercepts those in
     * {@code ClientPacketListener#sendCommand} before anything reaches the wire — so this
     * executes locally against spark rather than being forwarded to the server.
     */
    private static void dispatch(String command) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.player.connection == null) {
            LOG.warn("spark bridge: no player connection; skipping '{}'", command);
            return;
        }
        LOG.info("spark bridge: /{}", command);
        minecraft.player.connection.sendCommand(command);
    }

    // -- pure logic, unit-tested ------------------------------------------------------------

    /**
     * Parse the seconds property. Anything unparseable is treated as "off" rather than as an
     * error: a mistyped diagnostic flag must not stop a game from starting.
     */
    static int configuredSeconds(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(raw.trim()));
        } catch (NumberFormatException e) {
            LOG.warn("ignoring -D{}={} — not a number of seconds", PROPERTY_SECONDS, raw);
            return 0;
        }
    }

    /** Build the start command, honouring an optional lag-spike threshold. */
    static String startCommand(@Nullable String ticksOver) {
        // --thread * for the same reason the shell harness uses it: the platform default
        // dumper has been observed to produce a capture containing no threads at all, and a
        // client's integrated server does its work on more than one thread regardless.
        StringBuilder sb = new StringBuilder("sparkc profiler start --thread * --interval 4");
        if (ticksOver != null && !ticksOver.isBlank()) {
            try {
                int millis = Integer.parseInt(ticksOver.trim());
                if (millis > 0) {
                    sb.append(" --only-ticks-over ").append(millis);
                }
            } catch (NumberFormatException e) {
                LOG.warn("ignoring -D{}={} — not a number of milliseconds", PROPERTY_TICKS_OVER, ticksOver);
            }
        }
        return sb.toString();
    }

    /** What the countdown wants done on a given tick. */
    enum Action { NOTHING, START, STOP }

    /**
     * The capture's clock, counted in client ticks.
     *
     * <p>Separated from every Minecraft type on purpose: the dispatch itself can only be
     * exercised in a live client, but the decision of <em>when</em> to dispatch is ordinary
     * arithmetic and is tested.
     */
    static final class Countdown {
        private int durationTicks;
        private int warmupRemaining;
        private int captureRemaining;
        private boolean inWorld;
        private boolean started;
        private boolean finished;

        void arm(int durationTicks) {
            this.durationTicks = durationTicks;
            this.finished = false;
            this.started = false;
            this.inWorld = false;
        }

        void disarm() {
            this.finished = true;
        }

        void onWorldEntered() {
            if (finished || started) {
                return;
            }
            this.inWorld = true;
            this.warmupRemaining = WARMUP_TICKS;
        }

        Action tick() {
            if (finished || !inWorld) {
                return Action.NOTHING;
            }
            if (!started) {
                if (--warmupRemaining > 0) {
                    return Action.NOTHING;
                }
                started = true;
                captureRemaining = durationTicks;
                return Action.START;
            }
            if (--captureRemaining > 0) {
                return Action.NOTHING;
            }
            finished = true;
            return Action.STOP;
        }
    }
}
