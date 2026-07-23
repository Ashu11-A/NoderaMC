package dev.nodera.mod.client.multiplayer;

import dev.nodera.distribution.WorldArchive;
import dev.nodera.mod.common.CompanionLink;
import dev.nodera.mod.common.NoderaConfig;
import dev.nodera.mod.common.NoderaPeerService;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.ScreenEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;

/**
 * The joiner half of the world-continuity lane (the "host closed the game" answer): when the
 * connection to a Nodera-joined world dies, the world does not — its archive already lives on the
 * peer network. This class watches for the vanilla {@link DisconnectedScreen} after a Nodera join,
 * fetches the world's newest archive through the local worker ({@code NODERA-ARCHIVE}), unpacks it
 * into {@code saves/}, re-opens it as a local world, and lets the Task 33 auto-re-share path put it
 * straight back on the network — the joiner becomes the world's next host. The brief hop through a
 * reconnect is the interim the register already budgets for gateway migration (L-17); the zero-
 * reconnect local-replica view remains Task 16's exit.
 *
 * <p>The trigger is deliberately narrow: only a session that was started by {@link NoderaJoinFlow}
 * arms it, and only an abnormal disconnect (the {@link DisconnectedScreen}) fires it — a voluntary
 * "Disconnect" from the pause menu never shows that screen, so quitting on purpose never rehosts.
 *
 * <p>Thread-context: event handlers + screen on the render thread; the fetch/unpack runs on a
 * dedicated background thread and marshals back via {@link Minecraft#execute}.
 */
public final class NoderaContinuity {

    private static final Logger LOG = LoggerFactory.getLogger("NoderaContinuity");

    /** The Nodera world the current vanilla session was joined into, or {@code null}. */
    private static volatile JoinedWorld joined;

    /** One rehost attempt per disconnect; re-armed by the next Nodera join. */
    private static volatile boolean rehosting;

    private NoderaContinuity() {
    }

    /** A Nodera join in flight/active: worldId + display name (the rehost fetch key). */
    record JoinedWorld(String worldIdHex, String name) {
    }

    /**
     * Arm continuity for this session — called by {@link NoderaJoinFlow} when connecting, and by
     * the session payload on login (covers direct-IP / quick-play joins into a Nodera world). The
     * hosting player also receives the payload, but a host closing its own integrated server
     * never sees a {@link DisconnectedScreen}, so a host can never rehost its own world here.
     */
    public static void onJoining(String worldIdHex, String worldName) {
        if (worldIdHex == null || worldIdHex.isBlank()) {
            return;
        }
        // The hosting JVM must NEVER arm recovery for its own world: a local-connection hiccup
        // would otherwise "recover" a world this process still hosts — reopening it, kicking every
        // joiner, and cascading (observed live as a reopen/kick feedback loop).
        if (NoderaPeerService.get().isHosting()) {
            return;
        }
        joined = new JoinedWorld(worldIdHex, worldName);
        rehosting = false;
        // Hot standby: pull the world archive NOW, while the session is healthy, so a host loss
        // needs no download phase — the local worker already holds every piece and recovery
        // collapses to the world-open. Re-fetches ride new seeded versions; failures are silent
        // (the on-loss fetch remains the fallback).
        if (CompanionLink.isPresent()) {
            Thread.ofPlatform().name("nodera-standby-prefetch").daemon().start(() -> {
                try {
                    java.nio.file.Path fetchDir =
                            Minecraft.getInstance().gameDirectory.toPath().resolve("nodera/fetch");
                    java.nio.file.Files.createDirectories(fetchDir);
                    CompanionLink.client().fetchArchive(worldIdHex,
                            fetchDir.resolve(worldIdHex.substring(0, 12) + ".nar"), 300);
                    LOG.info("standby prefetch complete for '{}' — recovery needs no download",
                            worldName);
                } catch (Exception ignored) {
                    // standby is best-effort; the on-loss fetch is the fallback
                }
            });
        }
    }

    /** Disarm (deliberate leave, rehost done, or the player declined). */
    static void disarm() {
        joined = null;
    }

    /**
     * Screen hook (registered in {@code ClientBootstrap}): an abnormal disconnect from a
     * Nodera-joined world swaps the terminal vanilla screen for the recovery flow.
     */
    public static void onScreenOpening(ScreenEvent.Opening event) {
        JoinedWorld world = joined;
        if (world == null || rehosting
                || !(event.getNewScreen() instanceof DisconnectedScreen)
                || !NoderaConfig.CONTINUITY_AUTO_REHOST.get()
                || !CompanionLink.isPresent()
                || NoderaPeerService.get().isHosting()) {
            return;
        }
        rehosting = true;
        LOG.info("Nodera continuity: host connection lost for '{}' — fetching the world archive "
                + "from the network", world.name());
        RehostScreen screen = new RehostScreen(world);
        event.setNewScreen(screen);
        screen.begin();
    }

