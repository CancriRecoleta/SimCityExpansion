package com.github.simcityexpansion.buildpack.ui;

import net.minecraft.client.gui.GuiGraphics;

/**
 * Color palette and panel rendering for the BuildPack UI (replaces the original
 * {@code buildpack.lss} stylesheet).
 *
 * <p>All values are cross-referenced against the Litematica/malilib source: full-screen dim
 * {@code #b0000000}, list/detail panels via {@code drawOutlinedBox} (fill {@code #a0000000} +
 * 1 px border {@code #999999}), preview box fill more transparent ({@code #30000000}), text
 * labels grey {@code #c0c0c0}, values white.
 */
public final class BuildPackTheme {
  private BuildPackTheme() {}

  /** Full-screen semi-transparent dim color (GuiBase.TOOLTIP_BACKGROUND). */
  public static final int ROOT_BG = 0xB0000000;
  /** List/detail panel fill color. */
  public static final int PANEL_BG = 0xA0000000;
  /** Preview box fill color (more transparent, to highlight block models). */
  public static final int PREVIEW_BG = 0x30000000;
  /** 1 px border color for panels and preview boxes. */
  public static final int BORDER = 0xFF999999;

  /** Accent color (active tab underline, highlights). */
  public static final int ACCENT = 0xFF4A90D9;
  /** Title text color. */
  public static final int TITLE = 0xFFFFFFFF;
  /** Info-row and form label grey. */
  public static final int LABEL = 0xFFC0C0C0;
  /** Info value white. */
  public static final int VALUE = 0xFFFFFFFF;
  /** Count text grey. */
  public static final int COUNT = 0xFFA0A0A0;
  /** Hint text grey. */
  public static final int HINT = 0xFF808080;
  /** Success status message (green). */
  public static final int MESSAGE_OK = 0xFF55FF55;
  /** Warning status message (yellow). */
  public static final int MESSAGE_WARN = 0xFFFFD860;
  /** Error status message (red). */
  public static final int MESSAGE_ERROR = 0xFFFF6060;
  /** Empty-state/placeholder text grey. */
  public static final int NONE = 0xFF808080;

  /** Button normal fill color. */
  public static final int BUTTON_BG = 0xC0303030;
  /** Button hovered fill color. */
  public static final int BUTTON_BG_HOVER = 0xD0505050;
  /** Button disabled fill color. */
  public static final int BUTTON_BG_DISABLED = 0x90202020;
  /** Button normal border color. */
  public static final int BUTTON_BORDER = 0xFF999999;
  /** Button hovered border color. */
  public static final int BUTTON_BORDER_HOVER = 0xFFFFFFFF;
  /** Button normal text color. */
  public static final int BUTTON_TEXT = 0xFFE0E0E0;
  /** Button disabled text color. */
  public static final int BUTTON_TEXT_DISABLED = 0xFF808080;

  /** Draws a panel with a 1 px border (equivalent to Litematica's drawOutlinedBox). */
  public static void panel(GuiGraphics g, int x, int y, int w, int h) {
    fillPanel(g, x, y, w, h, PANEL_BG);
  }

  /** Draws a preview box (more-transparent fill + border). */
  public static void previewPanel(GuiGraphics g, int x, int y, int w, int h) {
    fillPanel(g, x, y, w, h, PREVIEW_BG);
  }

  /** Draws a bordered panel with a custom fill color. */
  public static void fillPanel(GuiGraphics g, int x, int y, int w, int h, int fill) {
    if (w <= 0 || h <= 0) {
      return;
    }
    g.fill(x, y, x + w, y + h, fill);
    border(g, x, y, w, h, BORDER);
  }

  /** Draws only a 1 px border. */
  public static void border(GuiGraphics g, int x, int y, int w, int h, int color) {
    if (w <= 0 || h <= 0) {
      return;
    }
    g.fill(x, y, x + w, y + 1, color);
    g.fill(x, y + h - 1, x + w, y + h, color);
    g.fill(x, y + 1, x + 1, y + h - 1, color);
    g.fill(x + w - 1, y + 1, x + w, y + h - 1, color);
  }
}
