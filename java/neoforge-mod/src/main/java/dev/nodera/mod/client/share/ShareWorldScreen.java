package dev.nodera.mod.client.share;

import dev.nodera.mod.common.NoderaHost;
import dev.nodera.mod.common.NoderaPeerService;
import dev.nodera.mod.common.ShareOptions;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * The "Share world to Nodera" screen (Task 30b) — the analogue of vanilla's
 * {@code ShareToLanScreen}, reached from the pause-menu button. Collects a {@link ShareOptions}
 * (encryption password, region delegation, tracker visibility) and asks the local integrated server
 * to {@link NoderaHost#activate host} the currently-loaded world on the peer network.
 *
 * <p>The host is the local integrated server (same JVM), so the action is dispatched onto the server
 * thread via {@code MinecraftServer.execute(...)}. If the world is already shared the screen offers
 * to update the options or stop sharing; changing the password re-manifests the world (Task 23),
 * which {@link NoderaHost#reconfigure} logs and starts.
 *
 * <p>Thread-context: client render thread; server work is dispatched to the server thread.
 */
public final class ShareWorldScreen extends Screen {

    private static final int WIDTH = 204;

    private final Screen parent;

    private EditBox password;
    private boolean delegate;
    private boolean listed;
    private int replicationHint;

    public ShareWorldScreen(Screen parent) {
        super(Component.translatable("nodera.share.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        ShareOptions current = NoderaPeerService.get().hostOptions();
        if (current == null) {
            current = ShareOptions.playerDefault();
        }
        this.delegate = current.delegateRegions();
        this.listed = current.listedOnTracker();
        this.replicationHint = current.replicationHint();

        int x = this.width / 2 - WIDTH / 2;
        int y = this.height / 4 + 8;

        this.password = new EditBox(this.font, x, y, WIDTH, 20,
                Component.translatable("nodera.share.password"));
        this.password.setHint(Component.translatable("nodera.share.password.hint"));
        this.password.setMaxLength(128);
        this.password.setValue(current.password());
        addRenderableWidget(this.password);
        y += 28;

        addRenderableWidget(Button.builder(delegateLabel(), b -> {
            this.delegate = !this.delegate;
            b.setMessage(delegateLabel());
        }).bounds(x, y, WIDTH, 20).build());
        y += 24;

        addRenderableWidget(Button.builder(listedLabel(), b -> {
            this.listed = !this.listed;
            b.setMessage(listedLabel());
        }).bounds(x, y, WIDTH, 20).build());
        y += 32;

        boolean sharing = NoderaPeerService.get().isHosting();
        if (sharing) {
            addRenderableWidget(Button.builder(
                    Component.translatable("nodera.share.update"), b -> apply(true))
                    .bounds(x, y, WIDTH, 20).build());
            y += 24;
            addRenderableWidget(Button.builder(
                    Component.translatable("nodera.share.stop"), b -> stopSharing())
                    .bounds(x, y, WIDTH, 20).build());
            y += 24;
        } else {
            addRenderableWidget(Button.builder(
                    Component.translatable("nodera.share.confirm"), b -> apply(false))
                    .bounds(x, y, WIDTH, 20).build());
            y += 24;
        }

        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose())
                .bounds(x, y, WIDTH, 20).build());
    }

    private Component delegateLabel() {
        return Component.translatable(delegate
                ? "nodera.share.delegate.on" : "nodera.share.delegate.off");
    }

    private Component listedLabel() {
        return Component.translatable(listed
                ? "nodera.share.listed.on" : "nodera.share.listed.off");
    }

    private void apply(boolean reconfigure) {
        var server = this.minecraft == null ? null : this.minecraft.getSingleplayerServer();
        if (server == null) {
            onClose();
            return;
        }
        ShareOptions options = new ShareOptions(
                this.password.getValue(), this.delegate, this.listed, this.replicationHint);
        server.execute(() -> {
            if (reconfigure) {
                NoderaHost.reconfigure(server, options);
            } else {
                NoderaHost.activate(server, options);
            }
        });
        onClose();
    }

    private void stopSharing() {
        var server = this.minecraft == null ? null : this.minecraft.getSingleplayerServer();
        if (server != null) {
            server.execute(() -> NoderaHost.deactivate(server));
        }
        onClose();
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, this.height / 4 - 24,
                0xFFFFFF);
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(parent);
        }
    }
}
