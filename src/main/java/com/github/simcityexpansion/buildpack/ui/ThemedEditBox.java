package com.github.simcityexpansion.buildpack.ui;

import com.github.simcityexpansion.buildpack.ui.style.Anim;
import com.github.simcityexpansion.buildpack.ui.style.Shapes;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

/**
 * {@link EditBox} in the BuildPack (Krypton-style) theme: a rounded dark input whose border fades to
 * the accent color while focused, with shadowless text and a muted placeholder. Layout, cursor
 * hit-testing, and text inset are identical to a bordered vanilla edit box, so it is a drop-in
 * replacement.
 */
public class ThemedEditBox extends EditBox {
  private final Anim.Value focusAnim = new Anim.Value();
  private final Anim.Value hoverAnim = new Anim.Value();
  private boolean invalid;

  public ThemedEditBox(Font font, int x, int y, int width, int height, Component message) {
    super(font, x, y, width, height, message);
    setTextShadow(false);
    setTextColor(BuildPackTheme.TEXT_PRIMARY);
    setTextColorUneditable(BuildPackTheme.TEXT_MUTED);
  }

  /** Flags the content as invalid: the border turns red. */
  public void setInvalid(boolean invalid) {
    this.invalid = invalid;
  }

  @Override
  public void setHint(Component hint) {
    super.setHint(hint.copy().withColor(BuildPackTheme.TEXT_MUTED & 0xFFFFFF));
  }

  @Override
  public void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
    if (!isVisible()) {
      return;
    }
    if (!isBordered()) {
      super.renderWidget(g, mouseX, mouseY, partialTick);
      return;
    }
    int x = getX();
    int y = getY();
    int w = getWidth();
    int h = getHeight();
    float focus = focusAnim.approach(isFocused() ? 1F : 0F, 14F);
    float hover = hoverAnim.approach(isHovered() ? 1F : 0F, 16F);
    float r = Math.min(BuildPackTheme.CONTROL_RADIUS, h / 2F);
    int idleBorder = BuildPackTheme.lerpColor(
        BuildPackTheme.withAlpha(BuildPackTheme.WIDGET_BORDER, 0xC0),
        BuildPackTheme.withAlpha(BuildPackTheme.WIDGET_BORDER_HOVER, 0xA0), hover);
    int border = invalid
        ? BuildPackTheme.MESSAGE_ERROR
        : BuildPackTheme.lerpColor(idleBorder, BuildPackTheme.ACCENT, focus);
    Shapes.roundRect(g, x, y, w, h, r, active ? BuildPackTheme.INPUT_BG
        : BuildPackTheme.withAlpha(BuildPackTheme.WIDGET_BG, 0x80));
    Shapes.roundRectStroke(g, x, y, w, h, r, Shapes.stroke(), border);

    // Vanilla draws its sprite frame whenever the box is bordered. Render the text unbordered with
    // the bordered geometry (4 px inset, vertically centered, inner width w - 8) so the content sits
    // exactly where cursor hit-testing (which still sees a bordered box) expects it.
    setBordered(false);
    setX(x + 4);
    setY(y + (h - 8) / 2);
    setWidth(w - 8);
    try {
      super.renderWidget(g, mouseX, mouseY, partialTick);
    } finally {
      setWidth(w);
      setY(y);
      setX(x);
      setBordered(true);
    }
  }
}
