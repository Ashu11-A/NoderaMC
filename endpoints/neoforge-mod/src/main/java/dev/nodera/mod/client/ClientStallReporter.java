package dev.nodera.mod.client;

import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Says what the client is <b>sitting on</b> when it is not in a world.
 *
 * <p>Written for a specific, repeated failure: the scripted live suites (L-45) fail in CI with
 * "the player never appeared on the server", and the client's own log is no help — it boots fine,
 * loads its resources, ticks its game loop for the full timeout, and simply never connects. A
 * thread dump proves the loop is alive; it does not say which screen the loop is drawing. Without
 * that one fact every failure costs another 30-minute CI round trip to guess at.
 *
 * <p>So: once the client has been outside a world for {@link #FIRST_REPORT_TICKS}, log the active
 * screen's class and keep logging it on a slow cadence while nothing changes. Quiet by
 * construction — a client that reaches a world within the first ten seconds never logs anything,
 * a screen change resets the cadence, and the interval is minutes rather than seconds, so this
 * costs a normal session nothing.
 *
 * @Thread-context client tick thread only.
 */
public final class ClientStallReporter {

    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger("NoderaClient");

    /** How long the client may sit outside a world before the first report (10 s). */
    static final int FIRST_REPORT_TICKS = 200;

    /** Cadence of subsequent reports while the same screen persists (30 s). */
    static final int REPEAT_TICKS = 600;

    private static int ticksOutsideWorld;
    private static String lastScreen = "";
    private static int ticksSinceReport;

    private ClientStallReporter() {
    }

    /** Attach to the client tick. */
    public static void register() {
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(ClientStallReporter::onTick);
    }

    private static void onTick(ClientTickEvent.Post event) {
        Minecraft client = Minecraft.getInstance();
        if (client.level != null) {
            // In a world: nothing to explain, and the counters reset so a later disconnect gets
            // its own fresh report rather than an immediate one from stale state.
            ticksOutsideWorld = 0;
            ticksSinceReport = 0;
            lastScreen = "";
            return;
        }
        ticksOutsideWorld++;
        if (ticksOutsideWorld < FIRST_REPORT_TICKS) {
            return;
        }
        String screen = describe(client);
        ticksSinceReport++;
        if (!screen.equals(lastScreen)) {
            // A screen change is the interesting event — report it immediately, whatever the
            // cadence, because the transition is usually the answer.
            lastScreen = screen;
            ticksSinceReport = 0;
            LOG.info("Nodera: client not in a world after {}s — screen: {}",
                    ticksOutsideWorld / 20, screen);
            return;
        }
        if (ticksSinceReport >= REPEAT_TICKS) {
            ticksSinceReport = 0;
            LOG.info("Nodera: client STILL not in a world after {}s — screen: {}",
                    ticksOutsideWorld / 20, screen);
        }
    }

    /**
     * A human-readable name for the active screen.
     *
     * @param client the client.
     * @return the screen's simple class name, or {@code "<none>"} when no screen is open (the
     *         in-world case, which the caller has already excluded, or a frame between screens).
     */
    static String describe(Minecraft client) {
        var screen = client.screen;
        if (screen == null) {
            return "<none>";
        }
        String name = screen.getClass().getSimpleName();
        return name.isEmpty() ? screen.getClass().getName() : name;
    }
}
