package com.github.simcityexpansion.buildpack.ui.component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import com.github.simcityexpansion.buildpack.ui.NodeIcons;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvent;
import com.lowdragmc.lowdraglib2.gui.ui.rendering.GUIContext;
import com.lowdragmc.lowdraglib2.gui.util.TreeNode;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * 自绘的文件列表/树控件——<b>不依赖 ldlib2 的 TreeList / ScrollerView</b>，
 * 直接用 Minecraft 原生渲染绘制「缩进 + 展开箭头 + 图标 + 文本」的行，
 * 自管理展开/折叠、选中、悬停、<b>分页</b>，并铺满父容器宽度。
 *
 * <p>列表项超过一页时，底部出现「上一页 / 第 X/Y 页 / 下一页」翻页条；
 * 翻页可点按钮或用滚轮。之所以用分页而非像素滚动：在该 GUI 框架下分页全靠点击事件，更可靠。
 *
 * <p>数据用 {@link TreeNode}（纯数据结构，非 UI 组件）：合成根被隐藏，只渲染其后代。
 */
public final class TreeView extends UIElement {

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

  public TreeView(Consumer<Object> onSelect) {
    this.onSelect = onSelect;
    layout(l -> l.widthStretch().flexGrow(1.0f));
    addEventListener("mouseDown", this::onMouseDown);
    addEventListener("mouseWheel", this::onWheel);
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

  // ---- 尺寸（兜底：裸叶子 flexGrow 在该 Taffy 下不撑开，退回祖先高度） ----

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

  /**
   * 用自算的可用尺寸做鼠标命中判定（而非被 flexGrow 算塌的真实布局尺寸），
   * 让整棵树都能收到点击/滚轮事件。<b>关键：不改动布局</b>——之前每 tick 改高度会触发
   * 重排、导致界面持续往下滚，这里只覆盖命中测试，渲染同样用自算尺寸，二者一致。
   */
  @Override
  public boolean isIntersectWithPoint(double localX, double localY) {
    return isMouseOverRect(getPositionX(), getPositionY(), availW(), availH(), localX, localY);
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
    int end = Math.min(rows.size(), start + perPage);

    g.flush();
    ctx.enableScissor(x, y, w, listH);
    for (int i = start; i < end; i++) {
      int rowY = y + (i - start) * ROW_HEIGHT;
      Row row = rows.get(i);
      boolean hovered = ctx.mouseX >= x && ctx.mouseX < x + w
          && ctx.mouseY >= rowY && ctx.mouseY < rowY + ROW_HEIGHT;
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
    g.flush();
    ctx.disableScissor();

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
    boolean paged = pages > 1;

    // 翻页条
    if (paged && e.y >= y + h - PAGE_BAR_H) {
      Font font = net.minecraft.client.Minecraft.getInstance().font;
      String next = Component.translatable("buildpack.tree.next_page").getString();
      if (e.x <= x + font.width(Component.translatable("buildpack.tree.prev_page").getString()) + 4
          && page > 0) {
        page--;
      } else if (e.x >= x + w - font.width(next) - 4 && page < pages - 1) {
        page++;
      }
      e.stopPropagation();
      return;
    }

    // 行
    int rel = (int) Math.floor((double) (e.y - y) / ROW_HEIGHT);
    int idx = page * perPage + rel;
    if (rel < 0 || rel >= perPage || idx >= rows.size() || e.x < x || e.x >= x + w) {
      return;
    }
    Row row = rows.get(idx);
    int rowX = x + PAD_LEFT + row.depth * INDENT;
    boolean arrowHit = row.branch && e.x >= rowX && e.x < rowX + ARROW_BOX;
    // 箭头：展开/折叠；无内容的分支（文件夹/分类）整行也展开/折叠；其余（文件/建筑/整包）选中。
    if (arrowHit || (row.branch && row.node.getContent() == null)) {
      toggle(row.node);
    } else {
      selected = row.node;
      onSelect.accept(row.node.getContent());
    }
    e.stopPropagation();
  }

  /** 滚轮翻页：上滚上一页，下滚下一页。 */
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

  private void toggle(TreeNode<String, Object> node) {
    if (!expanded.remove(node)) {
      expanded.add(node);
    }
    rebuild();
  }
}
