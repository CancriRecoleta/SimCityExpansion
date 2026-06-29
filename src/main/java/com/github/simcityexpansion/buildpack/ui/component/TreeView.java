package com.github.simcityexpansion.buildpack.ui.component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import com.github.simcityexpansion.buildpack.ui.NodeIcons;
import com.github.simcityexpansion.buildpack.ui.UiScale;
import com.github.simcityexpansion.buildpack.ui.tree.TreeNode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

/**
 * Custom-drawn file list/tree widget: renders rows of "indent + expand arrow +
 * icon + text" using Minecraft's native {@link GuiGraphics}, self-managing
 * collapse/expand, selection, hover, and <b>pagination</b>, filling the given area.
 *
 * <p>When items exceed one page, a "Previous / Page X of Y / Next" navigation bar
 * appears at the bottom; it responds to button clicks and scroll-wheel events.
 *
 * <p>Data is supplied via {@link TreeNode} (a pure data structure, not a UI component):
 * the synthetic root is hidden and only its descendants are rendered.
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

  /** A single visible row: node + indent depth + whether it is an expandable branch. */
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

  /** Replaces the entire tree (the synthetic root's children become top-level items); collapses all, clears selection, and resets to the first page. */
  public void setRoot(TreeNode<String, Object> root) {
    this.root = root;
    expanded.clear();
    checked.clear();
    selected = null;
    page = 0;
    rebuild();
  }

  /** Currently checked leaf contents (multi-selection); cleared on search, sort, or refresh. */
  public Set<Object> checked() {
    return Set.copyOf(checked);
  }

  /** Clears all checked items. */
  public void clearChecked() {
    checked.clear();
  }

  /** Sets the callback invoked when the checked set changes (used to refresh the enabled state of bulk-action buttons). */
  public void setOnCheckedChanged(Runnable callback) {
    this.onCheckedChanged = callback;
  }

  /** Right-click context callback: content (branches pass {@code null}) + screen coordinates. */
  public interface ContextHandler {
    void onContext(Object content, int mouseX, int mouseY);
  }

  /** Sets the right-click context callback. */
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

  // ---- Pagination parameters ----

  /** Number of rows that fit on one page (excluding the navigation bar). */
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

  // ---- Rendering ----

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

    UiScale.enableScissor(g, x, y, x + w, y + listH);
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

  /** 7px triangular arrow: down (▼) when expanded, right (▶) when collapsed. */
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

  /** Bottom navigation bar: [Previous]  Page X / Y  [Next]. */
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

  // ---- Interaction ----

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

    // Navigation bar
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

    // Row
    int rel = (int) Math.floor((mouseY - y) / ROW_HEIGHT);
    int idx = page * perPage + rel;
    if (rel < 0 || rel >= perPage || idx >= rows.size() || mouseX < x || mouseX >= x + w) {
      return true;
    }
    Row row = rows.get(idx);
    int rowX = x + PAD_LEFT + row.depth * INDENT;
    // Checkbox: toggle checked state (multi-selection) without changing the current selection.
    if (mouseX >= rowX && mouseX < rowX + CHECK_SIZE) {
      toggleChecked(row);
      return true;
    }
    int afterCheck = rowX + CHECK_BOX;
    boolean arrowHit = row.branch && mouseX >= afterCheck && mouseX < afterCheck + ARROW_BOX;
    // Arrow: expand/collapse; content-less branches (folders/categories) also expand/collapse on full-row click; others (files/buildings/packs) are selected.
    if (arrowHit || (row.branch && row.node.getContent() == null)) {
      toggle(row.node);
    } else {
      selected = row.node;
      onSelect.accept(row.node.getContent());
    }
    return true;
  }

  /** Scroll-wheel pagination: scroll up for the previous page, scroll down for the next page. */
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

  /** Right-click: selects the row and requests the context menu. */
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

  // ---- Checked state (multi-selection) ----

  /** Row checked state: for leaves, checks the leaf itself; for branches, checks whether all leaf descendants are checked. */
  private boolean isRowChecked(Row row) {
    if (!row.branch) {
      Object content = row.node.getContent();
      return content != null && checked.contains(content);
    }
    List<Object> leaves = new ArrayList<>();
    collectLeaves(row.node, leaves);
    return !leaves.isEmpty() && checked.containsAll(leaves);
  }

  /** Toggles row checked state: for leaves, toggles the leaf itself; for branches, toggles all leaf descendants. */
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

  /** 9px square checkbox; fills a green inner block when checked. */
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
