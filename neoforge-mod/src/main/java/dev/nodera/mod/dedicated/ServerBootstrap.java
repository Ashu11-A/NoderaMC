package dev.nodera.mod.dedicated;

import com.mojang.brigadier.context.CommandContext;
import dev.nodera.core.identity.NodeId;
import dev.nodera.mod.common.NoderaConfig;
import dev.nodera.mod.common.NoderaPeerService;
import dev.nodera.mod.common.NoderaSessionPayload;
import dev.nodera.peer.PeerRuntime;
import dev.nodera.peer.SessionView;
import dev.nodera.protocol.membership.PeerEntry;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.ApiStatus;

/**
 * Dedicated-server wiring for the Nodera bootstrap peer (Phase 6 continuity beta).
 *
 * <p>On {@link ServerStartedEvent} it starts the bootstrap {@link PeerRuntime} (the "server acting
 * as a peer"); on {@link PlayerEvent.PlayerLoggedInEvent} it hands each joining player the P2P
 * bootstrap route via {@link NoderaSessionPayload} so the client can dial into the mesh; on
 * {@link ServerStoppingEvent} it tears the bootstrap peer down — the moment that triggers the
 * clients' deterministic gateway migration. It also registers {@code /nodera status|peers} for
 * operators to observe the mesh.
 *
 * <p>Thread context: {@code register} runs on the mod-loading thread; the subscribed events fire
 * on the server main thread.
 */
@ApiStatus.Internal
public final class ServerBootstrap {

    private ServerBootstrap() {
    }

    /** Called from {@link dev.nodera.mod.NoderaMod} only when {@code dist == DEDICATED_SERVER}. */
    public static void register() {
        NeoForge.EVENT_BUS.addListener(ServerBootstrap::onServerStarted);
        NeoForge.EVENT_BUS.addListener(ServerBootstrap::onServerStopping);
        NeoForge.EVENT_BUS.addListener(ServerBootstrap::onPlayerLoggedIn);
        NeoForge.EVENT_BUS.addListener(ServerBootstrap::onRegisterCommands);
    }

    private static void onServerStarted(ServerStartedEvent event) {
        NoderaPeerService.get().startBootstrap(
                NoderaConfig.P2P_BIND_HOST.get(),
                NoderaConfig.P2P_PORT.get(),
                NoderaConfig.P2P_ADVERTISE_HOST.get());
    }

    private static void onServerStopping(ServerStoppingEvent event) {
        NoderaPeerService.get().stopServer();
    }

    private static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        String route = NoderaPeerService.get().bootstrapRoute();
        if (route != null && event.getEntity() instanceof ServerPlayer player) {
            PacketDistributor.sendToPlayer(player, new NoderaSessionPayload(route));
        }
    }

    private static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("nodera")
                        .then(Commands.literal("status").executes(ServerBootstrap::status))
                        .then(Commands.literal("peers").executes(ServerBootstrap::peers)));
    }

    private static int status(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        PeerRuntime rt = NoderaPeerService.get().serverRuntime();
        if (rt == null) {
            src.sendSuccess(() -> Component.literal("Nodera: bootstrap peer offline"), false);
            return 0;
        }
        SessionView view = rt.sessionView();
        src.sendSuccess(() -> Component.literal(
                "Nodera bootstrap " + rt.selfRoute()
                        + " | epoch " + view.epoch()
                        + " | gateway " + view.gatewayId()
                        + " | members " + view.size()), false);
        return 1;
    }

    private static int peers(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        PeerRuntime rt = NoderaPeerService.get().serverRuntime();
        if (rt == null) {
            src.sendSuccess(() -> Component.literal("Nodera: bootstrap peer offline"), false);
            return 0;
        }
        SessionView view = rt.sessionView();
        NodeId gateway = view.gatewayId();
        for (PeerEntry e : view.members()) {
            String marker = e.nodeId().equals(gateway) ? " (gateway)" : "";
            src.sendSuccess(() -> Component.literal(
                    " - " + e.nodeId() + " @ " + e.route()
                            + (e.bootstrap() ? " [bootstrap]" : "") + marker), false);
        }
        return view.size();
    }
}
