package dev.nodera.mod.common;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Registers the single Nodera custom payload type and the protocol-version registrar.
 *
 * <p>Design rule (Task 4): exactly ONE NeoForge payload type ({@code NoderaPayload}) carries a
 * self-describing frame; NeoForge sees opaque bytes, message evolution is a {@code protocol}
 * concern. For now (Task 1) we only prove the registrar exists at version {@code "1"} with
 * zero payloads — Task 4 wires the real handshake + relay.
 *
 * <p>Thread context: {@code register} subscribes on the mod event bus; the
 * {@link RegisterPayloadHandlersEvent} fires on the mod-loading thread.
 */
public final class ModNetworking {

    /** NeoForge payload registrar version string (Task 0 §2: wire protocol version "1"). */
    public static final String PROTOCOL_VERSION = "1";

    private ModNetworking() {
    }

    /** Called from {@link dev.nodera.mod.NoderaMod}. Idempotent: subscribing twice is a no-op. */
    public static void register(IEventBus modBus) {
        modBus.addListener(ModNetworking::onRegisterPayloads);
    }

    private static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(PROTOCOL_VERSION);
        // Task 4: registrar.playBidirectional(...) / configurationBidirectional(...) for NoderaPayload.
        // Intentionally empty for now — proves the pipeline is wired.
    }
}
