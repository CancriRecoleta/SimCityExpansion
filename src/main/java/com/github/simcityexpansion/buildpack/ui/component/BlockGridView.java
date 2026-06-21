package com.github.simcityexpansion.buildpack.ui.component;

import java.util.List;
import java.util.function.Consumer;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Inventory-style paginated grid of block icons. Blocks with an item use the item icon; blocks
 * without one (potted plants, wall variants, …) fall back to a small 3D block-model render so they
 * still show. Hovering a cell exposes its entry (for the host to draw a tooltip); clicking selects.
 */
public final class BlockGridView extends AbstractWidget {

  /** One selectable block: registry id, item icon (may be empty), model state, and display name. */
  public record Entry(String id, ItemStack stack, BlockState icon, Component name) {}

  private static final int CELL = 18;
  private static final int PAGE_BAR_H = 13;
  private static final int SLOT_BG = 0x20FFFFFF;
  private static final int HOVER_BG = 0x60FFFFFF;
  private static final int PAGE_SEP_COLOR = 0x60FFFFFF;
  private static final int PAGE_INFO_COLOR = 0xFFC0C0C0;
  private static final int PAGE_BTN_COLOR = 0xFFFFFFFF;
  private static final int PAGE_BTN_DISABLED = 0xFF808080;

  private List<Entry> entries = List.of();
  private int page;
  private Consumer<Entry> onSelect;
  private Entry hovered;

  public BlockGridView(int x, int y, int width, int height) {
    super(x, y, width, height, Component.empty());
  }

  /** Replace the listed entries and jump back to the first page. */
  public void setEntries(List<Entry> entries) {
    this.entries = entries;
    this.page = 0;
  }

  public void setOnSelect(Consumer<Entry> onSelect) {
    this.onSelect = onSelect;
  }

  /** Entry currently under the cursor, or null (for the host to draw a tooltip). */
  public Entry hovered() {
    return hovered;
  }

  private int cols() {
    return Math.max(1, getWidth() / CELL);
  }

  private int rowsPerPage(int h) {
    int full = Math.max(1, h / CELL);
    if (entries.size() <= full * cols()) {
      return full;
    }
    return Math.max(1, (h - PAGE_BAR_H) / CELL);
  }

  private int pageCount() {
    int per = cols() * rowsPerPage(getHeight());
    return Math.max(1, (entries.size() + per - 1) / per);
  }

  @Override
  protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
    hovered = null;
    int x = getX();
    int y = getY();
    int w = getWidth();
    int h = getHeight();
    if (w <= 0 || h <= 0) {
      return;
    }
    Minecraft mc = Minecraft.getInstance();
    int cols = cols();
    int rows = rowsPerPage(h);
    int per = cols * rows;
    int pages = Math.max(1, (entries.size() + per - 1) / per);
    page = Math.max(0, Math.min(pages - 1, page));
    int offsetX = (w - cols * CELL) / 2;
    int start = page * per;
    int end = Math.min(entries.size(), start + per);
    for (int i = start; i < end; i++) {
      int rel = i - start;
      int cx = x + offsetX + (rel % cols) * CELL;
      int cy = y + (rel / cols) * CELL;
      boolean hover = mouseX >= cx && mouseX < cx + CELL && mouseY >= cy && mouseY < cy + CELL;
      g.fill(cx + 1, cy + 1, cx + CELL - 1, cy + CELL - 1, hover ? HOVER_BG : SLOT_BG);
      Entry entry = entries.get(i);
      if (!entry.stack().isEmpty()) {
        g.renderItem(entry.stack(), cx + 1, cy + 1);
      } else {
        renderBlockIcon(g, entry.icon(), cx, cy, mc);
      }
      if (hover) {
        hovered = entry;
      }
    }
    if (pages > 1) {
      drawPageBar(g, mc.font, x, y + h - PAGE_BAR_H, w, pages);
    }
  }

  /** Render a block model as an iso icon (for blocks that have no item). */
  private void renderBlockIcon(GuiGraphics g, BlockState state, int cx, int cy, Minecraft mc) {
    PoseStack pose = g.pose();
    pose.pushPose();
    pose.translate(cx + CELL / 2.0, cy + CELL / 2.0, 150.0);
    pose.scale(11.0f, -11.0f, 11.0f);
    pose.mulPose(Axis.XP.rotationDegrees(30.0f));
    pose.mulPose(Axis.YP.rotationDegrees(225.0f));
    pose.translate(-0.5f, -0.5f, -0.5f);
    RenderSystem.enableDepthTest();
    Lighting.setupForEntityInInventory();
    MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
    try {
      mc.getBlockRenderer().renderSingleBlock(state, pose, buffers,
          LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
      buffers.endBatch();
    } catch (Throwable t) {
      buffers.endBatch();
    }
    Lighting.setupForFlatItems();
    pose.popPose();
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

  @Override
  public boolean mouseClicked(double mouseX, double mouseY, int button) {
    if (!active || !visible || button != 0 || !isMouseOver(mouseX, mouseY)) {
      return false;
    }
    int x = getX();
    int y = getY();
    int w = getWidth();
    int h = getHeight();
    int cols = cols();
    int per = cols * rowsPerPage(h);
    int pages = Math.max(1, (entries.size() + per - 1) / per);
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

    int offsetX = (w - cols * CELL) / 2;
    int relX = (int) (mouseX - x - offsetX);
    int relY = (int) (mouseY - y);
    if (relX < 0 || relX >= cols * CELL || relY < 0 || onSelect == null) {
      return true;
    }
    int idx = page * per + (relY / CELL) * cols + relX / CELL;
    if (idx >= 0 && idx < entries.size()) {
      onSelect.accept(entries.get(idx));
    }
    return true;
  }

  @Override
  public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
    if (!isMouseOver(mouseX, mouseY)) {
      return false;
    }
    int pages = pageCount();
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
