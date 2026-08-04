package dev.nodera.mod.client.multiplayer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.ProgressScreen;
import net.minecraft.client.gui.screens.ReceivingLevelScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Losing the host stops being an event the player has to look at.
 *
 * <h2>What this replaces, and why</h2>
 *
 * <p>There used to be a screen. A host closing their game put every other player in front of
 * "Migrating world…" for as long as the archive took to fetch, with a cancel button and, if it went
 * wrong, an error. It was a good screen. It should not exist, because <b>the player should not be
 * taken out of the world at all</b>: they are standing on ground their own client has already
 * loaded, holding an archive their own worker has already fetched, and the only thing that actually
 * stopped was a TCP connection to somebody else's process.
 *
 * <p>So the disconnect is intercepted before vanilla acts on it. Vanilla's
 * {@code onDisconnect} calls {@code Minecraft.disconnect}, which tears the level down and then shows
 * the screen — in that order, which is why nothing downstream of it can keep the player in the
 * world. {@link #begin} is called at the head of that method and, when this class can genuinely
 * take responsibility, cancels it: the level is never torn down, the screen is never built, and the
 * chunks the client already had keep rendering as <b>ghost chunks</b> while the world is re-opened
 * locally underneath them.
 *
 * <h2>The bar for taking over</h2>
 *
 * <p>{@link #begin} returns {@code false} unless every one of these holds, and vanilla then behaves
 * exactly as it always has:
 *
 * <ul>
 *   <li>this session was a Nodera join that armed continuity — a vanilla server going down is not
 *       ours to take over;</li>
 *   <li>this process is not itself the host — a host "recovering" its own world reopens it and
 *       kicks everyone, which was observed live as a reopen/kick loop;</li>
 *   <li>the player was not refused at the password gate — a refusal produces the same disconnect a
 *       crash does, and swallowing it means the password prompt never appears;</li>
 *   <li>a worker is present to open the world from.</li>
 * </ul>
 *
 * <p>Cancelling vanilla's disconnect is a serious thing to do, so the rule is that we only cancel
 * when we have somewhere to put the player. Anything else falls through.
 *
 * @Thread-context {@link #begin} runs on whichever thread handled the disconnect; the work it
 *                 schedules runs on a background thread and marshals to the client thread.
 */
public final class SeamlessTakeover {

    private static final Logger LOG = LoggerFactory.getLogger("NoderaContinuity");

    /** True from the moment a takeover is accepted until the local world is open. */
    private static final AtomicBoolean ACTIVE = new AtomicBoolean();

    private SeamlessTakeover() {
    }

    /** @return whether a takeover is running right now. */
    public static boolean active() {
        return ACTIVE.get();
    }

    /**
     * Decide whether Nodera is taking responsibility for this disconnect.
     *
     * @return {@code true} when the caller must not run vanilla's disconnect.
     * @Thread-context any thread.
     */
    public static boolean begin() {
        if (!NoderaContinuity.canTakeOver()) {
            return false;
        }
        if (!ACTIVE.compareAndSet(false, true)) {
            // Already taking over. Still cancel: letting a second disconnect through would tear
            // down the level the first one is keeping alive.
            return true;
        }
        // Exactly one peer opens the world. Every other survivor holds its ghost and waits for the
        // successor's endpoint to appear, then reconnects — one reconnect instead of a fork into N
        // divergent copies of the same world id.
        if (!NoderaContinuity.isElectedSuccessor()) {
            LOG.info("Nodera: the host's connection ended — another peer is taking the world over; "
                    + "holding here and waiting to reconnect to it.");
            NoderaContinuity.awaitSuccessor();
            return true;
        }
        LOG.info("Nodera: the host's connection ended — this peer was elected to take the world "
                + "over. Re-opening it locally.");
        NoderaContinuity.takeOverLocally();
        return true;
    }

    /** The takeover is over, one way or the other. */
    static void finish() {
        ACTIVE.set(false);
    }

    /**
     * Whether a screen about to open should be suppressed because a takeover is running.
     *
     * <p>These are the four screens the local re-open puts in front of the player, and every one of
     * them is an answer to a question the player did not ask. Suppressing a screen leaves whatever
     * was on screen before it — which, for the duration of a takeover, is the world.
     *
     * <p>Scoped to a running takeover on purpose: outside one, all four of these screens are doing
     * their job and must appear. A mod that hides {@link DisconnectedScreen} unconditionally is a
     * mod that hides why a player was kicked.
     *
     * @param screen the screen about to be shown.
     * @return whether to suppress it.
     * @Thread-context client thread.
     */
    public static boolean shouldSuppress(Screen screen) {
        if (!ACTIVE.get() || screen == null) {
            return false;
        }
        return screen instanceof DisconnectedScreen
                || screen instanceof ReceivingLevelScreen
                || screen instanceof ProgressScreen
                || screen instanceof GenericMessageScreen
                // Vanilla's world-GENERATION screen — the chunk-status grid with a percentage.
                // It was missing from this list, and it is the one the player actually reported
                // seeing: the local re-open runs the same load sequence a brand-new world does.
                || screen instanceof net.minecraft.client.gui.screens.LevelLoadingScreen;
    }

    /**
     * Tell the player what is happening, in the world, without interrupting them.
     *
     * @param message the line to show.
     * @Thread-context any thread; hops to the client thread.
     */
    static void say(Component message) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.player != null) {
                mc.player.displayClientMessage(message, false);
            }
        });
    }
}
