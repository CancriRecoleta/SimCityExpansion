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
  private static final int CHECK_SIZE = 9;
  private static final int CHECK_BOX = 11;
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
  private Runnable onCheckedChanged;
  private ContextHandler onContext;
  private final Set<Object> checked = new HashSet<>();
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
    checked.clear();
    selected = null;
    page = 0;
    rebuild();
  }

  /** 当前勾选的叶子内容（多选）；搜索/排序/刷新会清空。 */
  public Set<Object> checked() {
    return Set.copyOf(checked);
  }

  /** 清空勾选。 */
  public void clearChecked() {
    checked.clear();
  }

  /** 设置勾选变化回调（用于刷新批量按钮可用态）。 */
  public void setOnCheckedChanged(Runnable callback) {
    this.onCheckedChanged = callback;
  }

  /** 右键上下文回调：内容（分支为 {@code null}）+ 屏幕坐标。 */
  public interface ContextHandler {
    void onContext(Object content, int mouseX, int mouseY);
  }

  /** 设置右键上下文回调。 */
  public void setOnContext(ContextHandler handler) {
    this.onContext = handler;
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
      drawCheckbox(g, rowX, rowY + (ROW_HEIGHT - CHECK_SIZE) / 2, isRowChecked(row));
      int afterCheck = rowX + CHECK_BOX;
      if (row.branch) {
        drawArrow(g, afterCheck + 1, rowY + (ROW_HEIGHT - 7) / 2, expanded.contains(row.node));
      }
      int iconX = afterCheck + ARROW_BOX;
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
    if (!active || !visible || !isMouseOver(mouseX, mouseY)) {
      return false;
    }
    if (button == 1) {
      return handleRightClick(mouseX, mouseY);
    }
    if (button != 0) {
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
    // 复选框：切换勾选（多选），不改变当前选中项。
    if (mouseX >= rowX && mouseX < rowX + CHECK_SIZE) {
      toggleChecked(row);
      return true;
    }
    int afterCheck = rowX + CHECK_BOX;
    boolean arrowHit = row.branch && mouseX >= afterCheck && mouseX < afterCheck + ARROW_BOX;
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

  /** 右键：选中该行并请求上下文菜单。 */
  private boolean handleRightClick(double mouseX, double mouseY) {
    if (onContext == null) {
      return false;
    }
    int x = getX();
    int y = getY();
    int h = getHeight();
    int perPage = rowsPerPage(h);
    int pages = pageCount(perPage);
    page = Math.max(0, Math.min(pages - 1, page));
    if (pages > 1 && mouseY >= y + h - PAGE_BAR_H) {
      return false;
    }
    int rel = (int) Math.floor((mouseY - y) / ROW_HEIGHT);
    int idx = page * perPage + rel;
    if (rel < 0 || rel >= perPage || idx >= rows.size()
        || mouseX < x || mouseX >= x + getWidth()) {
      return false;
    }
    Row row = rows.get(idx);
    selected = row.node;
    onSelect.accept(row.node.getContent());
    onContext.onContext(row.node.getContent(), (int) mouseX, (int) mouseY);
    return true;
  }

  // ---- 勾选（多选）----

  /** 行勾选态：叶子看自身，分支看其全部叶子后代是否都勾选。 */
  private boolean isRowChecked(Row row) {
    if (!row.branch) {
      Object content = row.node.getContent();
      return content != null && checked.contains(content);
    }
    List<Object> leaves = new ArrayList<>();
    collectLeaves(row.node, leaves);
    return !leaves.isEmpty() && checked.containsAll(leaves);
  }

  /** 切换行勾选：叶子切自身；分支切其全部叶子后代。 */
  private void toggleChecked(Row row) {
    if (!row.branch) {
      Object content = row.node.getContent();
      if (content != null && !checked.remove(content)) {
        checked.add(content);
      }
    } else {
      List<Object> leaves = new ArrayList<>();
      collectLeaves(row.node, leaves);
      if (!leaves.isEmpty() && checked.containsAll(leaves)) {
        leaves.forEach(checked::remove);
      } else {
        checked.addAll(leaves);
      }
    }
    if (onCheckedChanged != null) {
      onCheckedChanged.run();
    }
  }

  private static void collectLeaves(TreeNode<String, Object> node, List<Object> out) {
    if (node.getChildren().isEmpty()) {
      if (node.getContent() != null) {
        out.add(node.getContent());
      }
      return;
    }
    for (TreeNode<String, Object> child : node.getChildren()) {
      collectLeaves(child, out);
    }
  }

  /** 9px 见方的复选框；勾选时填充绿色内块。 */
  private static void drawCheckbox(GuiGraphics g, int x, int y, boolean on) {
    g.fill(x, y, x + CHECK_SIZE, y + CHECK_SIZE, 0xFF202020);
    g.fill(x, y, x + CHECK_SIZE, y + 1, 0xFFA0A0A0);
    g.fill(x, y + CHECK_SIZE - 1, x + CHECK_SIZE, y + CHECK_SIZE, 0xFFA0A0A0);
    g.fill(x, y, x + 1, y + CHECK_SIZE, 0xFFA0A0A0);
    g.fill(x + CHECK_SIZE - 1, y, x + CHECK_SIZE, y + CHECK_SIZE, 0xFFA0A0A0);
    if (on) {
      g.fill(x + 2, y + 2, x + CHECK_SIZE - 2, y + CHECK_SIZE - 2, 0xFF55FF55);
    }
  }

  @Override
  protected void updateWidgetNarration(NarrationElementOutput output) {}
}
