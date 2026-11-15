package com.github.simcityexpansion.buildpack.ui.component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import com.github.simcityexpansion.buildpack.convert.StructureAnalysis.MaterialEntry;
import com.github.simcityexpansion.buildpack.ui.BuildPackTheme;
import com.github.simcityexpansion.buildpack.ui.UiFormats;
import com.github.simcityexpansion.buildpack.ui.UiScale;
import com.github.simcityexpansion.buildpack.ui.style.Shapes;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;

/**
 * Material table: one row per block type with its item icon, name (and id when there is room), a
 * share bar relative to the most-used block, the percentage of all blocks, a stack breakdown
 * ({@code 12×64 + 5}), and the count. Columns collapse on narrow widths. The header sorts by name
 * or count (missing blocks always stay on top, highlighted in red), the list scrolls smoothly with
 * the wheel or a draggable scrollbar, and hovering a row shows a detailed tooltip. Rows are
 * optionally clickable ({@link #setOnSelect}).
 */
public final class MaterialListView extends AbstractWidget {

  private static final int ROW_HEIGHT = 18;
  private static final int HEADER_H = 14;
  private static final int ICON = 16;
  private static final int PAD = 4;
  private static final int SCROLLBAR_W = 7;
  private static final int BAR_W = 46;
  private static final int SHULKER_SLOTS = 27;

  /** Sort orders; missing blocks always stay on top. */
  public enum Sort { COUNT_DESC, COUNT_ASC, NAME_ASC, NAME_DESC }

  /** A material plus everything the row needs, resolved once per {@link #setMaterials} call. */
  private record Row(MaterialEntry entry, String name, String searchKey, ItemStack icon,
      int maxStack, String stacks) {}

  /** Column layout for the current width. */
  private record Columns(int nameX, int nameRight, int shareX, int stacksRight, int countRight,
      boolean share, boolean stacks) {}

  private List<Row> all = List.of();
  private List<Row> rows = List.of();
  private long totalCount;
  private long maxCount;
  private int missingCount;
  private int stacksWidth;
  private String filter = "";
  private Sort sort = Sort.COUNT_DESC;
  private Consumer<MaterialEntry> onSelect;

  private float scroll;
  private float targetScroll;
  private long lastFrameNanos = System.nanoTime();
  private boolean draggingScrollbar;

  public MaterialListView(int x, int y, int width, int height) {
    super(x, y, width, height, Component.empty());
  }

  // ---- Data ----

  /** Sets the material data (resolves icons and stack sizes) and scrolls back to the top. */
  public void setMaterials(List<MaterialEntry> materials) {
    List<Row> resolved = new ArrayList<>(materials.size());
    long total = 0;
    long max = 0;
    int missing = 0;
    for (MaterialEntry m : materials) {
      ItemStack icon = iconFor(m);
      int maxStack = !m.missing() && !icon.isEmpty() && icon.getItem() != Items.BARRIER
          ? icon.getMaxStackSize() : 0;
      String name = m.missing() ? m.blockId() : m.displayName().getString();
      resolved.add(new Row(m, name, (name + " " + m.blockId()).toLowerCase(Locale.ROOT), icon,
          maxStack, stacksText(m.count(), maxStack)));
      total += m.count();
      max = Math.max(max, m.count());
      if (m.missing()) {
        missing++;
      }
    }
    Font font = Minecraft.getInstance().font;
    int widest = 0;
    for (Row row : resolved) {
      widest = Math.max(widest, font.width(row.stacks()));
    }
    this.all = resolved;
    this.totalCount = total;
    this.maxCount = Math.max(1, max);
    this.missingCount = missing;
    this.stacksWidth = widest;
    rebuildRows();
    scroll = 0;
    targetScroll = 0;
  }

  /** Shows only rows whose name or block id contains {@code text} (case-insensitive). */
  public void setFilter(String text) {
    String normalized = text == null ? "" : text.strip().toLowerCase(Locale.ROOT);
    if (!normalized.equals(filter)) {
      filter = normalized;
      rebuildRows();
      scroll = 0;
      targetScroll = 0;
    }
  }

  /** Enables row selection: clicking a row invokes the callback with that entry. */
  public void setOnSelect(Consumer<MaterialEntry> onSelect) {
    this.onSelect = onSelect;
  }

