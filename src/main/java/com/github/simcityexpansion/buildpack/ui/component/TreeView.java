package com.github.simcityexpansion.buildpack.ui.component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import com.github.simcityexpansion.buildpack.ui.NodeIcons;
import com.github.simcityexpansion.buildpack.ui.tree.TreeNode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

/**
 * 自绘文件列表/树控件：用 Minecraft 原生 {@link GuiGraphics} 绘制「缩进 + 展开箭头 +
 * 图标 + 文本」的行，自管理展开/折叠、选中、悬停、<b>分页</b>，并铺满给定区域。
 *
 * <p>列表项超过一页时，底部出现「上一页 / 第 X/Y 页 / 下一页」翻页条，可点按钮或用滚轮翻页。
 *
 * <p>数据用 {@link TreeNode}（纯数据结构，非 UI 组件）：合成根被隐藏，只渲染其后代。
 */
public final class TreeView extends AbstractWidget {

  private static final int ROW_HEIGHT = 13;
  private static final int INDENT = 10;
  private static final int ICON = 10;
  private static final int ARROW_BOX = 9;
  private static final int PAD_LEFT = 2;
  private static final int PAGE_BAR_H = 13;
  private static final int TEXT_COLOR = 0xFFFFFFFF;
  private static final int ARROW_COLOR = 0xFFC0C0C0;
  private static final int HOVER_COLOR = 0x40FFFFFF;
  private static final int SELECT_COLOR = 0x70FFFFFF;
  private static final int PAGE_SEP_COLOR = 0x60FFFFFF;
  private static final int PAGE_INFO_COLOR = 0xFFC0C0C0;
  private static final int PAGE_BTN_COLOR = 0xFFFFFFFF;
  private static final int PAGE_BTN_DISABLED = 0xFF808080;

  /** 一条可见行：节点 + 缩进层级 + 是否为可展开分支。 */
  private record Row(TreeNode<String, Object> node, int depth, boolean branch) {}

  private final Consumer<Object> onSelect;
  private final Set<TreeNode<String, Object>> expanded = new HashSet<>();
  private final List<Row> rows = new ArrayList<>();
  private TreeNode<String, Object> root;
  private TreeNode<String, Object> selected;
  private int page;

  public TreeView(int x, int y, int width, int height, Consumer<Object> onSelect) {
    super(x, y, width, height, Component.empty());
    this.onSelect = onSelect;
  }

  /** 替换整棵树（合成根的后代为顶层项）；折叠所有、清空选中、回到第一页。 */
  public void setRoot(TreeNode<String, Object> root) {
    this.root = root;
    expanded.clear();
    selected = null;
    page = 0;
    rebuild();
  }

  private void rebuild() {
    rows.clear();
    if (root != null) {
      for (TreeNode<String, Object> child : root.getChildren()) {
        addRows(child, 0);
      }
    }
  }

  private void addRows(TreeNode<String, Object> node, int depth) {
    boolean branch = !node.getChildren().isEmpty();
    rows.add(new Row(node, depth, branch));
    if (branch && expanded.contains(node)) {
      for (TreeNode<String, Object> child : node.getChildren()) {
        addRows(child, depth + 1);
      }
    }
  }

  // ---- 分页参数 ----

  /** 单页可容纳的行数（不含翻页条）。 */
  private int rowsPerPage(int h) {
    int full = Math.max(1, h / ROW_HEIGHT);
    if (rows.size() <= full) {
      return full;
    }
    return Math.max(1, (h - PAGE_BAR_H) / ROW_HEIGHT);
  }

  private int pageCount(int perPage) {
    return Math.max(1, (rows.size() + perPage - 1) / perPage);
  }

  // ---- 渲染 ----

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
    int end = Math.min(rows.size(), start + perPage);

    g.enableScissor(x, y, x + w, y + listH);
    for (int i = start; i < end; i++) {
      int rowY = y + (i - start) * ROW_HEIGHT;
      Row row = rows.get(i);
      boolean hovered = mouseX >= x && mouseX < x + w
          && mouseY >= rowY && mouseY < rowY + ROW_HEIGHT;
      if (row.node == selected) {
        g.fill(x, rowY, x + w, rowY + ROW_HEIGHT, SELECT_COLOR);
      } else if (hovered) {
        g.fill(x, rowY, x + w, rowY + ROW_HEIGHT, HOVER_COLOR);
      }

      int rowX = x + PAD_LEFT + row.depth * INDENT;
      if (row.branch) {
        drawArrow(g, rowX + 1, rowY + (ROW_HEIGHT - 7) / 2, expanded.contains(row.node));
      }
      int iconX = rowX + ARROW_BOX;
      int iconY = rowY + (ROW_HEIGHT - ICON) / 2;
      NodeIcons.draw(g, iconX, iconY, ICON, row.node.getContent(),
          row.branch, expanded.contains(row.node));

      int textX = iconX + ICON + 2;
      int textY = rowY + (ROW_HEIGHT - font.lineHeight) / 2 + 1;
      g.drawString(font, row.node.getKey(), textX, textY, TEXT_COLOR, true);
    }
    g.disableScissor();

    if (paged) {
      drawPageBar(g, font, x, y + h - PAGE_BAR_H, w, pages);
    }
  }

  /** 7px 见方的三角箭头：展开为向下 ▼，折叠为向右 ▶。 */
  private static void drawArrow(GuiGraphics g, int x, int y, boolean open) {
    if (open) {
      for (int r = 0; r < 4; r++) {
        g.fill(x + r, y + r, x + 7 - r, y + r + 1, ARROW_COLOR);
      }
    } else {
      for (int c = 0; c < 4; c++) {
        g.fill(x + c, y + c, x + c + 1, y + 7 - c, ARROW_COLOR);
      }
    }
  }

  /** 底部翻页条：[上一页]  第 X / Y 页  [下一页]。 */
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

  // ---- 交互 ----

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
    boolean paged = pages > 1;

    // 翻页条
    if (paged && mouseY >= y + h - PAGE_BAR_H) {
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

    // 行
    int rel = (int) Math.floor((mouseY - y) / ROW_HEIGHT);
    int idx = page * perPage + rel;
    if (rel < 0 || rel >= perPage || idx >= rows.size() || mouseX < x || mouseX >= x + w) {
      return true;
    }
    Row row = rows.get(idx);
    int rowX = x + PAD_LEFT + row.depth * INDENT;
    boolean arrowHit = row.branch && mouseX >= rowX && mouseX < rowX + ARROW_BOX;
    // 箭头：展开/折叠；无内容的分支（文件夹/分类）整行也展开/折叠；其余（文件/建筑/整包）选中。
    if (arrowHit || (row.branch && row.node.getContent() == null)) {
      toggle(row.node);
    } else {
      selected = row.node;
      onSelect.accept(row.node.getContent());
    }
    return true;
  }

  /** 滚轮翻页：上滚上一页，下滚下一页。 */
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

  private void toggle(TreeNode<String, Object> node) {
    if (!expanded.remove(node)) {
      expanded.add(node);
    }
    rebuild();
  }

  @Override
  protected void updateWidgetNarration(NarrationElementOutput output) {}
}
