package com.github.simcityexpansion.buildpack.ui;

import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * 轻量右键上下文菜单：一列「文本 + 动作」。点击某项执行其动作并关闭，点击空白也关闭。
 * 由宿主屏幕在最上层渲染并优先转发点击；不是注册控件，便于控制层级。
 */
public final class ContextMenu {

  /** 一个菜单项。 */
  public record Item(Component label, Runnable action) {}

  private static final int ROW_H = 12;
  private static final int PAD = 3;

  private final List<Item> items;
  private final int x;
  private final int y;
  private final int width;

  public ContextMenu(int x, int y, List<Item> items) {
    this.items = items;
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

  /** 在最上层绘制菜单。 */
  public void render(GuiGraphics g, int mouseX, int mouseY) {
    BuildPackTheme.fillPanel(g, x, y, width, height(), 0xF0101010);
    Font font = Minecraft.getInstance().font;
    for (int i = 0; i < items.size(); i++) {
      int rowY = y + PAD + i * ROW_H;
      boolean hovered = mouseX >= x && mouseX < x + width
          && mouseY >= rowY && mouseY < rowY + ROW_H;
      if (hovered) {
        g.fill(x + 1, rowY, x + width - 1, rowY + ROW_H, 0x40FFFFFF);
      }
      g.drawString(font, items.get(i).label(), x + PAD + 2,
          rowY + (ROW_H - font.lineHeight) / 2 + 1, BuildPackTheme.BUTTON_TEXT, true);
    }
  }

  /** 处理一次点击：命中某项则执行其动作。无论命中与否都应由宿主关闭菜单。 */
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
