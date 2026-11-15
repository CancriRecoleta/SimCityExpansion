package com.github.simcityexpansion.buildpack.ui;

import java.util.List;

import com.github.simcityexpansion.buildpack.ui.style.Anim;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * Lightweight right-click context menu: a single column of "text + action" items. Clicking an
 * item executes its action and closes the menu; clicking outside also closes it. Rendered on top
 * by the host screen, which also forwards clicks with priority; not a registered widget, so
 * z-order is easy to control.
 */
public final class ContextMenu {

  /** A single menu item. */
  public record Item(Component label, Runnable action) {}

  private static final int ROW_H = 13;
  private static final int PAD = 4;

  private final List<Item> items;
  private final int x;
  private final int y;
  private final int width;
  private final Anim.Value[] hoverAnims;

  public ContextMenu(int x, int y, List<Item> items) {
    this.items = items;
    this.hoverAnims = new Anim.Value[items.size()];
    for (int i = 0; i < hoverAnims.length; i++) {
      hoverAnims[i] = new Anim.Value();
    }
    Font font = Minecraft.getInstance().font;
    int w = 50;
    for (Item item : items) {
      w = Math.max(w, font.width(item.label()) + PAD * 2 + 6);
    }
    int screenW = Minecraft.getInstance().getWindow().getGuiScaledWidth();
    int screenH = Minecraft.getInstance().getWindow().getGuiScaledHeight();
    this.width = Math.min(w, screenW - 2);
    this.x = Math.max(0, Math.min(x, screenW - width - 1));
    this.y = Math.max(0, Math.min(y, screenH - height() - 1));
  }

  private int height() {
    return items.size() * ROW_H + PAD * 2;
  }

  /** Renders the menu on top of all other content (Krypton popup look). */
  public void render(GuiGraphics g, int mouseX, int mouseY) {
    g.pose().pushPose();
    g.pose().translate(0, 0, 400);
    BuildPackTheme.popup(g, x, y, width, height());
    Font font = Minecraft.getInstance().font;
    for (int i = 0; i < items.size(); i++) {
      int rowY = y + PAD + i * ROW_H;
      boolean hovered = mouseX >= x && mouseX < x + width
          && mouseY >= rowY && mouseY < rowY + ROW_H;
      float hover = hoverAnims[i].approach(hovered ? 1F : 0F, 16F);
      if (hover > 0.01F) {
        BuildPackTheme.rowHighlight(g, x + 2, rowY, width - 4, ROW_H,
            BuildPackTheme.withAlpha(BuildPackTheme.ACCENT, (int) (0x30 * hover)));
      }
      int color = BuildPackTheme.lerpColor(BuildPackTheme.TEXT_SECONDARY,
          BuildPackTheme.TEXT_PRIMARY, hover);
      g.drawString(font, items.get(i).label(), x + PAD + 3,
          rowY + (ROW_H - font.lineHeight) / 2 + 1, color, false);
    }
    g.pose().popPose();
  }

  /** Handles a click: executes the action of the hit item, if any. The host should close the menu regardless of whether an item was hit. */
  public void click(double mouseX, double mouseY) {
    if (mouseX < x || mouseX >= x + width || mouseY < y + PAD || mouseY >= y + height() - PAD) {
      return;
    }
    int index = (int) ((mouseY - y - PAD) / ROW_H);
    if (index >= 0 && index < items.size()) {
      items.get(index).action().run();
    }
  }
}
