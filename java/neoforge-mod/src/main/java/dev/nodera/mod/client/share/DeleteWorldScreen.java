package dev.nodera.mod.client.share;

import dev.nodera.mod.common.CompanionClient;
import dev.nodera.mod.common.CompanionLink;
import dev.nodera.mod.common.NoderaPeerService;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.Locale;

/**
 * "Delete this world from the Nodera network" — the confirmation for the one action that cannot be
 * taken back.
 *
 * <h2>Why this is a screen and not a button</h2>
 *
 * <p>Everything else in the share flow is reversible: stop sharing and share again, change the
 * password, go invite-only. This is not. The deletion is signed with the world's private key and
 * flooded to every peer that will ever hear of the world, and each of them verifies it and destroys
 * its copy. There is no "undo" message, because a message that could resurrect a deleted world would
 * be a message an attacker could send.
 *
 * <p>So the confirmation is a typed one — the player writes the world's name — rather than a second
 * button, which is the difference between a decision and a double-click.
 *
 * <p>The mod holds no authority here. It asks the worker, and the worker refuses unless it holds
 * this world's private key; a player looking at somebody else's world sees the refusal, not a
 * deleted world. Everything this screen shows is the worker's answer, never an assumption about it.
 *
 * <p>Thread-context: client render thread. The control call is a short loopback exchange.
 */
public final class DeleteWorldScreen extends Screen {

    private static final int WIDTH = 220;

    private final Screen parent;
    private final String worldIdHex;
    private final String worldName;

    private EditBox confirmation;
    private EditBox reason;
    private Button deleteButton;

    /** The worker's last answer, shown verbatim; empty until something has been attempted. */
    private Component outcome = Component.empty();
    private int outcomeColour = 0xC9814E;

    /**
     * @param parent     the screen to return to.
     * @param worldIdHex the world to delete.
     * @param worldName  its display name — also what the player must type to confirm.
     */
    public DeleteWorldScreen(Screen parent, String worldIdHex, String worldName) {
        super(Component.translatable("nodera.delete.title"));
        this.parent = parent;
        this.worldIdHex = worldIdHex == null ? "" : worldIdHex;
        this.worldName = worldName == null || worldName.isBlank() ? this.worldIdHex : worldName;
    }

    @Override
    protected void init() {
        int x = this.width / 2 - WIDTH / 2;
        int y = this.height / 4 + 24;

        this.confirmation = new EditBox(this.font, x, y, WIDTH, 20,
                Component.translatable("nodera.delete.confirm.field"));
        this.confirmation.setMaxLength(64);
        this.confirmation.setHint(Component.literal(this.worldName));
        this.confirmation.setResponder(text -> refreshDeleteButton());
        addRenderableWidget(this.confirmation);
        y += 26;

        this.reason = new EditBox(this.font, x, y, WIDTH, 20,
                Component.translatable("nodera.delete.reason"));
        this.reason.setMaxLength(200); // the same bound the record carries
        this.reason.setHint(Component.translatable("nodera.delete.reason.hint"));
        addRenderableWidget(this.reason);
        y += 32;

        this.deleteButton = addRenderableWidget(Button.builder(
                        Component.translatable("nodera.delete.confirm"), b -> delete())
                .bounds(x, y, WIDTH, 20).build());
        y += 24;

        addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> onClose())
                .bounds(x, y, WIDTH, 20).build());

        refreshDeleteButton();
        setInitialFocus(this.confirmation);
    }

    /** Armed only once the typed name matches, and never while the worker is absent. */
    private void refreshDeleteButton() {
        if (this.deleteButton == null) {
            return;
        }
        boolean named = this.confirmation != null
                && this.confirmation.getValue().trim().equalsIgnoreCase(this.worldName.trim());
        this.deleteButton.active = named && CompanionLink.isPresent();
    }

    private void delete() {
        CompanionClient worker = CompanionLink.client();
        if (worker == null) {
            fail(Component.translatable("nodera.delete.result.noworker"));
            return;
        }
        CompanionClient.DeleteOutcome result = worker.deleteWorld(this.worldIdHex,
                this.reason.getValue());
        if (!result.deleted()) {
            // The worker's own words. A refusal here is nearly always "you do not administer this
            // world", and paraphrasing it would hide which world the player is actually looking at.
            fail(Component.translatable("nodera.delete.result.refused", result.error()));
            return;
        }
        this.outcome = Component.translatable("nodera.delete.result.done", result.peersNotified());
        this.outcomeColour = 0x6FBF73;
        this.deleteButton.active = false;
        this.confirmation.setEditable(false);
        this.reason.setEditable(false);
    }

    private void fail(Component message) {
        this.outcome = message;
        this.outcomeColour = 0xC9814E;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        int centre = this.width / 2;
        graphics.drawCenteredString(this.font, this.title, centre, this.height / 4 - 40, 0xFFFFFF);
        graphics.drawCenteredString(this.font,
                Component.translatable("nodera.delete.warning"), centre, this.height / 4 - 26,
                0xC9814E);
        graphics.drawCenteredString(this.font,
                Component.translatable("nodera.delete.confirm.prompt", this.worldName),
                centre, this.height / 4 + 10, 0xA0A0A0);
        if (!this.outcome.getString().isEmpty()) {
            graphics.drawCenteredString(this.font, this.outcome, centre, this.height - 52,
                    this.outcomeColour);
        }
        graphics.drawCenteredString(this.font,
                Component.literal(shortId()), centre, this.height - 32, 0x808080);
    }

    private String shortId() {
        String hex = this.worldIdHex.toLowerCase(Locale.ROOT);
        return hex.length() <= 16 ? hex : hex.substring(0, 16) + "…";
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            // Refreshed on the way out: a deleted world is no longer hosted, and the share screen
            // behind this one would otherwise still be offering to update its options.
            NoderaPeerService.get();
            this.minecraft.setScreen(this.parent);
        }
    }
}
