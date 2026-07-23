package dev.nodera.mod.client.create;

import dev.nodera.mod.common.PendingCreateShare;
import dev.nodera.mod.common.ShareOptions;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * The create-world Nodera options screen (Task 5d redesign of the floating
 * {@code CreateTorrentWorldOption} widgets): a proper settings surface reached from the
 * create-world screen's "Nodera: …" button. Collects whether the new world should go on the
 * network at creation, its tracker visibility, region delegation, and the optional encryption
 * password — parked in {@link PendingCreateShare} and consumed when the freshly created world
 * first starts.
 *
 * <p>Thread-context: client render thread.
 */
public final class NoderaCreateOptionsScreen extends Screen {

    private static final int WIDTH = 240;

    private final Screen parent;

    private boolean share;
    private boolean listed = true;
    private boolean delegate = true;
    private EditBox password;
    private Button shareToggle;
    private Button listedToggle;
    private Button delegateToggle;

    public NoderaCreateOptionsScreen(Screen parent) {
        super(Component.translatable("nodera.create.title"));
        this.parent = parent;
        PendingCreateShare.peek().ifPresent(opts -> {
            this.share = true;
            this.listed = opts.listedOnTracker();
            this.delegate = opts.delegateRegions();
        });
    }

    @Override
    protected void init() {
        String parkedPassword = PendingCreateShare.peek().map(ShareOptions::password).orElse("");
        int x = this.width / 2 - WIDTH / 2;
        int y = this.height / 4;

        shareToggle = addRenderableWidget(Button.builder(shareLabel(), b -> {
            share = !share;
            refreshLabels();
        }).bounds(x, y, WIDTH, 20).build());
        y += 28;

        listedToggle = addRenderableWidget(Button.builder(listedLabel(), b -> {
            listed = !listed;
            refreshLabels();
        }).bounds(x, y, WIDTH, 20).build());
        y += 24;

        delegateToggle = addRenderableWidget(Button.builder(delegateLabel(), b -> {
            delegate = !delegate;
            refreshLabels();
        }).bounds(x, y, WIDTH, 20).build());
        y += 28;

        password = new EditBox(this.font, x, y, WIDTH, 20,
                Component.translatable("nodera.share.password"));
        password.setMaxLength(128);
        password.setValue(parkedPassword);
        password.setHint(Component.translatable("nodera.share.password.hint"));
        addRenderableWidget(password);
        y += 36;

        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> saveAndClose())
                .bounds(x, y, (WIDTH - 8) / 2, 20).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> onClose())
                .bounds(x + (WIDTH + 8) / 2, y, (WIDTH - 8) / 2, 20).build());

        refreshLabels();
    }

    private void refreshLabels() {
        shareToggle.setMessage(shareLabel());
        listedToggle.setMessage(listedLabel());
        delegateToggle.setMessage(delegateLabel());
        listedToggle.active = share;
        delegateToggle.active = share;
        password.setEditable(share);
    }

    private Component shareLabel() {
        return Component.translatable(share
                ? "nodera.create.share.on" : "nodera.create.share.off");
    }

    private Component listedLabel() {
        return Component.translatable(listed
                ? "nodera.share.listed.on" : "nodera.share.listed.off");
    }

    private Component delegateLabel() {
        return Component.translatable(delegate
                ? "nodera.share.delegate.on" : "nodera.share.delegate.off");
    }

    private void saveAndClose() {
        if (share) {
            PendingCreateShare.set(new ShareOptions(password.getValue(), delegate, listed, 5));
        } else {
            PendingCreateShare.clear();
        }
        onClose();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, this.height / 4 - 28,
                0xFFFFFF);
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(parent);
        }
    }
}
