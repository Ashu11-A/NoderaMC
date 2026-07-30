package dev.nodera.endpoint.config;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The handful of configured values the endpoint logic actually reads.
 *
 * <p>This exists because {@code NoderaConfig} — the mod's config — is a {@code ModConfigSpec}
 * holder whose every field TYPE is NeoForge. There is no plain record hiding inside it to extract,
 * so the split is an accessor: the mod keeps its spec and registers an adapter, and everything that
 * merely wants to know which trackers to dial reads this interface and needs no Minecraft on its
 * path. That is what freed {@code NoderaPeerService}, 1077 lines of peer wiring, to leave the mod.
 *
 * <p>Deliberately narrow. It names the five values that were actually read across the endpoint code
 * (four endpoint lists and the join password), not the forty the config spec publishes. A settings
 * interface that mirrors a config file grows a method per knob and is a second copy of the config;
 * this one grows a method when a caller appears.
 *
 * <p>Thread-context: implementations are read from the server main thread, the client thread and
 * netty handlers. {@link #current} is an atomic read of an immutable implementation.
 */
public interface NoderaSettings {

    /** Trackers a HOSTED world announces to. */
    List<String> trackerEndpoints();

    /** Rendezvous services a HOSTED world registers with. */
    List<String> rendezvousEndpoints();

    /** Trackers this client queries when browsing worlds. */
    List<String> clientTrackerEndpoints();

    /** Rendezvous services this client dials through. */
    List<String> clientRendezvousEndpoints();

    /**
     * The password this client answers a live-join challenge with, or empty for none.
     *
     * <p>Empty is not "no gate": it is "this client has nothing to offer", and a gated world will
     * refuse it during the configuration phase, which is the intended outcome.
     */
    String joinPassword();

    /**
     * The built-in defaults, used until a host installs something else.
     *
     * <p>The endpoint lists point at the services {@code scripts/dev.sh} runs on localhost, so a
     * fresh checkout is functional rather than announcing into nothing. A release build replaces
     * them with the known public network.
     *
     * <p>These are the ONLY copy. {@code NoderaConfig} used to declare them and
     * {@code HeadlessPeerMain} hardcoded the same two strings with a comment promising they matched
     * — a comment is not a mechanism.
     */
    static NoderaSettings defaults() {
        return Defaults.INSTANCE;
    }

    /** The settings in force. {@link #defaults} until {@link #install} is called. */
    static NoderaSettings current() {
        return Holder.CURRENT.get();
    }

    /**
     * Install the host's settings.
     *
     * <p>Called once, by whichever endpoint is running: the mod hands over a {@code ModConfigSpec}
     * adapter during mod construction, a Paper plugin hands over its own YAML-backed one.
     *
     * @param settings the settings to read from here on; {@code null} restores the defaults.
     */
    static void install(NoderaSettings settings) {
        Holder.CURRENT.set(settings == null ? Defaults.INSTANCE : settings);
    }

    /** @hidden */
    final class Holder {
        static final AtomicReference<NoderaSettings> CURRENT =
                new AtomicReference<>(Defaults.INSTANCE);

        private Holder() {
        }
    }

    /** @hidden */
    final class Defaults implements NoderaSettings {
        static final Defaults INSTANCE = new Defaults();

        private Defaults() {
        }

        @Override
        public List<String> trackerEndpoints() {
            return List.of("127.0.0.1:25600");
        }

        @Override
        public List<String> rendezvousEndpoints() {
            return List.of("127.0.0.1:25601");
        }

        @Override
        public List<String> clientTrackerEndpoints() {
            return trackerEndpoints();
        }

        @Override
        public List<String> clientRendezvousEndpoints() {
            return rendezvousEndpoints();
        }

        @Override
        public String joinPassword() {
            return "";
        }
    }
}
