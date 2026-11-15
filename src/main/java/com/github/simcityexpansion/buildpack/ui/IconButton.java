package com.github.simcityexpansion.buildpack.ui;

import com.github.simcityexpansion.buildpack.ui.style.Anim;
import com.github.simcityexpansion.buildpack.ui.style.Shapes;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

/**
 * Round glyph button (Krypton-style header icon such as ⚙ or ✕): no chrome at rest, an accent
 * circle that fades in on hover (red for the close glyph), and a glyph that brightens with it.
 */
public final class IconButton extends AbstractButton {
  /** Glyph that tints red on hover. */
  public static final String CLOSE = "✕";

  private final String glyph;
  private final Runnable action;
  private final Anim.Value hoverAnim = new Anim.Value();

  /**
   * @param narration accessible name (the glyph itself is not narrated)
   */
  public IconButton(int x, int y, int size, String glyph, Component narration, Runnable action) {
    super(x, y, size, size, narration);
    this.glyph = glyph;
    this.action = action;
  }

  @Override
  public void onPress() {
    action.run();
  }

  @Override
  protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
    float hover = hoverAnim.approach(active && isHovered() ? 1F : 0F, 16F);
    int x = getX();
    int y = getY();
    int size = Math.min(getWidth(), getHeight());
    int tint = CLOSE.equals(glyph) ? BuildPackTheme.messageError() : BuildPackTheme.accent();
    if (hover > 0.01F) {
      Shapes.circle(g, x + getWidth() / 2F, y + getHeight() / 2F, size / 2F,
          BuildPackTheme.withAlpha(tint, (int) (0x50 * hover)));
    }
    Font font = Minecraft.getInstance().font;
    int color = !active ? BuildPackTheme.textMuted()
        : BuildPackTheme.lerpColor(BuildPackTheme.textSecondary(), BuildPackTheme.textPrimary(),
            hover);
    int gw = font.width(glyph);
    g.drawString(font, glyph, x + (getWidth() - gw + 1) / 2, y + (getHeight() - 8) / 2, color,
        false);
  }

  @Override
  protected void updateWidgetNarration(NarrationElementOutput output) {
    defaultButtonNarrationText(output);
  }
}
