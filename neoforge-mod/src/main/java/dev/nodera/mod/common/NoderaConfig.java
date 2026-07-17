package dev.nodera.mod.common;

import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.jetbrains.annotations.ApiStatus;

/**
 * Nodera configuration specs — exposes the Task 0 §5 {@code NoderaConstants} defaults as
 * NeoForge config values (SERVER + CLIENT). Unused until Task 5+; registered now so the
 * specs exist and the files materialise on first run.
 *
 * <p>Thread context: registration runs on the mod-loading thread; value reads later happen on
 * whichever thread asks NeoForge's config API (server main thread for the SERVER spec).
 */
public final class NoderaConfig {

    private static final ModConfigSpec.Builder SERVER_BUILDER = new ModConfigSpec.Builder();
    private static final ModConfigSpec.Builder CLIENT_BUILDER = new ModConfigSpec.Builder();

    // Region / batch sizing (Task 0 §5).
    public static final ModConfigSpec.IntValue REGION_SIZE_CHUNKS =
            SERVER_BUILDER.defineInRange("region.regionSizeChunks", 8, 1, 64);
    public static final ModConfigSpec.IntValue BATCH_TICKS =
            SERVER_BUILDER.defineInRange("execution.batchTicks", 2, 1, 20);
    public static final ModConfigSpec.IntValue LEASE_LENGTH_TICKS =
            SERVER_BUILDER.defineInRange("coordinator.leaseLengthTicks", 200, 1, 60_000);

    // Quorum (Task 6–8 MVP gate).
    public static final ModConfigSpec.IntValue REQUIRED_VALIDATORS =
            SERVER_BUILDER.defineInRange("committee.requiredValidators", 3, 1, 16);

    // Client worker capacity (Task 5).
    public static final ModConfigSpec.IntValue CLIENT_MAX_PRIMARY =
            CLIENT_BUILDER.defineInRange("worker.maxPrimary", 1, 0, 64);
    public static final ModConfigSpec.IntValue CLIENT_MAX_REPLICA =
            CLIENT_BUILDER.defineInRange("worker.maxReplica", 4, 0, 64);

    private static final ModConfigSpec SERVER_SPEC = SERVER_BUILDER.build();
    private static final ModConfigSpec CLIENT_SPEC = CLIENT_BUILDER.build();

    private NoderaConfig() {
    }

    /** Called from {@link dev.nodera.mod.NoderaMod} on the mod-loading thread. */
    @ApiStatus.Internal
    public static void register(ModContainer container) {
        container.registerConfig(ModConfig.Type.SERVER, SERVER_SPEC);
        container.registerConfig(ModConfig.Type.CLIENT, CLIENT_SPEC);
    }
}
