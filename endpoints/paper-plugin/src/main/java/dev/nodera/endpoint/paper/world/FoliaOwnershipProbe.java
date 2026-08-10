package dev.nodera.endpoint.paper.world;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Optional;

/**
 * The regionised-ownership seam, resolved reflectively (server task 3).
 *
 * <p><b>Why reflection.</b> {@link dev.nodera.endpoint.paper.EndpointPlatform} already decides what
 * platform this is by probing for a class rather than reading a version string, for the reason
 * given there: forks rename themselves. This is the same decision one level down. The plugin
 * compiles against the <i>Paper</i> API — the pin is {@code paperApi} in
 * {@code gradle/libs.versions.toml} — and {@code isOwnedByCurrentRegion} is a method whose presence
 * and shape follow the platform, not our pin. Naming it directly would tie the whole plugin's
 * ability to compile to which Bukkit-family API happened to be resolvable, and would put a Folia
 * jar on the unit-test classpath to test one boolean.
 *
 * <p>Everything here is {@code Object} and {@link Method}: the class body references no Bukkit
 * type, so it loads and is testable on a machine with no server jar at all.
 *
 * <p><b>An unresolvable seam is not an error.</b> {@link #resolve} returns
 * {@link NoderaFoliaRegionMap.ExecutionOwnership#UNANSWERABLE}, which
 * {@link NoderaFoliaRegionMap} reads as "does not share" — the refusal, not a guess.
 *
 * @Thread-context the resolved probe answers about the CALLING thread and must be called from the
 *                 region thread whose ownership is in question.
 */
public final class FoliaOwnershipProbe implements NoderaFoliaRegionMap.ExecutionOwnership {

    /** Folia's own name for the check, on {@code org.bukkit.Server}. */
    private static final String METHOD = "isOwnedByCurrentRegion";

    private final Object server;
    private final Object world;
    private final Method check;

    private FoliaOwnershipProbe(Object server, Object world, Method check) {
        this.server = server;
        this.world = world;
        this.check = check;
    }

    /**
     * Resolve the probe against a live server.
     *
     * @param server the {@code org.bukkit.Server}, as an {@code Object}.
     * @param world  the {@code org.bukkit.World} the endpoint's regions live in.
     * @return the probe, or {@link NoderaFoliaRegionMap.ExecutionOwnership#UNANSWERABLE} when this
     *         platform has no such seam.
     */
    public static NoderaFoliaRegionMap.ExecutionOwnership resolve(Object server, Object world) {
        if (server == null || world == null) {
            return NoderaFoliaRegionMap.ExecutionOwnership.UNANSWERABLE;
        }
        for (Method method : server.getClass().getMethods()) {
            if (!METHOD.equals(method.getName()) || method.getParameterCount() != 3
                    || method.getReturnType() != boolean.class) {
                continue;
            }
            Class<?>[] parameters = method.getParameterTypes();
            if (parameters[1] != int.class || parameters[2] != int.class
                    || !parameters[0].isInstance(world)) {
                continue;
            }
            return new FoliaOwnershipProbe(server, world, method);
        }
        return NoderaFoliaRegionMap.ExecutionOwnership.UNANSWERABLE;
    }

    @Override
    public Optional<Boolean> ownsChunk(int chunkX, int chunkZ) {
        try {
            return Optional.of((Boolean) check.invoke(server, world, chunkX, chunkZ));
        } catch (IllegalAccessException | InvocationTargetException | RuntimeException refused) {
            // Folia throws rather than answering when the caller is not a region thread at all.
            // That is an honest "cannot say", and the caller already treats it as "does not share".
            return Optional.empty();
        }
    }
}