    /**
     * The network-first entry (no host-and-client assumption): materialize a world <i>from the
     * peer network</i> and open it locally, becoming one of its hosts. Used by the join flow for
     * a world whose author/host is offline — the world's files live on the Nodera network, so
     * "no live game endpoint" is a fetch, not a dead end. The disconnect-recovery path above is
     * this same flow triggered by a session loss.
     *
     * @param worldIdHex the world to materialize.
     * @param worldName  its display name.
     * @return whether the recovery flow started (worker present + id known).
     * @Thread-context render thread.
     */
    public static boolean openFromNetwork(String worldIdHex, String worldName) {
        if (worldIdHex == null || worldIdHex.isBlank() || !CompanionLink.isPresent()) {
            return false;
        }
        rehosting = true;
        LOG.info("Nodera: materializing world '{}' from the peer network", worldName);
        RehostScreen screen = new RehostScreen(new JoinedWorld(worldIdHex, worldName));
        Minecraft.getInstance().setScreen(screen);
        screen.begin();
        return true;
    }

    /** The stable local save-folder name for a rehosted world (per-world, collision-free). */
    static String rehostDirName(JoinedWorld world) {
        String suffix = world.worldIdHex().substring(0, Math.min(8, world.worldIdHex().length()));
        String base = world.name() == null || world.name().isBlank() ? "Nodera World"
                : world.name().replaceAll("[\\\\/:*?\"<>|]", "_");
        return base + " [" + suffix.toLowerCase(Locale.ROOT) + "]";
    }

    /** Fetch → unpack → open. All heavy work off-thread; UI transitions on the render thread. */
    static void rehost(JoinedWorld world, RehostScreen screen) {
        Minecraft mc = Minecraft.getInstance();
        Thread.ofPlatform().name("nodera-rehost").daemon().start(() -> {
            try {
                Path fetchDir = mc.gameDirectory.toPath().resolve("nodera/fetch");
                Files.createDirectories(fetchDir);
                Path archiveFile = fetchDir.resolve(world.worldIdHex().substring(0, 12) + ".nar");
                screen.setStatus(Component.translatable("nodera.continuity.fetching"));
                Optional<String> fetched = CompanionLink.client().fetchArchive(
                        world.worldIdHex(), archiveFile,
                        NoderaConfig.CONTINUITY_FETCH_TIMEOUT_SECONDS.get());
                if (fetched.isEmpty()) {
                    fail(mc, screen, "the worker could not fetch the archive (no seeder online?)");
                    return;
                }
                screen.setStatus(Component.translatable("nodera.continuity.unpacking"));
                byte[] blob = Files.readAllBytes(archiveFile);
                String dirName = rehostDirName(world);
                Path saveDir = mc.gameDirectory.toPath().resolve("saves").resolve(dirName);
                WorldArchive.unpackInto(blob, saveDir);
                LOG.info("Nodera continuity: '{}' restored to saves/{} ({} bytes, {})",
                        world.name(), dirName, blob.length, fetched.get());
                disarm();
                mc.execute(() -> {
                    screen.setStatus(Component.translatable("nodera.continuity.opening"));
                    // Opening the world triggers the Task 33 auto-re-share (the archive carries
                    // nodera-world.dat with shared=true), which makes this player the next host.
                    mc.createWorldOpenFlows().openWorld(dirName, () -> {
                        LOG.warn("Nodera continuity: could not open the restored world");
                        mc.setScreen(new TitleScreen());
                    });
                });
            } catch (Exception e) {
                fail(mc, screen, e.toString());
            }
        });
    }

    private static void fail(Minecraft mc, RehostScreen screen, String reason) {
        LOG.warn("Nodera continuity: rehost failed: {}", reason);
        disarm();
        mc.execute(() -> screen.showFailure(reason));
    }

    /** Progress screen shown while the archive is fetched + the world re-opened. */
    static final class RehostScreen extends Screen {
        private final JoinedWorld world;
        private volatile Component status = Component.translatable("nodera.continuity.starting");
        private volatile Component failure;

        RehostScreen(JoinedWorld world) {
            super(Component.translatable("nodera.continuity.title"));
            this.world = world;
        }

        void begin() {
            NoderaContinuity.rehost(world, this);
        }

        void setStatus(Component component) {
            this.status = component;
        }

        void showFailure(String reason) {
            this.failure = Component.translatable("nodera.continuity.failed", reason);
            rebuildWidgets();
        }

        @Override
        protected void init() {
            if (failure != null) {
                addRenderableWidget(net.minecraft.client.gui.components.Button.builder(
                                net.minecraft.network.chat.CommonComponents.GUI_BACK,
                                b -> onClose())
                        .bounds(this.width / 2 - 100, this.height / 2 + 40, 200, 20).build());
            }
        }

        @Override
        public void render(net.minecraft.client.gui.GuiGraphics graphics, int mouseX, int mouseY,
                           float partialTick) {
            // Deliberately quiet — a vanilla-style loading beat, not a banner. With the standby
            // prefetch this screen typically lives for well under a second before the world-open.
            super.render(graphics, mouseX, mouseY, partialTick);
            if (failure != null) {
                graphics.drawCenteredString(this.font, failure, this.width / 2,
                        this.height / 2 - 4, 0xFF6666);
            } else {
                graphics.drawCenteredString(this.font,
                        net.minecraft.network.chat.Component.translatable(
                                "nodera.continuity.migrating"),
                        this.width / 2, this.height / 2 - 4, 0xA0A0A0);
            }
        }

        @Override
        public boolean shouldCloseOnEsc() {
            return failure != null;
        }

        @Override
        public void onClose() {
            if (this.minecraft != null) {
                this.minecraft.setScreen(new TitleScreen());
            }
        }
    }
}