  /** Total block count across all materials (ignores the filter). */
  public long totalCount() {
    return totalCount;
  }

  /** Number of block types (ignores the filter). */
  public int typeCount() {
    return all.size();
  }

  /** Number of block types not registered in this game. */
  public int missingCount() {
    return missingCount;
  }

  /** Plain-text lines for the visible (filtered, sorted) rows, one block type per line. */
  public List<String> exportLines() {
    List<String> lines = new ArrayList<>(rows.size());
    for (Row row : rows) {
      StringBuilder line = new StringBuilder(row.name())
          .append(" × ").append(UiFormats.integer(row.entry().count()));
      if (!row.stacks().isEmpty()) {
        line.append(" (").append(row.stacks()).append(')');
      }
      if (!row.name().equals(row.entry().blockId())) {
        line.append("  [").append(row.entry().blockId()).append(']');
      }
      lines.add(line.toString());
    }
    return lines;
  }

  private void rebuildRows() {
    List<Row> filtered = new ArrayList<>();
    for (Row row : all) {
      if (filter.isEmpty() || row.searchKey().contains(filter)) {
        filtered.add(row);
      }
    }
    Comparator<Row> order = switch (sort) {
      case COUNT_DESC -> Comparator.comparingLong((Row r) -> r.entry().count()).reversed();
      case COUNT_ASC -> Comparator.comparingLong((Row r) -> r.entry().count());
      case NAME_ASC -> Comparator.comparing(Row::name, String.CASE_INSENSITIVE_ORDER);
      case NAME_DESC -> Comparator.comparing(Row::name, String.CASE_INSENSITIVE_ORDER).reversed();
    };
    filtered.sort(Comparator.comparing((Row r) -> !r.entry().missing()).thenComparing(order));
    rows = filtered;
  }

  private static ItemStack iconFor(MaterialEntry m) {
    if (m.missing()) {
      return new ItemStack(Items.BARRIER);
    }
    ResourceLocation id = ResourceLocation.tryParse(m.blockId());
    if (id == null) {
      return ItemStack.EMPTY;
    }
    Block block = BuiltInRegistries.BLOCK.get(id);
    Item item = block.asItem();
    if (item == Items.AIR) {
      // Fluids and other item-less blocks: show the fluid's bucket when there is one.
      Item bucket = block.defaultBlockState().getFluidState().getType().getBucket();
      return bucket == Items.AIR ? ItemStack.EMPTY : new ItemStack(bucket);
    }
    return new ItemStack(item);
  }

  /** {@code 12×64 + 5}; empty when the block does not stack or there is less than one stack. */
  private static String stacksText(long count, int maxStack) {
    if (maxStack <= 1 || count < maxStack) {
      return "";
    }
    long stacks = count / maxStack;
    long rest = count % maxStack;
    return UiFormats.integer(stacks) + "×" + maxStack + (rest > 0 ? " + " + rest : "");
  }

  // ---- Layout ----

  private int listTop() {
    return getY() + HEADER_H;
  }

  private int viewHeight() {
    return Math.max(0, getHeight() - HEADER_H);
  }

  private int maxScroll() {
    return Math.max(0, rows.size() * ROW_HEIGHT - viewHeight());
  }

  private boolean scrollbarVisible() {
    return maxScroll() > 0;
  }

  private Columns columns(Font font) {
    int x = getX();
    int right = x + getWidth() - PAD - (scrollbarVisible() ? SCROLLBAR_W : 0);
    int width = right - x;
    String countHeader = Component.translatable("buildpack.materials.col.count").getString();
    int countW = Math.max(font.width(UiFormats.integer(maxCount)), font.width(countHeader) + 8);
    boolean stacks = width >= 300 && stacksWidth > 0;
    boolean share = width >= 220;
    int stacksRight = right - countW - 10;
    int stacksW = Math.max(stacksWidth,
        font.width(Component.translatable("buildpack.materials.col.stacks").getString()) + 8);
    int shareRight = stacks ? stacksRight - stacksW - 10 : stacksRight;
    int shareX = shareRight - BAR_W - 4 - font.width("100%");
    int nameX = x + PAD + ICON + 5;
    int nameRight = (share ? shareX : stacks ? stacksRight - stacksW : right - countW) - 8;
    return new Columns(nameX, nameRight, shareX, stacksRight, right, share, stacks);
  }

