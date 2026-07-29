package dev.nodera.mod.debug.render;

import dev.nodera.diagnostics.model.TelemetrySnapshot;
import dev.nodera.diagnostics.view.ViewBuilder;
import dev.nodera.diagnostics.state.Semantic;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.game.ClientboundTabListPacket;

/**
 * Builds the tab-list header/footer {@link ClientboundTabListPacket} from a
 * {@link TelemetrySnapshot} (Task 18 surface: tab list).
 *
 * <p>Header: {@code NoderaMC · epoch E · gateway <id|YOU> · N peers}. Footer:
 * {@code ▲ tx/s · ▼ rx/s · regions: owned/val · health <state>}. Colour via {@link Palette}.
 *
 * <p>Thread-context: stateless; safe from any thread. The caller sends the returned packet to a
 * {@code ServerPlayer} on the server main thread.
 */
public final class TabListRenderer {

    /** Lang keys for the tab-list segments (MC-GUI-5: the HUD reads from the lang file). */
    public static final String TITLE = "nodera.hud.title";
    public static final String EPOCH = "nodera.hud.epoch";
    public static final String GATEWAY = "nodera.hud.gateway";
    public static final String GATEWAY_SELF = "nodera.hud.gateway.you";
    public static final String PEERS = "nodera.hud.peers";
    public static final String RATE_TX = "nodera.hud.rate.tx";
    public static final String RATE_RX = "nodera.hud.rate.rx";
    public static final String REGIONS = "nodera.hud.regions";
    public static final String OWNED = "nodera.hud.owned";
    public static final String VALIDATING = "nodera.hud.validating";
    public static final String HEALTH = "nodera.hud.health";

    private TabListRenderer() {}

    /** Punctuation between segments — a separator, not a word. */
    private static final Component SEP =
            Component.literal(" · ").withStyle(ChatFormatting.DARK_GRAY);

    /** @return the packet for {@code snapshot}; never null. */
    public static ClientboundTabListPacket render(TelemetrySnapshot s) {
        return new ClientboundTabListPacket(header(s), footer(s));
    }

    private static MutableComponent header(TelemetrySnapshot s) {
        MutableComponent h = Component.empty();
        h.append(Component.translatable(TITLE).withStyle(ChatFormatting.WHITE, ChatFormatting.BOLD));
        h.append(SEP);
        h.append(Component.translatable(EPOCH, s.session().epoch()).withStyle(ChatFormatting.WHITE));
        h.append(SEP);
        Component gw = s.session().selfGateway()
                ? Component.translatable(GATEWAY_SELF)
                : Component.literal(ViewBuilder.shortId(s.session().gatewayId()));
        h.append(Component.translatable(GATEWAY, gw).withStyle(Palette.chat(Semantic.GATEWAY)));
        h.append(SEP);
        h.append(Component.translatable(PEERS, s.session().memberCount())
                .withStyle(ChatFormatting.DARK_GRAY));
        return h;
    }

    private static MutableComponent footer(TelemetrySnapshot s) {
        MutableComponent f = Component.empty();
        f.append(Component.translatable(RATE_TX, ViewBuilder.formatRate(s.net().bytesPerSecTx()))
                .withStyle(Palette.chat(Semantic.TX)));
        f.append(SEP);
        f.append(Component.translatable(RATE_RX, ViewBuilder.formatRate(s.net().bytesPerSecRx()))
                .withStyle(Palette.chat(Semantic.RX)));
        f.append(SEP);
        f.append(Component.translatable(REGIONS).withStyle(ChatFormatting.DARK_GRAY));
        f.append(Component.literal(" "));
        f.append(Component.translatable(OWNED, s.regions().primary().size())
                .withStyle(Palette.chat(Semantic.OWNED)));
        f.append(Component.literal(" "));
        f.append(Component.translatable(VALIDATING, s.regions().validator().size())
                .withStyle(Palette.chat(Semantic.VALIDATING)));
        f.append(SEP);
        f.append(Component.translatable(HEALTH).withStyle(ChatFormatting.DARK_GRAY));
        f.append(Component.literal(" "));
        f.append(Component.literal(s.health().state().name())
                .withStyle(Palette.chat(ViewBuilder.healthSemantic(s.health().state()))));
        return f;
    }
}
