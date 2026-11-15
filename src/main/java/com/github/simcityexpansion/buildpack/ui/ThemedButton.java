package com.github.simcityexpansion.buildpack.ui;

import java.util.function.BooleanSupplier;

import com.github.simcityexpansion.buildpack.ui.style.Anim;
import com.github.simcityexpansion.buildpack.ui.style.Shapes;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

/**
 * A flat button in the BuildPack (Krypton-style) theme: an anti-aliased pill tinted with the accent
 * color that brightens with an eased hover fade. {@link #primary()} fills it with the accent,
 * {@link #selected} shows a solid accent pill for toggles, and {@link #tab()} draws a flat tab label
 * whose underline the host screen slides between tabs. Text is shadowless and clipped to the button.
 */
public final class ThemedButton extends AbstractButton {

  /** Visual variants. */
  private enum Style { NORMAL, PRIMARY, TAB }

  private final Runnable action;
  private final Anim.Value hoverAnim = new Anim.Value();
  private final Anim.Value selectAnim = new Anim.Value();
  private BooleanSupplier selected;
  private Style style = Style.NORMAL;

  public ThemedButton(int x, int y, int width, int height, Component message, Runnable action) {
    super(x, y, width, height, message);
    this.action = action;
  }

  /** Optional selected-state predicate; when true the button shows a solid accent pill. */
  public ThemedButton selected(BooleanSupplier selected) {
    this.selected = selected;
    return this;
  }

  /** Marks this as the primary action of its group (solid accent fill). */
  public ThemedButton primary() {
    this.style = Style.PRIMARY;
    return this;
  }

  /** Renders as a flat tab label; the host draws the active underline. */
  public ThemedButton tab() {
    this.style = Style.TAB;
    return this;
  }

  /** Whether the selected predicate currently holds. */
  public boolean isSelected() {
    return selected != null && selected.getAsBoolean();
  }

  @Override
  public void onPress() {
    action.run();
  }

  @Override
  protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
    boolean hovered = active && isMouseOver(mouseX, mouseY);
    float hover = hoverAnim.approach(hovered ? 1F : 0F, 16F);
    float sel = selectAnim.approach(active && isSelected() ? 1F : 0F, 14F);
    int x = getX();
    int y = getY();
    int w = getWidth();
    int h = getHeight();

    int textColor;
    if (style == Style.TAB) {
      if (!active) {
        textColor = BuildPackTheme.TEXT_MUTED;
      } else {
        if (hover > 0.01F && sel < 0.99F) {
          Shapes.roundRect(g, x + 1, y + 1, w - 2, h - 3, BuildPackTheme.CONTROL_RADIUS,
              BuildPackTheme.withAlpha(BuildPackTheme.ACCENT, (int) (0x26 * hover * (1F - sel))));
        }
        int idle = BuildPackTheme.lerpColor(BuildPackTheme.TEXT_SECONDARY,
            BuildPackTheme.ACCENT_LIGHT, hover);
        textColor = BuildPackTheme.lerpColor(idle, BuildPackTheme.TEXT_PRIMARY, sel);
      }
    } else {
      int bg;
      if (!active) {
        bg = BuildPackTheme.BUTTON_BG_DISABLED;
        textColor = BuildPackTheme.BUTTON_TEXT_DISABLED;
      } else if (style == Style.PRIMARY) {
        bg = BuildPackTheme.lerpColor(BuildPackTheme.ACCENT, BuildPackTheme.ACCENT_HOVER, hover);
        textColor = 0xFFFFFFFF;
      } else {
        int tinted = BuildPackTheme.withAlpha(BuildPackTheme.ACCENT, 0x30 + (int) (0x28 * hover));
        int solid = BuildPackTheme.lerpColor(BuildPackTheme.ACCENT, BuildPackTheme.ACCENT_HOVER,
            hover);
        bg = BuildPackTheme.lerpColor(tinted, solid, sel);
        int idleText = BuildPackTheme.lerpColor(BuildPackTheme.ACCENT_LIGHT,
            BuildPackTheme.TEXT_PRIMARY, hover);
        textColor = BuildPackTheme.lerpColor(idleText, 0xFFFFFFFF, sel);
      }
      Shapes.pill(g, x, y, w, h, bg);
    }

    Font font = Minecraft.getInstance().font;
    Component label = getMessage();
    int textX = x + Math.max(3, (w - font.width(label)) / 2);
    int textY = y + (h - font.lineHeight) / 2 + 1;
    UiScale.enableScissor(g, x + 1, y + 1, x + w - 1, y + h - 1);
    g.drawString(font, label, textX, textY, textColor, false);
    g.disableScissor();
  }

  @Override
  protected void updateWidgetNarration(NarrationElementOutput output) {
    defaultButtonNarrationText(output);
  }
}
