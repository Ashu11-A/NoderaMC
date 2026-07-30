package dev.nodera.mod.common;

import dev.nodera.endpoint.share.HostJoinGate;
import dev.nodera.endpoint.share.JoinerIdentity;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.configuration.ServerConfigurationPacketListener;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.network.ConfigurationTask;
import net.neoforged.neoforge.network.configuration.ICustomConfigurationTask;

import java.util.function.Consumer;

/**
 * The configuration-phase step that gates a live join on the world password (L-52).
 *
 * <p>The configuration phase is the right place: it runs after the connection is established and
 * <b>before</b> the player is created and placed in the world, so a joiner who cannot answer never
 * loads a chunk, never appears in the player list, and never gets a tick of gameplay. A play-phase
 * kick would have let them in first.
 *
 * <p>Registered per connection, and only while {@link HostJoinGate} is armed — a world with no
 * password never adds this step at all, so an ordinary join is exactly what it was.
 *
 * @Thread-context constructed on the server's configuration listener; {@link #run} is called by the
 *     listener when it is this task's turn.
 */
public final class JoinPasswordTask implements ICustomConfigurationTask {

    /** The task id NeoForge tracks completion by ({@code nodera:join_password}). */
    public static final Type TYPE = new Type(
            ResourceLocation.fromNamespaceAndPath(dev.nodera.mod.NoderaMod.MOD_ID, "join_password"));

    /** The disconnect reason a refused joiner sees. */
    public static final Component REFUSED =
            Component.translatable("nodera.join.error.password_required");

    /** The disconnect reason a joiner inside a lockout window sees. */
    public static final Component THROTTLED =
            Component.translatable("nodera.join.error.too_many_attempts");

    private final ServerConfigurationPacketListener listener;

    /**
     * @param listener the connection's configuration listener — the source of the connection key
     *                 the challenge is stored under, and the only way to complete this step.
     */
    public JoinPasswordTask(ServerConfigurationPacketListener listener) {
        this.listener = listener;
    }

    @Override
    public void run(Consumer<CustomPacketPayload> sender) {
        Connection connection = listener.getConnection();
        long now = System.currentTimeMillis();
        if (HostJoinGate.get().isThrottled(
                JoinerIdentity.of(connection.getRemoteAddress()), now)) {
            // Refused before the challenge is built: a client grinding the password must not be
            // able to make the host issue nonces at the rate it can reconnect.
            listener.disconnect(THROTTLED);
            return;
        }
        HostJoinGate.get().issue(connection, now)
                .map(NoderaJoinChallengePayload::of)
                .ifPresentOrElse(sender::accept, () -> {
                    if (HostJoinGate.get().isSealed()) {
                        // The world is password-protected and its gate could not be armed. Refusing
                        // is the only honest answer: letting the joiner through would silently turn
                        // a protected world into an open one.
                        listener.disconnect(REFUSED);
                        return;
                    }
                    // Disarmed between registration and our turn (the host stopped sharing, or
                    // dropped the password). There is nothing left to prove, so let the joiner
                    // through rather than punishing them for the host's timing.
                    listener.finishCurrentTask(TYPE);
                });
    }

    @Override
    public ConfigurationTask.Type type() {
        return TYPE;
    }
}
