package com.github.simcityexpansion.buildpack.ui.component;

import java.util.List;

import com.github.simcityexpansion.buildpack.convert.StructureAnalysis.MaterialEntry;
import com.github.simcityexpansion.buildpack.ui.UiFormats;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvent;
import com.lowdragmc.lowdraglib2.gui.ui.rendering.GUIContext;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * 自绘的材料清单（分页）——与 {@link TreeView} 同套原生自绘 + 分页方案，
 * 每行「数量 × 名称」，缺失方块标红置顶（数据已按缺失优先、数量降序排好）。
 * 仅展示、不可选，底部超过一页时显示翻页条（点按钮或滚轮翻页）。
 */
public final class MaterialListView extends UIElement {

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

  private List<MaterialEntry> materials = List.of();
  private int page;

  public MaterialListView() {
    layout(l -> l.widthStretch().flexGrow(1.0f));
    addEventListener("mouseDown", this::onMouseDown);
    addEventListener("mouseWheel", this::onWheel);
  }

  /** 设置材料数据并回到第一页。 */
  public void setMaterials(List<MaterialEntry> materials) {
    this.materials = materials;
    this.page = 0;
  }

  // ---- 尺寸兜底 + 命中（同 TreeView：裸叶子 flexGrow 在该 Taffy 下可能算塌） ----

  private float availW() {
    float w = getContentWidth();
    UIElement parent = getParent();
    if (w < 1.0f && parent != null) {
      return parent.getContentWidth() - (getContentX() - parent.getContentX());
    }
    return w;
  }

  private float availH() {
    float h = getContentHeight();
    if (h < ROW_HEIGHT) {
      for (UIElement a = getParent(); a != null; a = a.getParent()) {
        if (a.getContentHeight() >= ROW_HEIGHT) {
          return a.getContentY() + a.getContentHeight() - getContentY();
        }
      }
    }
    return h;
  }

  @Override
  public boolean isIntersectWithPoint(double localX, double localY) {
    return isMouseOverRect(getPositionX(), getPositionY(), availW(), availH(), localX, localY);
  }

  // ---- 分页 ----

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

  // ---- 渲染 ----

  @Override
  public void drawContents(GUIContext ctx) {
    GuiGraphics g = ctx.graphics;
    Font font = ctx.mc.font;
    int x = Math.round(getContentX());
    int y = Math.round(getContentY());
    int w = Math.round(availW());
    int h = Math.round(availH());
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

    g.flush();
    ctx.enableScissor(x, y, w, listH);
    for (int i = start; i < end; i++) {
      MaterialEntry m = materials.get(i);
      int rowY = y + (i - start) * ROW_HEIGHT;
      if ((i & 1) == 1) {
        g.fill(x, rowY, x + w, rowY + ROW_HEIGHT, ALT_ROW_BG);
      }
      int textY = rowY + (ROW_HEIGHT - font.lineHeight) / 2 + 1;
      String count = UiFormats.integer(m.count());
      g.drawString(font, count, x + PAD_LEFT, textY, COUNT_COLOR, true);
      int nameX = x + PAD_LEFT + font.width("00000") + 6;
      String name = m.missing() ? m.blockId() : m.displayName().getString();
      g.drawString(font, name, nameX, textY, m.missing() ? MISSING_COLOR : ROW_COLOR, true);
    }
    g.flush();
    ctx.disableScissor();

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

  // ---- 交互（仅翻页） ----

  private void onMouseDown(UIEvent e) {
    if (e.button != 0) {
      return;
    }
    int x = Math.round(getContentX());
    int y = Math.round(getContentY());
    int w = Math.round(availW());
    int h = Math.round(availH());
    int perPage = rowsPerPage(h);
    int pages = pageCount(perPage);
    page = Math.max(0, Math.min(pages - 1, page));
    if (pages <= 1 || e.y < y + h - PAGE_BAR_H) {
      return;
    }
    Font font = net.minecraft.client.Minecraft.getInstance().font;
    String next = Component.translatable("buildpack.tree.next_page").getString();
    if (e.x <= x + font.width(Component.translatable("buildpack.tree.prev_page").getString()) + 4
        && page > 0) {
      page--;
    } else if (e.x >= x + w - font.width(next) - 4 && page < pages - 1) {
      page++;
    }
    e.stopPropagation();
  }

  private void onWheel(UIEvent e) {
    int perPage = rowsPerPage(Math.round(availH()));
    int pages = pageCount(perPage);
    if (pages <= 1) {
      return;
    }
    if (e.deltaY < 0.0f && page < pages - 1) {
      page++;
    } else if (e.deltaY > 0.0f && page > 0) {
      page--;
    }
    e.stopPropagation();
  }
}
