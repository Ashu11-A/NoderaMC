package dev.nodera.mod.debug.render;

import dev.nodera.diagnostics.view.Cell;
import dev.nodera.diagnostics.view.DiagnosticsView;
import dev.nodera.diagnostics.view.Panel;
import dev.nodera.diagnostics.view.Row;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.List;

/**
 * Renders the MC-free {@link DiagnosticsView} (Panel/Row/Cell + {@link dev.nodera.diagnostics.state.Semantic})
 * into Minecraft {@link Component}s — the uniform colour-coded chat table every {@code /nodera} and
 * {@code /noderac} subcommand flows through (Task 18).
 *
 * <p>Colour is resolved only via {@link Palette}; column padding is a single-space grid (values are
 * short labels) — the whole table reads as one system regardless of which command produced it.
 *
 * <p>Thread-context: stateless; safe from any thread.
 */
public final class ComponentRenderer {

    private ComponentRenderer() {}

    /** @return the whole view as one multi-line {@link Component} (panels separated by a blank line). */
    public static MutableComponent renderView(DiagnosticsView view) {
        MutableComponent out = Component.empty();
        List<Panel> panels = view.panels();
        for (int i = 0; i < panels.size(); i++) {
            if (i > 0) {
                out.append(Component.literal("\n"));
            }
            out.append(renderPanel(panels.get(i)));
        }
        return out;
    }

    /** @return one panel as a titled multi-line {@link Component}. */
    public static MutableComponent renderPanel(Panel panel) {
        MutableComponent out = Component.empty();
        out.append(heading(panel.title()));
        for (Row row : panel.rows()) {
            out.append(Component.literal("\n")).append(renderRow(row));
        }
        return out;
    }

    /** @return one row as a space-joined, per-cell-coloured {@link Component}. */
    public static MutableComponent renderRow(Row row) {
        MutableComponent out = Component.empty();
        List<Cell> cells = row.cells();
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                out.append(Component.literal(" "));
            }
            out.append(renderCell(cells.get(i)));
        }
        return out;
    }

    /** @return one cell as a coloured {@link Component} (bold when the cell is bold). */
    public static MutableComponent renderCell(Cell cell) {
        ChatFormatting fmt = Palette.chat(cell.semantic());
        return cell.bold()
                ? Component.literal(cell.text()).withStyle(fmt, ChatFormatting.BOLD)
                : Component.literal(cell.text()).withStyle(fmt);
    }

    private static MutableComponent heading(String title) {
        return Component.literal(title).withStyle(ChatFormatting.WHITE, ChatFormatting.BOLD);
    }
}