  // ---- Rendering ----

  @Override
  protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
    Font font = Minecraft.getInstance().font;
    int x = getX();
    int y = getY();
    int w = getWidth();
    int h = getHeight();
    if (w <= 0 || h <= HEADER_H) {
      return;
    }
    advanceScroll();
    Columns cols = columns(font);
    drawHeader(g, font, cols, mouseX, mouseY);

    int top = listTop();
    int view = viewHeight();
    if (rows.isEmpty()) {
      String key = filter.isEmpty() ? "buildpack.materials.empty" : "buildpack.materials.no_match";
      String text = Component.translatable(key).getString();
      g.drawString(font, text, x + (w - font.width(text)) / 2, top + Math.min(view / 2, 24) - 4,
          BuildPackTheme.textMuted(), false);
      return;
    }

    int first = Math.max(0, (int) (scroll / ROW_HEIGHT));
    int last = Math.min(rows.size(), (int) ((scroll + view) / ROW_HEIGHT) + 1);
    boolean overList = isMouseOver(mouseX, mouseY) && mouseY >= top
        && (!scrollbarVisible() || mouseX < x + w - SCROLLBAR_W);
    Row hoveredRow = null;
    UiScale.enableScissor(g, x, top, x + w, top + view);
    for (int i = first; i < last; i++) {
      Row row = rows.get(i);
      int rowY = top + i * ROW_HEIGHT - Math.round(scroll);
      boolean hovered = overList && !draggingScrollbar
          && mouseY >= rowY && mouseY < rowY + ROW_HEIGHT;
      if (hovered) {
        hoveredRow = row;
      }
      drawRow(g, font, cols, row, i, rowY, hovered);
    }
    g.disableScissor();

