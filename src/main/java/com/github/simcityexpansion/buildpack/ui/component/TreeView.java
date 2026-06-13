package com.github.simcityexpansion.buildpack.ui.component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import com.github.simcityexpansion.buildpack.ui.UiIcons;
import com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvent;
import com.lowdragmc.lowdraglib2.gui.ui.rendering.GUIContext;
import com.lowdragmc.lowdraglib2.gui.util.TreeNode;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * 自绘的文件列表/树控件——<b>不依赖 ldlib2 的 TreeList / ScrollerView</b>，
 * 直接用 Minecraft 原生渲染绘制「缩进 + 展开箭头 + 图标 + 文本」的行，
 * 自管理展开/折叠、选中、悬停、滚动与裁剪，并铺满父容器宽度。
 *
 * <p>之所以自实现：ldlib2 的 TreeList 把节点宽度写死成 widthPercent(100%)，
 * 放进 ScrollerView 后宽度塌缩、文字被裁成 0 宽（只剩图标）。作为父容器里的一个叶子元素
 * 自绘后，尺寸由父布局确定（铺满），不再有百分比/滚动容器的尺寸解析问题。
 *
 * <p>数据仍用 {@link TreeNode}（纯数据结构，非 UI 组件）：合成根被隐藏，只渲染其后代。
 */
public final class TreeView extends UIElement {

  private static final int ROW_HEIGHT = 12;
  private static final int INDENT = 10;
  private static final int ICON = 9;
  private static final int ARROW_BOX = 9;
  private static final int PAD_LEFT = 2;
  private static final int SCROLLBAR_W = 4;
  private static final int TEXT_COLOR = 0xFFFFFFFF;
  private static final int ARROW_COLOR = 0xFFC0C0C0;
  private static final int HOVER_COLOR = 0x40FFFFFF;
  private static final int SELECT_COLOR = 0x70FFFFFF;
  private static final int SCROLL_TRACK_COLOR = 0x50000000;
  private static final int SCROLL_THUMB_COLOR = 0x90FFFFFF;

  /** 一条可见行：节点 + 缩进层级 + 是否为可展开分支。 */
  private record Row(TreeNode<String, Object> node, int depth, boolean branch) {}

  private final Consumer<Object> onSelect;
  private final Set<TreeNode<String, Object>> expanded = new HashSet<>();
  private final List<Row> rows = new ArrayList<>();
  private TreeNode<String, Object> root;
  private TreeNode<String, Object> selected;
  private float scroll;
  private boolean draggingThumb;

  public TreeView(Consumer<Object> onSelect) {
    this.onSelect = onSelect;
    layout(l -> l.widthStretch().flexGrow(1.0f));
    addEventListener("mouseDown", this::onMouseDown);
    addEventListener("mouseWheel", this::onWheel);
    addEventListener("dragUpdate", this::onDrag);
    addEventListener("mouseUp", e -> draggingThumb = false);
    addEventListener("mouseLeave", e -> draggingThumb = false);
  }

