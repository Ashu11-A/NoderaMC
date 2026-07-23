package dev.nodera.mod.common;

import com.mojang.serialization.Codec;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.DeferredHolder;

/**
 * Nodera chunk/entity/data attachments. Empty {@link DeferredRegister} registered now so the
 * key namespace is reserved; later tasks (Task 6 coordinator state, Task 9 archival) add real
 * {@link AttachmentType}s.
 *
 * <p>Thread context: {@code register} runs on the mod-loading thread; attachment values are
 * later read/written on the server main thread.
 */
public final class ModAttachments {

    private static final DeferredRegister<AttachmentType<?>> ATTACHMENTS =
            DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, dev.nodera.mod.NoderaMod.MOD_ID);

    /** Persistent Task-12 network identity attached to every tracked vanilla entity. */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<Long>> NETWORK_ENTITY_ID =
            ATTACHMENTS.register("network_entity_id", () ->
                    AttachmentType.builder(() -> 0L).serialize(Codec.LONG).build());

    /** Persistent exactly-once inventory-credit keys attached to each server player. */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<java.util.List<String>>>
            APPLIED_INVENTORY_CREDITS = ATTACHMENTS.register("applied_inventory_credits", () ->
            AttachmentType.builder((java.util.function.Supplier<java.util.List<String>>)
                            java.util.List::of)
                    .serialize(Codec.STRING.listOf()).build());

    private ModAttachments() {
    }

    /** Called from {@link dev.nodera.mod.NoderaMod}. */
    public static void register(IEventBus modBus) {
        ATTACHMENTS.register(modBus);
    }
}
