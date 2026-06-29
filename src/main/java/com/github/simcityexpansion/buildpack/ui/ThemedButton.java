package com.github.simcityexpansion.buildpack.ui;

import java.util.function.BooleanSupplier;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

/**
 * A flat button consistent with the BuildPack dark theme (replacing the vanilla nine-slice button texture): dark background + 1px border,
 * hover highlight, disabled greyed-out, text centered with shadow and clipped inside the button bounds.
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
    UiScale.enableScissor(g, x + 1, y + 1, x + w - 1, y + h - 1);
    g.drawString(font, label, textX, textY, textColor, true);
    g.disableScissor();
  }

  @Override
  protected void updateWidgetNarration(NarrationElementOutput output) {
    defaultButtonNarrationText(output);
  }
}
