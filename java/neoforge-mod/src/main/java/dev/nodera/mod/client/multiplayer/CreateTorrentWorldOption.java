package dev.nodera.mod.client.multiplayer;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

/**
 * The "torrent hosting" option on the create-world screen (Task 26): a toggle plus an encryption
 * password field. The toggle and the password are independent (L-43's "password option"): torrent
 * hosting with an empty password is plaintext hosting; a non-empty password turns on Task 23
 * content encryption at world creation. The live creation pipeline (building the
 * {@code PieceManifest}, registering the name with the tracker, granting the integrated server
 * {@code FULL_ARCHIVE} + bootstrap/tracker roles) is the NeoForge live lane; this component owns
 * only the choice.
 *
 * <p>Thread-context: client (render) thread only.
 */
public final class CreateTorrentWorldOption {

    private final Button toggle;
    private final EditBox password;
    private boolean enabled;

    public CreateTorrentWorldOption(Font font, int x, int y, int width) {
        this.toggle = Button.builder(label(false), b -> toggle())
                .bounds(x, y, width, 20)
                .build();
        this.password = new EditBox(font, x, y + 24, width, 20,
                Component.translatable("nodera.multiplayer.create.password"));
        this.password.setHint(Component.translatable("nodera.multiplayer.create.password.hint"));
        this.password.setVisible(false);
    }

    private void toggle() {
        enabled = !enabled;
        toggle.setMessage(label(enabled));
        password.setVisible(enabled);
        if (!enabled) {
            password.setValue("");
        }
    }

    private static Component label(boolean enabled) {
        return Component.translatable(
                enabled ? "nodera.multiplayer.create.torrent.on" : "nodera.multiplayer.create.torrent.off");
    }

    /** The toggle widget (add to the screen). */
    public Button toggleWidget() {
        return toggle;
    }

    /** The password widget (add to the screen; hidden until the toggle is on). */
    public EditBox passwordWidget() {
        return password;
    }

    /** True when the created world should be torrent-hosted. */
    public boolean torrentHostingEnabled() {
        return enabled;
    }

    /** The chosen password; empty means plaintext torrent hosting. */
    public String password() {
        return password.getValue();
    }
}
