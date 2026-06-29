package com.github.simcityexpansion.buildpack.ui.component;

import java.util.List;
import java.util.function.Consumer;

import com.github.simcityexpansion.buildpack.convert.StructureAnalysis.MaterialEntry;
import com.github.simcityexpansion.buildpack.ui.UiFormats;
import com.github.simcityexpansion.buildpack.ui.UiScale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

/**
 * Custom-drawn material list (paginated): each row shows "count x name"; missing
 * blocks are highlighted in red and sorted to the top (the data is already sorted
 * by missing-first, then count descending). Display-only with optional row
 * selection; a navigation bar appears at the bottom when there is more than one
 * page (button click or scroll-wheel to turn pages).
 */
public final class MaterialListView extends AbstractWidget {

  private static final int ROW_HEIGHT = 12;
  private static final int PAD_LEFT = 4;
  private static final int PAGE_BAR_H = 13;
  private static final int ROW_COLOR = 0xFFE0E0E0;
  private static final int MISSING_COLOR = 0xFFFF6060;
  private static final int COUNT_COLOR = 0xFFFFFFFF;
  private static final int ALT_ROW_BG = 0x18FFFFFF;
  private static final int PAGE_SEP_COLOR = 0x60FFFFFF;
  private static final int PAGE_INFO_COLOR = 0xFFC0C0C0;
  private static final int PAGE_BTN_COLOR = 0xFFFFFFFF;
  private static final int PAGE_BTN_DISABLED = 0xFF808080;
  private static final int HOVER_COLOR = 0x40FFFFFF;

  private List<MaterialEntry> materials = List.of();
  private int page;
  private Consumer<MaterialEntry> onSelect;

  public MaterialListView(int x, int y, int width, int height) {
    super(x, y, width, height, Component.empty());
  }

  /** Sets the material data and resets to the first page. */
  public void setMaterials(List<MaterialEntry> materials) {
    this.materials = materials;
    this.page = 0;
  }

  /** Enables row selection (clicking a row invokes the callback for that entry and highlights the hovered row); if not set, the widget is read-only. */
  public void setOnSelect(Consumer<MaterialEntry> onSelect) {
    this.onSelect = onSelect;
  }

  private int rowsPerPage(int h) {
    int full = Math.max(1, h / ROW_HEIGHT);
    if (materials.size() <= full) {
      return full;
    }
    return Math.max(1, (h - PAGE_BAR_H) / ROW_HEIGHT);
  }

  private int pageCount(int perPage) {
    return Math.max(1, (materials.size() + perPage - 1) / perPage);
  }

  @Override
  protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
    Font font = Minecraft.getInstance().font;
    int x = getX();
    int y = getY();
    int w = getWidth();
    int h = getHeight();
    if (w <= 0 || h <= 0) {
      return;
    }

    int perPage = rowsPerPage(h);
    int pages = pageCount(perPage);
    page = Math.max(0, Math.min(pages - 1, page));
    boolean paged = pages > 1;
    int listH = paged ? h - PAGE_BAR_H : h;
    int start = page * perPage;
    int end = Math.min(materials.size(), start + perPage);

    UiScale.enableScissor(g, x, y, x + w, y + listH);
    for (int i = start; i < end; i++) {
      MaterialEntry m = materials.get(i);
      int rowY = y + (i - start) * ROW_HEIGHT;
      boolean hovered = onSelect != null && mouseX >= x && mouseX < x + w
          && mouseY >= rowY && mouseY < rowY + ROW_HEIGHT;
      if (hovered) {
        g.fill(x, rowY, x + w, rowY + ROW_HEIGHT, HOVER_COLOR);
      } else if ((i & 1) == 1) {
        g.fill(x, rowY, x + w, rowY + ROW_HEIGHT, ALT_ROW_BG);
      }
      int textY = rowY + (ROW_HEIGHT - font.lineHeight) / 2 + 1;
      String count = UiFormats.integer(m.count());
      g.drawString(font, count, x + PAD_LEFT, textY, COUNT_COLOR, true);
      int nameX = x + PAD_LEFT + font.width("00000") + 6;
      String name = m.missing() ? m.blockId() : m.displayName().getString();
      g.drawString(font, name, nameX, textY, m.missing() ? MISSING_COLOR : ROW_COLOR, true);
    }
    g.disableScissor();

    if (paged) {
      drawPageBar(g, font, x, y + h - PAGE_BAR_H, w, pages);
    }
  }

  private void drawPageBar(GuiGraphics g, Font font, int x, int barY, int w, int pages) {
    g.fill(x, barY, x + w, barY + 1, PAGE_SEP_COLOR);
    int textY = barY + (PAGE_BAR_H - font.lineHeight) / 2 + 1;
    String prev = Component.translatable("buildpack.tree.prev_page").getString();
    String next = Component.translatable("buildpack.tree.next_page").getString();
    String info = Component.translatable("buildpack.tree.page_info", page + 1, pages).getString();
    g.drawString(font, prev, x + 2, textY, page > 0 ? PAGE_BTN_COLOR : PAGE_BTN_DISABLED, true);
    g.drawString(font, next, x + w - font.width(next) - 2, textY,
        page < pages - 1 ? PAGE_BTN_COLOR : PAGE_BTN_DISABLED, true);
    g.drawString(font, info, x + (w - font.width(info)) / 2, textY, PAGE_INFO_COLOR, true);
  }

  // ---- Interaction (pagination + optional row selection) ----

  @Override
  public boolean mouseClicked(double mouseX, double mouseY, int button) {
    if (!active || !visible || button != 0 || !isMouseOver(mouseX, mouseY)) {
      return false;
    }
    int x = getX();
    int y = getY();
    int w = getWidth();
    int h = getHeight();
    int perPage = rowsPerPage(h);
    int pages = pageCount(perPage);
    page = Math.max(0, Math.min(pages - 1, page));

    if (pages > 1 && mouseY >= y + h - PAGE_BAR_H) {
      Font font = Minecraft.getInstance().font;
      String prev = Component.translatable("buildpack.tree.prev_page").getString();
      String next = Component.translatable("buildpack.tree.next_page").getString();
      if (mouseX <= x + font.width(prev) + 4 && page > 0) {
        page--;
      } else if (mouseX >= x + w - font.width(next) - 4 && page < pages - 1) {
        page++;
      }
      return true;
    }

    if (onSelect != null && mouseX >= x && mouseX < x + w) {
      int rel = (int) Math.floor((mouseY - y) / ROW_HEIGHT);
      int idx = page * perPage + rel;
      if (rel >= 0 && rel < perPage && idx >= 0 && idx < materials.size()) {
        onSelect.accept(materials.get(idx));
      }
    }
    return true;
  }

  @Override
  public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
    if (!isMouseOver(mouseX, mouseY)) {
      return false;
    }
    int perPage = rowsPerPage(getHeight());
    int pages = pageCount(perPage);
    if (pages <= 1) {
      return false;
    }
    if (scrollY < 0.0 && page < pages - 1) {
      page++;
      return true;
    }
    if (scrollY > 0.0 && page > 0) {
      page--;
      return true;
    }
    return false;
  }

  @Override
  protected void updateWidgetNarration(NarrationElementOutput output) {}
}
