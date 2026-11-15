package com.github.simcityexpansion.buildpack.ui;

import com.github.simcityexpansion.buildpack.ui.style.Anim;
import com.github.simcityexpansion.buildpack.ui.style.Shapes;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

/**
 * Boolean option in the BuildPack (Krypton-style) theme: an 18x10 switch with a sliding knob that
 * turns green when on, followed by its label. The builder mirrors vanilla's {@code Checkbox} (whose
 * constructor is package-private), so call sites only change the class name.
 */
public final class ThemedCheckbox extends AbstractButton {
  private static final int TRACK_W = 18;
  private static final int TRACK_H = 10;
  private static final int LABEL_GAP = 5;
  private static final int HEIGHT = 16;

  /** Callback fired after the value flips. */
  @FunctionalInterface
  public interface OnValueChange {
    OnValueChange NOP = (checkbox, value) -> {};

    void onValueChange(ThemedCheckbox checkbox, boolean value);
  }

  private final Font font;
  private final OnValueChange onValueChange;
  private final Anim.Value knobAnim = new Anim.Value();
  private final Anim.Value hoverAnim = new Anim.Value();
  private boolean selected;

  private ThemedCheckbox(int x, int y, int maxWidth, Component message, Font font,
      boolean selected, OnValueChange onValueChange) {
    super(x, y, Math.min(defaultWidth(message, font), maxWidth), HEIGHT, message);
    this.font = font;
    this.selected = selected;
    this.onValueChange = onValueChange;
  }

  public static Builder builder(Component message, Font font) {
    return new Builder(message, font);
  }

  private static int defaultWidth(Component message, Font font) {
    return TRACK_W + LABEL_GAP + font.width(message) + 2;
  }

  public boolean selected() {
    return selected;
  }

  public void setSelected(boolean selected) {
    this.selected = selected;
  }

  @Override
  public void onPress() {
    selected = !selected;
    onValueChange.onValueChange(this, selected);
  }

  @Override
  protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
    float t = knobAnim.approach(selected ? 1F : 0F, 14F);
    float hover = hoverAnim.approach(active && isHovered() ? 1F : 0F, 16F);
    int x = getX();
    int y = getY();
    float ty = y + (getHeight() - TRACK_H) / 2F;
    int offTrack = BuildPackTheme.withAlpha(BuildPackTheme.WIDGET_BORDER_HOVER,
        0x80 + (int) (0x30 * hover));
    int track = BuildPackTheme.lerpColor(offTrack, BuildPackTheme.MESSAGE_OK, t);
    if (!active) {
      track = BuildPackTheme.withAlpha(track, 0x60);
    }
    Shapes.pill(g, x, ty, TRACK_W, TRACK_H, track);
    float knob = TRACK_H - 3F;
    Shapes.circle(g, x + 1.5F + knob / 2 + t * (TRACK_W - TRACK_H), ty + TRACK_H / 2F, knob / 2,
        active ? 0xFFFFFFFF : 0xFFB0B0B0);

    int textColor = !active ? BuildPackTheme.TEXT_MUTED
        : BuildPackTheme.lerpColor(BuildPackTheme.TEXT_SECONDARY, BuildPackTheme.TEXT_PRIMARY,
            hover);
    int textX = x + TRACK_W + LABEL_GAP;
    UiScale.enableScissor(g, textX, y, x + getWidth(), y + getHeight());
    g.drawString(font, getMessage(), textX, y + (getHeight() - 8) / 2, textColor, false);
    g.disableScissor();
  }

  @Override
  protected void updateWidgetNarration(NarrationElementOutput output) {
    output.add(NarratedElementType.TITLE, Component.translatable(
        selected ? "options.on.composed" : "options.off.composed", getMessage()));
  }

  /** Mirrors {@code Checkbox.Builder}. */
  public static final class Builder {
    private final Component message;
    private final Font font;
    private int maxWidth;
    private int x;
    private int y;
    private boolean selected;
    private Tooltip tooltip;
    private OnValueChange onValueChange = OnValueChange.NOP;

    Builder(Component message, Font font) {
      this.message = message;
      this.font = font;
      this.maxWidth = defaultWidth(message, font);
    }

    public Builder pos(int x, int y) {
      this.x = x;
      this.y = y;
      return this;
    }

    public Builder selected(boolean selected) {
      this.selected = selected;
      return this;
    }

    public Builder onValueChange(OnValueChange onValueChange) {
      this.onValueChange = onValueChange;
      return this;
    }

    public Builder tooltip(Tooltip tooltip) {
      this.tooltip = tooltip;
      return this;
    }

    public Builder maxWidth(int maxWidth) {
      this.maxWidth = maxWidth;
      return this;
    }

    public ThemedCheckbox build() {
      ThemedCheckbox checkbox = new ThemedCheckbox(x, y, maxWidth, message,
          font == null ? Minecraft.getInstance().font : font, selected, onValueChange);
      checkbox.setTooltip(tooltip);
      return checkbox;
    }
  }
}
