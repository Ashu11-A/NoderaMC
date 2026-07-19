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

    private TabListRenderer() {}

    /** @return the packet for {@code snapshot}; never null. */
    public static ClientboundTabListPacket render(TelemetrySnapshot s) {
        return new ClientboundTabListPacket(header(s), footer(s));
    }

    private static MutableComponent header(TelemetrySnapshot s) {
        MutableComponent h = Component.empty();
        h.append(Component.literal("NoderaMC").withStyle(ChatFormatting.WHITE, ChatFormatting.BOLD));
        h.append(Component.literal(" · epoch ").withStyle(ChatFormatting.DARK_GRAY));
        h.append(Component.literal(String.valueOf(s.session().epoch())).withStyle(ChatFormatting.WHITE));
        h.append(Component.literal(" · gateway ").withStyle(ChatFormatting.DARK_GRAY));
        String gw = s.session().selfGateway() ? "YOU" : ViewBuilder.shortId(s.session().gatewayId());
        h.append(Component.literal(gw).withStyle(Palette.chat(Semantic.GATEWAY)));
        h.append(Component.literal(" · " + s.session().memberCount() + " peers").withStyle(ChatFormatting.DARK_GRAY));
        return h;
    }

    private static MutableComponent footer(TelemetrySnapshot s) {
        MutableComponent f = Component.empty();
        f.append(Component.literal("▲ " + ViewBuilder.formatRate(s.net().bytesPerSecTx())).withStyle(Palette.chat(Semantic.TX)));
        f.append(Component.literal(" · ").withStyle(ChatFormatting.DARK_GRAY));
        f.append(Component.literal("▼ " + ViewBuilder.formatRate(s.net().bytesPerSecRx())).withStyle(Palette.chat(Semantic.RX)));
        f.append(Component.literal(" · regions: ").withStyle(ChatFormatting.DARK_GRAY));
        f.append(Component.literal(s.regions().primary().size() + " owned")
                .withStyle(Palette.chat(Semantic.OWNED)));
        f.append(Component.literal(" / " + s.regions().validator().size() + " val")
                .withStyle(Palette.chat(Semantic.VALIDATING)));
        f.append(Component.literal(" · health ").withStyle(ChatFormatting.DARK_GRAY));
        f.append(Component.literal(s.health().state().name())
                .withStyle(Palette.chat(ViewBuilder.healthSemantic(s.health().state()))));
        return f;
    }
}
