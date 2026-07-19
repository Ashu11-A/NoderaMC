package dev.nodera.mod.common;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import net.neoforged.neoforge.registries.DeferredRegister;

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

    private ModAttachments() {
    }

    /** Called from {@link dev.nodera.mod.NoderaMod}. */
    public static void register(IEventBus modBus) {
        ATTACHMENTS.register(modBus);
    }
}
