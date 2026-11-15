package com.github.simcityexpansion.buildpack.ui;

import net.minecraft.client.gui.GuiGraphics;

/**
 * Manager-UI scale support. The manager renders inside a {@code pose().scale(s, s, 1)} transform so
 * the whole screen can be zoomed; this holds the scale currently being rendered with (so scissor
 * rects, which {@link GuiGraphics#enableScissor} applies in raw screen space ignoring the pose, can
 * be corrected) plus the user's persisted preference.
 */
public final class UiScale {
  private UiScale() {}

  public static final float MIN = 0.5f;
  public static final float MAX = 2.0f;
  public static final float STEP = 0.1f;

  /** Scale of the frame currently being rendered (1.0 outside the manager). */
  private static float current = 1.0f;

  public static void set(float scale) {
    current = scale;
  }

  public static float current() {
    return current;
  }

  /** The user's chosen manager scale (persisted by {@link UiSettings}). */
  public static float preference() {
    return UiSettings.scale();
  }

  /** Sets and persists the preference (snapped to {@link #STEP} and clamped to [{@link #MIN}, {@link #MAX}]); returns the applied value. */
  public static float setPreference(float scale) {
    return UiSettings.setScale(scale);
  }

  /**
   * Scissor that accounts for the active UI pose scale. {@link GuiGraphics#enableScissor} clips in
   * screen space and ignores the pose matrix, so when the manager is zoomed the rect must be scaled
   * by the same factor the content is drawn with.
   */
  public static void enableScissor(GuiGraphics g, int x1, int y1, int x2, int y2) {
    if (current == 1.0f) {
      g.enableScissor(x1, y1, x2, y2);
    } else {
      g.enableScissor(Math.round(x1 * current), Math.round(y1 * current),
          Math.round(x2 * current), Math.round(y2 * current));
    }
  }
}
