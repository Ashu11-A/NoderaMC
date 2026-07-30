package dev.nodera.mod.client.multiplayer;

import dev.nodera.endpoint.client.ClientJoinPasswords;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.ScreenEvent;

/**
 * "This world needs its password" — the joiner's prompt for the live-join gate (L-52).
 *
 * <p>Reached from the disconnect screen of a refused join rather than from a guess before one: the
 * tracker directory does not say which worlds are password-protected, so asking every player for a
 * password before every join would be wrong far more often than right. The host's refusal is the
 * signal, and it names the world.
 *
 * <p>Typing a password here remembers it for that world ({@link ClientJoinPasswords}, in memory
 * only) and re-dials the same endpoint; the gate exchange then happens silently.
 *
 * <p>Thread-context: client render thread.
 */
public final class JoinPasswordScreen extends Screen {

    private static final int WIDTH = 200;

    private final Screen parent;
    private final String worldIdHex;
    private final String worldName;

    private EditBox password;

    /**
     * @param parent     the screen to return to on cancel.
     * @param worldIdHex the world the host challenged for.
     * @param worldName  that world's display name (for the prompt line).
     */
    public JoinPasswordScreen(Screen parent, String worldIdHex, String worldName) {
        super(Component.translatable("nodera.join.password.title"));
        this.parent = parent;
        this.worldIdHex = worldIdHex;
        this.worldName = worldName;
    }

    /**
     * Client hook: after a join is refused at the gate, offer the prompt on the disconnect screen.
     * Quiet otherwise — an ordinary disconnect is untouched.
     *
     * @param event the screen-init event.
     */
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof net.minecraft.client.gui.screens.DisconnectedScreen)) {
            return;
        }
        // The wider marker: a join refused because the typed password was WRONG answered the
        // challenge, so `unansweredWorldId` is null for it and the button never appeared — the one
        // case where the player most needs it. Both refusals end at the gate, and that is the test.
        String worldId = ClientJoinPasswords.pendingGateWorldId();
        NoderaJoinFlow.LastJoin target = NoderaJoinFlow.lastJoin();
        if (worldId == null || target == null) {
            return;
        }
        Screen screen = event.getScreen();
        event.addListener(Button.builder(
                        Component.translatable("nodera.join.password.enter"),
                        b -> net.minecraft.client.Minecraft.getInstance().setScreen(
                                new JoinPasswordScreen(screen, worldId, target.worldName())))
                .bounds(screen.width / 2 - 100, screen.height - 30, 200, 20)
                .build());
    }

    @Override
    protected void init() {
        int x = this.width / 2 - WIDTH / 2;
        int y = this.height / 2 - 10;

        this.password = new EditBox(this.font, x, y, WIDTH, 20,
                Component.translatable("nodera.join.password.field"));
        this.password.setMaxLength(128);
        this.password.setHint(Component.translatable("nodera.join.password.hint"));
        addRenderableWidget(this.password);
        setInitialFocus(this.password);

        addRenderableWidget(Button.builder(Component.translatable("nodera.join.password.join"),
                        b -> joinWithPassword())
                .bounds(x, y + 28, WIDTH / 2 - 2, 20).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> onClose())
                .bounds(x + WIDTH / 2 + 2, y + 28, WIDTH / 2 - 2, 20).build());
    }

    private void joinWithPassword() {
        String typed = this.password.getValue();
        if (typed.isEmpty()) {
            return;
        }
        ClientJoinPasswords.remember(worldIdHex, typed);
        NoderaJoinFlow.retryLastJoin(this.parent);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(this.font, this.title, this.width / 2,
                this.height / 2 - 44, 0xFFFFFF);
        graphics.drawCenteredString(this.font,
                Component.translatable("nodera.join.password.detail", worldName),
                this.width / 2, this.height / 2 - 30, 0xA0A0A0);
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(parent);
        }
    }
}
