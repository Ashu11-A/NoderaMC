package dev.nodera.mod.server.entity;

import dev.nodera.diagnostics.model.EntityControl;
import dev.nodera.diagnostics.source.EntityControlProvider;

import java.util.concurrent.atomic.AtomicReference;

/**
 * The live {@link EntityControlProvider} (Task 18 L-31 exit): while a Task-12 entity-lane session
 * is active, the HUD's entities panel shows the validated entities per delegated region straight
 * from the lane's committed snapshots; with no active session it renders the empty placeholder.
 *
 * <p>Registered once into the host diagnostics collector; the active runtime is swapped in and out
 * by {@link LiveEntityLaneSession} so the provider's lifetime is independent of the lane's.
 *
 * <p>Thread-context: {@link #entities()} runs on the diagnostics sample thread;
 * {@link #activate}/{@link #deactivate} on the lane bootstrap/server threads. All state is a
 * single {@link AtomicReference}.
 */
public final class LiveEntityControlProvider implements EntityControlProvider {

    private static final AtomicReference<LiveEntityLaneRuntime> ACTIVE = new AtomicReference<>();
    private static final LiveEntityControlProvider INSTANCE = new LiveEntityControlProvider();

    private LiveEntityControlProvider() {
    }

    /** The singleton registered into the diagnostics collector. */
    public static LiveEntityControlProvider get() {
        return INSTANCE;
    }

    /** Called by the session when its runtime goes live. */
    static void activate(LiveEntityLaneRuntime runtime) {
        ACTIVE.set(runtime);
    }

    /** Called by the session on close; only clears if {@code runtime} is still the active one. */
    static void deactivate(LiveEntityLaneRuntime runtime) {
        ACTIVE.compareAndSet(runtime, null);
    }

    @Override
    public EntityControl entities() {
        LiveEntityLaneRuntime runtime = ACTIVE.get();
        return runtime == null ? EntityControl.empty() : runtime.entityControl();
    }
}
