/**
 * Client-only ({@code Dist.CLIENT}) pause-menu "Share" surface (Task 30b) — the analogue of vanilla
 * "Open to LAN", but it broadcasts an <b>existing</b> world to the Nodera network instead of the LAN.
 *
 * <p>{@link dev.nodera.mod.client.share.PauseScreenShareAddon} adds a "Share to Nodera" button to
 * {@link net.minecraft.client.gui.screens.PauseScreen} (only when the client is hosting a local
 * integrated server, mirroring where vanilla shows "Open to LAN"), which opens
 * {@link dev.nodera.mod.client.share.ShareWorldScreen}. The screen collects a
 * {@link dev.nodera.mod.common.ShareOptions} (password + options) and asks the local integrated
 * server to {@link dev.nodera.mod.common.NoderaHost#activate host} the world. Event hooks over
 * mixins — {@code nodera.mixins.json} stays empty, matching {@code client.multiplayer}.
 *
 * <p>The host is always the local integrated server (same JVM as the client), so the action runs via
 * {@code MinecraftServer.execute(...)} rather than a network payload; a networked request would only
 * be needed to ask a <i>remote</i> dedicated server to share, which is out of scope here.
 *
 * <p>Thread-context: the screen-init event and all widgets run on the client render thread; the
 * host activation is dispatched onto the server thread.
 */
@ApiStatus.Internal
package dev.nodera.mod.client.share;

import org.jetbrains.annotations.ApiStatus;
