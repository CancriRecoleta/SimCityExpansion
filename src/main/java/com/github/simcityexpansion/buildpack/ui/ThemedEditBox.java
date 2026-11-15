package com.github.simcityexpansion.buildpack.ui;

import com.github.simcityexpansion.buildpack.ui.style.Anim;
import com.github.simcityexpansion.buildpack.ui.style.Shapes;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

/**
 * {@link EditBox} in the BuildPack (Krypton-style) theme: a rounded input whose border fades to the
 * accent color while focused, with shadowless text and a muted placeholder. Layout, cursor
 * hit-testing, and text inset are identical to a bordered vanilla edit box, so it is a drop-in
 * replacement.
 */
public class ThemedEditBox extends EditBox {
  private final Anim.Value focusAnim = new Anim.Value();
  private final Anim.Value hoverAnim = new Anim.Value();
  private boolean invalid;
  /** Placeholder as given by the caller; re-tinted whenever the theme's muted color changes. */
  private Component rawHint;
  private int hintColor;

  public ThemedEditBox(Font font, int x, int y, int width, int height, Component message) {
    super(font, x, y, width, height, message);
    setTextShadow(false);
    applyThemeColors();
  }

  /** Flags the content as invalid: the border turns red. */
  public void setInvalid(boolean invalid) {
    this.invalid = invalid;
  }

  @Override
  public void setHint(Component hint) {
    rawHint = hint;
    hintColor = BuildPackTheme.textMuted();
    super.setHint(hint.copy().withColor(hintColor & 0xFFFFFF));
  }

  /** Text colors follow the active theme (it can change while the box is on screen). */
  private void applyThemeColors() {
    setTextColor(BuildPackTheme.textPrimary());
    setTextColorUneditable(BuildPackTheme.textMuted());
    if (rawHint != null && hintColor != BuildPackTheme.textMuted()) {
      setHint(rawHint);
    }
  }

  @Override
  public void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
    if (!isVisible()) {
      return;
    }
    applyThemeColors();
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
        BuildPackTheme.withAlpha(BuildPackTheme.widgetBorder(), 0xC0),
        BuildPackTheme.withAlpha(BuildPackTheme.widgetBorderHover(), 0xA0), hover);
    int border = invalid
        ? BuildPackTheme.messageError()
        : BuildPackTheme.lerpColor(idleBorder, BuildPackTheme.accent(), focus);
    Shapes.roundRect(g, x, y, w, h, r, active ? BuildPackTheme.inputBg()
        : BuildPackTheme.withAlpha(BuildPackTheme.widgetBg(), 0x80));
    Shapes.roundRectStroke(g, x, y, w, h, r, Shapes.stroke(), border);

    // Vanilla draws its sprite frame whenever the box is bordered. Render the text unbordered with
    // the bordered geometry (4 px inset, vertically centered, inner width w - 8) so the content
    // sits exactly where cursor hit-testing (which still sees a bordered box) expects it.
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
