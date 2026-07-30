package dev.nodera.mod.client.share;

import dev.nodera.mod.common.WorldArchiver;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.ScreenEvent;

/**
 * Issue #43: the "Saving World" exit screen overlay — while the final archive flush streams the
 * session's last bytes to the Nodera network, show an animated progress bar + status line so the
 * player sees the exit is <i>sending latest data to the Nodera network</i>, not hung.
 *
 * <p>Pure presentation: reads {@link WorldArchiver#phase()} (set by the seeding thread) and draws
 * on the vanilla {@link GenericMessageScreen} (the level-saving screen). Nothing here touches the
 * save/seed logic; when the phase is idle/done the overlay draws nothing.
 *
 * <p>Thread-context: render thread ({@code ScreenEvent.Render.Post}).
 */
public final class SaveScreenSeedOverlay {

    /** Indeterminate sweep period, ms (the upload length is unknown — animate a sweep). */
    private static final long SWEEP_MS = 1_200;

    private SaveScreenSeedOverlay() {
    }

    /** {@code ScreenEvent.Render.Post} hook — registered in {@code ClientBootstrap}. */
    public static void onScreenRender(ScreenEvent.Render.Post event) {
        if (!(event.getScreen() instanceof GenericMessageScreen screen)) {
            return;
        }
        WorldArchiver.SeedPhase phase = WorldArchiver.phase();
        if (phase != WorldArchiver.SeedPhase.PACKING && phase != WorldArchiver.SeedPhase.UPLOADING) {
            return;
        }
        GuiGraphics gfx = event.getGuiGraphics();
        int width = screen.width;
        int centerY = screen.height / 2 + 24;

        Component label = phase == WorldArchiver.SeedPhase.PACKING
                ? Component.translatable("nodera.exit.packing")
                : Component.translatable("nodera.exit.uploading");
        gfx.drawCenteredString(net.minecraft.client.Minecraft.getInstance().font,
                label.copy().withStyle(ChatFormatting.AQUA), width / 2, centerY, 0xFFFFFF);

        // Indeterminate progress bar: a bright segment sweeping a track.
        int barWidth = Math.min(220, width - 80);
        int barX = (width - barWidth) / 2;
        int barY = centerY + 14;
        int barH = 6;
        gfx.fill(barX - 1, barY - 1, barX + barWidth + 1, barY + barH + 1, 0xFF000000);
        gfx.fill(barX, barY, barX + barWidth, barY + barH, 0xFF2A2A2A);
        int segment = barWidth / 4;
        long now = System.currentTimeMillis() % SWEEP_MS;
        int offset = (int) ((barWidth + segment) * now / SWEEP_MS) - segment;
        int segStart = Math.max(barX, barX + offset);
        int segEnd = Math.min(barX + barWidth, barX + offset + segment);
        if (segEnd > segStart) {
            gfx.fill(segStart, barY, segEnd, barY + barH, 0xFF35C4A0);
        }
    }
}