  /** 替换整棵树（合成根的后代为顶层项）；折叠所有、清空选中、回到顶部。 */
  public void setRoot(TreeNode<String, Object> root) {
    this.root = root;
    expanded.clear();
    selected = null;
    scroll = 0.0f;
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

  // ---- 渲染 ----

  /** 临时调试：在控件左上角打印尺寸/行数，便于定位渲染问题；确认后置 false 移除。 */
  private static final boolean DEBUG = true;

  @Override
  public void drawContents(GUIContext ctx) {
    GuiGraphics g = ctx.graphics;
    Font font = ctx.mc.font;
    int x = Math.round(getContentX());
    int y = Math.round(getContentY());
    float availW = getContentWidth();
    float availH = getContentHeight();
    // 自身被 flex 布局算成 0 尺寸时（裸叶子 flexGrow 在该 Taffy 下可能不撑开），
    // 退回用父容器（浏览框）的内容区尺寸。
    UIElement parent = getParent();
    if (parent != null) {
      if (availW < 1.0f) {
        availW = parent.getContentWidth() - (getContentX() - parent.getContentX());
      }
      if (availH < 1.0f) {
        availH = parent.getContentHeight() - (getContentY() - parent.getContentY());
      }
    }
    int w = Math.round(availW);
    int h = Math.round(availH);

    if (w <= 0 || h <= 0) {
      if (DEBUG) {
        g.drawString(font, "TV(0) rows=" + rows.size() + " w=" + w + " h=" + h
            + " x=" + x + " y=" + y, x + 2, y + 1, 0xFFFF5555, true);
      }
      return;
    }

    int total = rows.size() * ROW_HEIGHT;
    float maxScroll = Math.max(0.0f, total - h);
    scroll = Math.max(0.0f, Math.min(maxScroll, scroll));
    boolean hasBar = total > h;
    int listW = hasBar ? w - SCROLLBAR_W - 1 : w;

    g.flush();
    ctx.enableScissor(x, y, listW, h);
    for (int i = 0; i < rows.size(); i++) {
      int rowY = Math.round(y - scroll + (float) i * ROW_HEIGHT);
      if (rowY + ROW_HEIGHT < y || rowY > y + h) {
        continue;
      }
      Row row = rows.get(i);
      boolean hovered = ctx.mouseX >= x && ctx.mouseX < x + listW
          && ctx.mouseY >= rowY && ctx.mouseY < rowY + ROW_HEIGHT;
      if (row.node == selected) {
        g.fill(x, rowY, x + listW, rowY + ROW_HEIGHT, SELECT_COLOR);
      } else if (hovered) {
        g.fill(x, rowY, x + listW, rowY + ROW_HEIGHT, HOVER_COLOR);
      }

      int rowX = x + PAD_LEFT + row.depth * INDENT;
      if (row.branch) {
        drawArrow(g, rowX + 1, rowY + (ROW_HEIGHT - 7) / 2, expanded.contains(row.node));
      }
      int iconX = rowX + ARROW_BOX;
      int iconY = rowY + (ROW_HEIGHT - ICON) / 2;
      IGuiTexture icon = UiIcons.node(row.node.getContent());
      ctx.drawTexture(icon, iconX, iconY, ICON, ICON);

      int textX = iconX + ICON + 2;
      int textY = rowY + (ROW_HEIGHT - font.lineHeight) / 2 + 1;
      g.drawString(font, row.node.getKey(), textX, textY, TEXT_COLOR, true);
    }
    g.flush();
    ctx.disableScissor();

    if (hasBar) {
      int barX = x + w - SCROLLBAR_W;
      g.fill(barX, y, barX + SCROLLBAR_W, y + h, SCROLL_TRACK_COLOR);
      int thumbH = Math.max(8, Math.round((float) h * h / total));
      int thumbY = y + Math.round(scroll / maxScroll * (h - thumbH));
      g.fill(barX, thumbY, barX + SCROLLBAR_W, thumbY + thumbH, SCROLL_THUMB_COLOR);
    }

    if (DEBUG) {
      g.drawString(font, "TV rows=" + rows.size() + " w=" + w + " h=" + h,
          x + 2, y + 1, 0xFFFFFF00, true);
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

  // ---- 交互 ----

  private void onMouseDown(UIEvent e) {
    if (e.button != 0) {
      return;
    }
    int x = Math.round(getContentX());
    int y = Math.round(getContentY());
    int w = Math.round(getContentWidth());
    int h = Math.round(getContentHeight());
    int total = rows.size() * ROW_HEIGHT;
    boolean hasBar = total > h;

    if (hasBar && e.x >= x + w - SCROLLBAR_W) {
      draggingThumb = true;
      scrollToMouse(e.y, y, h, total);
      e.stopPropagation();
      return;
    }

    int listW = hasBar ? w - SCROLLBAR_W - 1 : w;
    int idx = (int) Math.floor((e.y - y + scroll) / ROW_HEIGHT);
    if (idx < 0 || idx >= rows.size() || e.x < x || e.x >= x + listW) {
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

  private void onWheel(UIEvent e) {
    int h = Math.round(getContentHeight());
    float maxScroll = Math.max(0.0f, rows.size() * ROW_HEIGHT - h);
    if (maxScroll <= 0.0f) {
      return;
    }
    scroll = Math.max(0.0f, Math.min(maxScroll, scroll - e.deltaY * ROW_HEIGHT * 2.0f));
    e.stopPropagation();
  }

  private void onDrag(UIEvent e) {
    if (!draggingThumb) {
      return;
    }
    scrollToMouse(e.y, Math.round(getContentY()), Math.round(getContentHeight()),
        rows.size() * ROW_HEIGHT);
    e.stopPropagation();
  }

  private void toggle(TreeNode<String, Object> node) {
    if (!expanded.remove(node)) {
      expanded.add(node);
    }
    rebuild();
  }

  /** 让滚动位置使滚动条把手中心对准鼠标 y。 */
  private void scrollToMouse(float mouseY, int y, int h, int total) {
    float maxScroll = Math.max(0.0f, total - h);
    int thumbH = Math.max(8, Math.round((float) h * h / total));
    float denom = h - thumbH;
    float t = denom <= 0.0f ? 0.0f : (mouseY - y - thumbH / 2.0f) / denom;
    scroll = Math.max(0.0f, Math.min(maxScroll, t * maxScroll));
  }
}
