package com.github.simcityexpansion.buildpack.ui;

import java.util.function.BooleanSupplier;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

/**
 * 与 BuildPack 暗色主题一致的扁平按钮（取代原版按钮九宫格贴图）：深色底 + 1px 边框，
 * 悬停高亮、禁用置灰，文字居中带阴影并裁剪在按钮内。
 */
public final class ThemedButton extends AbstractButton {
  private final Runnable action;
  private BooleanSupplier selected;

  public ThemedButton(int x, int y, int width, int height, Component message, Runnable action) {
    super(x, y, width, height, message);
    this.action = action;
  }

  /** Optional selected-state predicate; when true the button shows a highlighted appearance. */
  public ThemedButton selected(BooleanSupplier selected) {
    this.selected = selected;
    return this;
  }

  @Override
  public void onPress() {
    action.run();
  }

  @Override
  protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
    boolean hovered = active && isMouseOver(mouseX, mouseY);
    boolean sel = active && selected != null && selected.getAsBoolean();
    int bg;
    int border;
    int textColor;
    if (!active) {
      bg = BuildPackTheme.BUTTON_BG_DISABLED;
      border = BuildPackTheme.BUTTON_BORDER;
      textColor = BuildPackTheme.BUTTON_TEXT_DISABLED;
    } else if (sel) {
      bg = BuildPackTheme.BUTTON_BG_HOVER;
      border = 0xFFFFCC55;
      textColor = BuildPackTheme.BUTTON_TEXT;
    } else if (hovered) {
      bg = BuildPackTheme.BUTTON_BG_HOVER;
      border = BuildPackTheme.BUTTON_BORDER_HOVER;
      textColor = BuildPackTheme.BUTTON_TEXT;
    } else {
      bg = BuildPackTheme.BUTTON_BG;
      border = BuildPackTheme.BUTTON_BORDER;
      textColor = BuildPackTheme.BUTTON_TEXT;
    }
    int x = getX();
    int y = getY();
    int w = getWidth();
    int h = getHeight();
    g.fill(x, y, x + w, y + h, bg);
    BuildPackTheme.border(g, x, y, w, h, border);

    Font font = Minecraft.getInstance().font;
    Component label = getMessage();
    int textX = x + Math.max(2, (w - font.width(label)) / 2);
    int textY = y + (h - font.lineHeight) / 2 + 1;
    g.enableScissor(x + 1, y + 1, x + w - 1, y + h - 1);
    g.drawString(font, label, textX, textY, textColor, true);
    g.disableScissor();
  }

  @Override
  protected void updateWidgetNarration(NarrationElementOutput output) {
    defaultButtonNarrationText(output);
  }
}