    if (scrollbarVisible()) {
      int trackH = view - 2;
      int thumbH = Math.max(12, trackH * view / (rows.size() * ROW_HEIGHT));
      int thumbY = top + 1 + Math.round((trackH - thumbH) * (scroll / maxScroll()));
      BuildPackTheme.scrollbar(g, x + w - 5, top + 1, trackH, thumbY, thumbH);
    }
    if (hoveredRow != null) {
      showTooltip(hoveredRow);
    }
  }

  private void drawHeader(GuiGraphics g, Font font, Columns cols, int mouseX, int mouseY) {
    int y = getY();
    int textY = y + (HEADER_H - 8) / 2;
    boolean overHeader = isMouseOver(mouseX, mouseY) && mouseY < y + HEADER_H;
    boolean byName = sort == Sort.NAME_ASC || sort == Sort.NAME_DESC;
    String arrow = sort == Sort.COUNT_ASC || sort == Sort.NAME_ASC ? " ▲" : " ▼";

    String block = Component.translatable("buildpack.materials.col.block").getString()
        + (byName ? arrow : "");
    boolean hoverName = overHeader && mouseX < cols.nameRight();
    g.drawString(font, block, cols.nameX() - ICON - 5 + PAD / 2, textY,
        headerColor(byName, hoverName), false);

    String count = Component.translatable("buildpack.materials.col.count").getString()
        + (byName ? "" : arrow);
    boolean hoverCount = overHeader && mouseX >= cols.nameRight();
    int countColor = headerColor(!byName, hoverCount);
    g.drawString(font, count, cols.countRight() - font.width(count), textY, countColor, false);
    if (cols.stacks()) {
      String stacks = Component.translatable("buildpack.materials.col.stacks").getString();
      g.drawString(font, stacks, cols.stacksRight() - font.width(stacks), textY,
          headerColor(false, hoverCount), false);
    }
    if (cols.share()) {
      String share = Component.translatable("buildpack.materials.col.share").getString();
      g.drawString(font, share, cols.shareX(), textY, headerColor(false, hoverCount), false);
    }
    BuildPackTheme.divider(g, getX() + 2, getX() + getWidth() - 2, y + HEADER_H - 1.5F);
  }

  private static int headerColor(boolean active, boolean hovered) {
    if (hovered) {
      return BuildPackTheme.accentLight();
    }
    return active ? BuildPackTheme.textPrimary() : BuildPackTheme.textMuted();
  }

  private void drawRow(GuiGraphics g, Font font, Columns cols, Row row, int index, int rowY,
      boolean hovered) {
    int x = getX();
    int rowW = cols.countRight() + PAD / 2 - x - 1;
    MaterialEntry m = row.entry();
    if (hovered) {
      BuildPackTheme.rowHighlight(g, x + 1, rowY, rowW, ROW_HEIGHT - 1,
          onSelect != null ? BuildPackTheme.rowSelected() : BuildPackTheme.rowHover());
    } else if (m.missing()) {
      BuildPackTheme.rowHighlight(g, x + 1, rowY, rowW, ROW_HEIGHT - 1,
          BuildPackTheme.withAlpha(BuildPackTheme.messageError(), 0x1C));
    } else if ((index & 1) == 1) {
      BuildPackTheme.rowHighlight(g, x + 1, rowY, rowW, ROW_HEIGHT - 1, BuildPackTheme.rowAlt());
    }

    int iconX = x + PAD;
    int iconY = rowY + (ROW_HEIGHT - ICON) / 2;
    if (row.icon().isEmpty()) {
      Shapes.roundRect(g, iconX + 2, iconY + 2, ICON - 4, ICON - 4, 3F,
          BuildPackTheme.withAlpha(BuildPackTheme.widgetBorderHover(), 0x60));
    } else {
      g.renderItem(row.icon(), iconX, iconY);
    }

    int textY = rowY + (ROW_HEIGHT - 8) / 2;
    int nameColor = m.missing() ? BuildPackTheme.messageError()
        : hovered ? BuildPackTheme.textPrimary() : BuildPackTheme.textSecondary();
    int nameRoom = cols.nameRight() - cols.nameX();
    String name = clip(font, row.name(), nameRoom);
    g.drawString(font, name, cols.nameX(), textY, nameColor, false);
    if (!m.missing() && name.equals(row.name())) {
      int idX = cols.nameX() + font.width(name) + 6;
      String id = m.blockId();
      if (idX + font.width(id) <= cols.nameRight()) {
        g.drawString(font, id, idX, textY, BuildPackTheme.withAlpha(BuildPackTheme.textMuted(),
            0xB0), false);
      }
    }

    if (cols.share()) {
      float barY = rowY + ROW_HEIGHT / 2F - 2F;
      Shapes.pill(g, cols.shareX(), barY, BAR_W, 4F,
          BuildPackTheme.withAlpha(BuildPackTheme.widgetBorder(), 0xA0));
      float fill = Math.max(4F, BAR_W * (float) m.count() / maxCount);
      Shapes.pill(g, cols.shareX(), barY, fill, 4F,
          m.missing() ? BuildPackTheme.messageError() : BuildPackTheme.accent());
      String percent = percent(m.count());
      g.drawString(font, percent, cols.shareX() + BAR_W + 4, textY, BuildPackTheme.textMuted(),
          false);
    }
    if (cols.stacks() && !row.stacks().isEmpty()) {
      g.drawString(font, row.stacks(), cols.stacksRight() - font.width(row.stacks()), textY,
          BuildPackTheme.textMuted(), false);
    }
    String count = UiFormats.integer(m.count());
    g.drawString(font, count, cols.countRight() - font.width(count), textY,
        BuildPackTheme.textPrimary(), false);
  }

  private String percent(long count) {
    double value = totalCount <= 0 ? 0 : count * 100.0 / totalCount;
    return value >= 10 || value == 0
        ? String.format(UiFormats.gameLocale(), "%.0f%%", value)
        : String.format(UiFormats.gameLocale(), "%.1f%%", value);
  }

  private static String clip(Font font, String text, int maxWidth) {
    if (font.width(text) <= maxWidth) {
      return text;
    }
    return font.plainSubstrByWidth(text, Math.max(0, maxWidth - font.width("…"))) + "…";
  }

  private void showTooltip(Row row) {
    Screen screen = Minecraft.getInstance().screen;
    if (screen == null) {
      return;
    }
    MaterialEntry m = row.entry();
    List<Component> lines = new ArrayList<>();
    lines.add(Component.literal(row.name()));
    if (!row.name().equals(m.blockId())) {
      lines.add(Component.literal(m.blockId()).withStyle(ChatFormatting.DARK_GRAY));
    }
    lines.add(Component.translatable("buildpack.materials.tip.count",
        UiFormats.integer(m.count()), percent(m.count())).withStyle(ChatFormatting.GRAY));
    if (!row.stacks().isEmpty()) {
      lines.add(Component.translatable("buildpack.materials.tip.stacks", row.stacks())
          .withStyle(ChatFormatting.GRAY));
    }
    if (row.maxStack() > 1 && m.count() >= (long) row.maxStack() * SHULKER_SLOTS) {
      lines.add(Component.translatable("buildpack.materials.tip.shulkers",
          String.format(UiFormats.gameLocale(), "%.1f",
              m.count() / (double) (row.maxStack() * SHULKER_SLOTS)))
          .withStyle(ChatFormatting.GRAY));
    }
    if (m.missing()) {
      lines.add(Component.translatable("buildpack.materials.tip.missing")
          .withStyle(ChatFormatting.RED));
    }
    List<FormattedCharSequence> sequences = new ArrayList<>(lines.size());
    for (Component line : lines) {
      sequences.add(line.getVisualOrderText());
    }
    screen.setTooltipForNextRenderPass(sequences);
  }

  // ---- Scrolling ----

  private void advanceScroll() {
    long now = System.nanoTime();
    float dt = Math.min(0.1F, (now - lastFrameNanos) / 1_000_000_000F);
    lastFrameNanos = now;
    targetScroll = Math.max(0, Math.min(maxScroll(), targetScroll));
    if (draggingScrollbar) {
      scroll = targetScroll;
    } else {
      scroll += (targetScroll - scroll) * (1F - (float) Math.exp(-18F * dt));
      if (Math.abs(targetScroll - scroll) < 0.25F) {
        scroll = targetScroll;
      }
    }
  }

  private void scrollToMouse(double mouseY) {
    int view = viewHeight();
    int content = rows.size() * ROW_HEIGHT;
    if (content <= view) {
      return;
    }
    int thumbH = Math.max(12, (view - 2) * view / content);
    double fraction = (mouseY - listTop() - 1 - thumbH / 2.0) / Math.max(1, view - 2 - thumbH);
    targetScroll = (float) (Math.max(0, Math.min(1, fraction)) * maxScroll());
    scroll = targetScroll;
  }

  // ---- Interaction ----

  @Override
  public boolean mouseClicked(double mouseX, double mouseY, int button) {
    if (!active || !visible || button != 0 || !isMouseOver(mouseX, mouseY)) {
      return false;
    }
    Font font = Minecraft.getInstance().font;
    if (mouseY < getY() + HEADER_H) {
      Columns cols = columns(font);
      if (mouseX < cols.nameRight()) {
        sort = sort == Sort.NAME_ASC ? Sort.NAME_DESC : Sort.NAME_ASC;
      } else {
        sort = sort == Sort.COUNT_DESC ? Sort.COUNT_ASC : Sort.COUNT_DESC;
      }
      rebuildRows();
      return true;
    }
    if (scrollbarVisible() && mouseX >= getX() + getWidth() - SCROLLBAR_W) {
      draggingScrollbar = true;
      scrollToMouse(mouseY);
      return true;
    }
    if (onSelect != null) {
      int index = (int) Math.floor((mouseY - listTop() + scroll) / ROW_HEIGHT);
      if (index >= 0 && index < rows.size()) {
        onSelect.accept(rows.get(index).entry());
      }
    }
    return true;
  }

  @Override
  public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX,
      double dragY) {
    if (draggingScrollbar && button == 0) {
      scrollToMouse(mouseY);
      return true;
    }
    return false;
  }

  @Override
  public boolean mouseReleased(double mouseX, double mouseY, int button) {
    if (draggingScrollbar && button == 0) {
      draggingScrollbar = false;
      return true;
    }
    return false;
  }

  @Override
  public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
    if (!isMouseOver(mouseX, mouseY) || maxScroll() <= 0) {
      return false;
    }
    targetScroll = Math.max(0, Math.min(maxScroll(),
        targetScroll - (float) scrollY * ROW_HEIGHT * 3));
    return true;
  }

  @Override
  protected void updateWidgetNarration(NarrationElementOutput output) {}
}
