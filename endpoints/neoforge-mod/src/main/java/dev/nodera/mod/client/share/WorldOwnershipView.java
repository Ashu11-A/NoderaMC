package dev.nodera.mod.client.share;

import dev.nodera.endpoint.state.WorkerStateParser;
import dev.nodera.mod.common.NoderaPeerService;
import dev.nodera.peer.control.CompanionLink;

import java.util.concurrent.atomic.AtomicLong;

/**
 * "Do I own the world I am in?", answered cheaply enough to gate a button.
 *
 * <h2>Why this had to exist</h2>
 *
 * <p>Nothing on the client could answer it. The pause menu's share button was gated on
 * {@code mc.hasSingleplayerServer()} alone, which asks whether the server is in this JVM — a
 * different question, and one that is <b>true</b> for a player who has just recovered somebody
 * else's world onto their own machine. Those players were handed the owner's control panel: the
 * share options, the password field and the delete button for a world they did not author.
 *
 * <p>The worker has always known the answer and has always sent it — {@code owned}, per world, in
 * every {@code NODERA-STATE} reply. It died in {@code WorkerStateParser}, which parsed five of the
 * twenty-two fields on the wire and dropped this one.
 *
 * <p>The other candidate, {@code NoderaHost.localWorkerIsAuthor}, is authoritative but does file I/O
 * and a blocking control exchange — not something to call from a screen's render or init. This
 * caches the worker's answer for a second, which is the right shape for a button.
 *
 * @Thread-context client thread; the control exchange is bounded and the result is cached.
 */
public final class WorldOwnershipView {

    /** How long an answer is reused. Long enough for a screen build, short enough to be current. */
    private static final long CACHE_MILLIS = 1_000L;

    private static final AtomicLong CHECKED_AT = new AtomicLong();
    private static volatile String cachedWorldId = "";
    private static volatile boolean cachedOwned;

    private WorldOwnershipView() {
    }

    /**
     * Whether this installation owns the world this client is currently in.
     *
     * @return {@code false} when there is no world, no worker, or no answer. Not knowing must never
     *         read as owning — the cost of a wrong {@code true} is a non-author editing somebody
     *         else's world's share settings, and the cost of a wrong {@code false} is a button the
     *         owner has to reach a moment later.
     * @Thread-context client thread.
     */
    public static boolean ownsCurrentWorld() {
        String worldId = NoderaPeerService.get().currentWorldIdHex();
        if (worldId == null || worldId.isBlank() || !CompanionLink.isPresent()) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (worldId.equals(cachedWorldId) && now - CHECKED_AT.get() < CACHE_MILLIS) {
            return cachedOwned;
        }
        boolean owned = false;
        try {
            String state = CompanionLink.client().state().orElse("");
            for (WorkerStateParser.HostedWorldInfo info : WorkerStateParser.connectedWorlds(state)) {
                if (worldId.equalsIgnoreCase(info.worldId())) {
                    owned = info.owned();
                    break;
                }
            }
        } catch (RuntimeException unreachable) {
            owned = false;
        }
        cachedWorldId = worldId;
        cachedOwned = owned;
        CHECKED_AT.set(now);
        return owned;
    }

    /**
     * Whether this client may offer world-sharing controls at all.
     *
     * <p>Two cases, and only two. A world that has never been shared has no owner yet, so the player
     * running it is about to become one — that is how a world gets shared in the first place. A world
     * that <i>is</i> on the network is only theirs to configure if the worker says they own it.
     *
     * @param sharedAlready whether this world is currently on the network.
     * @return whether to show the share controls.
     * @Thread-context client thread.
     */
    public static boolean mayShare(boolean sharedAlready) {
        return !sharedAlready || ownsCurrentWorld();
    }
}
