package dev.nodera.peer.view;

import dev.nodera.core.action.ActionEnvelope;

import java.util.function.Supplier;

/**
 * The seam that feeds locally-captured actions into the local-replica view (L-16).
 *
 * <p>{@link LocalReplicaView} has been complete and tested since Task 16a, and **nothing called
 * {@code predict}**. That is the whole of what L-16 describes from a player's chair: the committed
 * effect of your own action arrives a tick or two after you took it, because the only thing that
 * ever advanced the view was a commit coming back from the committee. The overlay existed; the
 * capture path simply never told it anything.
 *
 * <p>This class is the one line of policy in between, kept separate from both sides for a reason:
 * the capture path lives next to Minecraft and the view lives in the peer, so a direct call would
 * put a rendering concern inside the submit path and a Minecraft concern next to the engine. What
 * it decides is small and worth stating:
 *
 * <ul>
 *   <li><b>Predict only what this node captured.</b> An action arriving from another node is
 *       somebody else's proposal; rendering it before the committee agrees would show a state no
 *       certificate backs, which is the opposite of what the validated lane is for.</li>
 *   <li><b>A missing view is not an error.</b> A dedicated server has no renderer, so there is
 *       nothing to predict onto and the feed is a no-op. Making that a failure would mean the
 *       capture path had to know whether anyone was looking.</li>
 *   <li><b>A refused prediction is not an error either.</b> The view refuses an action it cannot
 *       execute on its base — an untracked region, or an action the engine rejects — and refusing
 *       is the correct outcome: the render stays certified truth rather than showing something the
 *       engine would not produce.</li>
 * </ul>
 *
 * @Thread-context the supplier is read on every call; both it and the view are internally
 *                 synchronised, so any thread may feed.
 */
public final class PredictionFeed {

    private final Supplier<LocalReplicaView> view;

    /**
     * @param view supplies the active view, or {@code null} when this node renders nothing. Read
     *             per call rather than captured, because a lane can start and stop under a
     *             long-lived capture path.
     */
    public PredictionFeed(Supplier<LocalReplicaView> view) {
        if (view == null) {
            throw new IllegalArgumentException("view supplier must not be null");
        }
        this.view = view;
    }

    /** A feed with no view behind it — the dedicated-server case, and a safe default. */
    public static PredictionFeed none() {
        return new PredictionFeed(() -> null);
    }

    /**
     * Offer one locally-captured, already-signed action to the render overlay.
     *
     * @param action the action this node just captured and submitted.
     * @return whether the overlay took it. {@code false} covers every ordinary case — no view, an
     *         untracked region, an action the engine will not execute — and none of them is a
     *         reason for the caller to do anything differently.
     */
    public boolean onLocalAction(ActionEnvelope action) {
        if (action == null) {
            return false;
        }
        LocalReplicaView active = view.get();
        if (active == null) {
            return false;
        }
        try {
            return active.predict(action);
        } catch (RuntimeException degraded) {
            // Prediction is latency-hiding, never correctness: a fault here must cost the player a
            // slightly late-looking block, never their submit path.
            return false;
        }
    }
}
