package dev.nodera.mod.common;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The create-world → first-load hand-off for "share this world on the Nodera network as soon as
 * it exists" (Task 5d create pipeline). The create-world screen has no server to talk to yet, so
 * the chosen {@link ShareOptions} park here; {@code ServerBootstrap} consumes them when the
 * <b>freshly created</b> integrated world starts and calls {@link NoderaHost#activate} — one code
 * path with share-later, exactly as Task 30 requires.
 *
 * <p>Safety: the pending options are cleared whenever the player returns to a world-selection /
 * title surface without creating (the client addons call {@link #clear}), and the consumer
 * additionally guards on a brand-new world, so a parked choice can never silently share a
 * pre-existing world.
 *
 * <p>Thread-context: set/cleared on the client thread, consumed on the server thread — atomics.
 */
public final class PendingCreateShare {

    private static final AtomicReference<ShareOptions> PENDING = new AtomicReference<>();

    private PendingCreateShare() {
    }

    /** Park share options chosen on the create-world screen. */
    public static void set(ShareOptions options) {
        PENDING.set(options);
    }

    /** @return the parked options, if any (peek — does not clear). */
    public static Optional<ShareOptions> peek() {
        return Optional.ofNullable(PENDING.get());
    }

    /** Take and clear the parked options. */
    public static Optional<ShareOptions> consume() {
        return Optional.ofNullable(PENDING.getAndSet(null));
    }

    /** Drop any parked options (the player backed out of world creation). */
    public static void clear() {
        PENDING.set(null);
    }
}
