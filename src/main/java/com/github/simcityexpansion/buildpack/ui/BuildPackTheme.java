package com.github.simcityexpansion.buildpack.ui;

import com.github.simcityexpansion.buildpack.ui.style.Shapes;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Color palette and surface rendering for the BuildPack UI.
 *
 * <p>Shares the look of the Krypton Hybrid dashboard: a GitHub-Primer inspired dark palette, a
 * translucent floating panel with rounded corners over a light world dim, rounded "cards" for the
 * inner panes, pill-shaped controls, hairline dividers, and flat (shadowless) text. Shapes are
 * anti-aliased through {@link Shapes}.
 */
public final class BuildPackTheme {
  private BuildPackTheme() {}

  // ---- Base palette (Primer dark) ----

  /** Floating panel fill before the panel opacity is applied. */
  public static final int PANEL_BASE = 0xFF0D1117;
  /** Header / tooltip surface. */
  public static final int HEADER_BG = 0xFF161B22;
  /** Widget (card, control) surface. */
  public static final int WIDGET_BG = 0xFF21262D;
  /** Widget border. */
  public static final int WIDGET_BORDER = 0xFF30363D;
  /** Widget border while hovered / off-state switch track. */
  public static final int WIDGET_BORDER_HOVER = 0xFF8B949E;
  /** Primary text (titles, values). */
  public static final int TEXT_PRIMARY = 0xFFF0F6FC;
  /** Secondary text (labels, inactive tabs). */
  public static final int TEXT_SECONDARY = 0xFFC9D1D9;
  /** Muted text (hints, counts, footers). */
  public static final int TEXT_MUTED = 0xFF8B949E;
  /** Accent hover. */
  public static final int ACCENT_HOVER = 0xFF79C0FF;
  /** Light accent (text on tinted accent fills, tooltip titles). */
  public static final int ACCENT_LIGHT = 0xFFA5D6FF;
  /** Secondary accent (deep blue). */
  public static final int ACCENT_SECONDARY = 0xFF1F6FEB;
  /** Tertiary accent (purple). */
  public static final int ACCENT_TERTIARY = 0xFF8957E5;
  /** Text-input fill. */
  public static final int INPUT_BG = 0xC00D1117;
  /** Scrollbar track. */
  public static final int SCROLL_TRACK = 0xFF21262D;
  /** Scrollbar thumb. */
  public static final int SCROLL_THUMB = 0xFF484F58;
  /** Divider / hairline color. */
  public static final int DIVIDER = 0xA0484F58;

  /** Floating-panel opacity (Krypton's default 85%). */
  public static final int PANEL_OPACITY_PERCENT = 85;

  /** Corner radius of the floating panel. */
  public static final float PANEL_RADIUS = 8F;
  /** Corner radius of cards / inner panes. */
  public static final float CARD_RADIUS = 5F;
  /** Corner radius of small controls (rows, inputs). */
  public static final float CONTROL_RADIUS = 4F;

  // ---- Semantic colors (names kept from the original Litematica-style theme) ----

  /** World dim drawn behind the floating panel. */
  public static final int ROOT_BG = 0x48000000;
  /** Floating panel fill at the configured opacity. */
  public static final int SURFACE_BG = withAlpha(PANEL_BASE, PANEL_OPACITY_PERCENT * 255 / 100);
  /** Card / list / detail pane fill. */
  public static final int PANEL_BG = 0xB021262D;
  /** Preview box fill (darker, so block models stand out). */
  public static final int PREVIEW_BG = 0x900D1117;
  /** Card / panel border. */
  public static final int BORDER = 0xD030363D;

  /** Accent color (active tab underline, highlights, primary buttons). */
  public static final int ACCENT = 0xFF58A6FF;
  /** Title text color. */
  public static final int TITLE = TEXT_PRIMARY;
  /** Info-row and form label color. */
  public static final int LABEL = TEXT_SECONDARY;
  /** Info value color. */
  public static final int VALUE = TEXT_PRIMARY;
  /** Count text color. */
  public static final int COUNT = TEXT_MUTED;
  /** Hint text color. */
  public static final int HINT = TEXT_MUTED;
  /** Success status message (green). */
  public static final int MESSAGE_OK = 0xFF3FB950;
  /** Warning status message (amber). */
  public static final int MESSAGE_WARN = 0xFFD29922;
  /** Error status message (red). */
  public static final int MESSAGE_ERROR = 0xFFF85149;
  /** Empty-state/placeholder text color. */
  public static final int NONE = TEXT_MUTED;

  /** Row hover tint. */
  public static final int ROW_HOVER = 0x2458A6FF;
  /** Row selection tint. */
  public static final int ROW_SELECTED = 0x4458A6FF;
  /** Alternating-row tint. */
  public static final int ROW_ALT = 0x0CFFFFFF;

  /** Button normal fill color (tinted accent). */
  public static final int BUTTON_BG = 0x3058A6FF;
  /** Button hovered fill color. */
  public static final int BUTTON_BG_HOVER = 0x5858A6FF;
  /** Button disabled fill color. */
  public static final int BUTTON_BG_DISABLED = 0x6030363D;
  /** Button normal border color (unused by pill buttons; kept for outlines). */
  public static final int BUTTON_BORDER = BORDER;
  /** Button hovered border color. */
  public static final int BUTTON_BORDER_HOVER = ACCENT;
  /** Button normal text color. */
  public static final int BUTTON_TEXT = ACCENT_LIGHT;
  /** Button disabled text color. */
  public static final int BUTTON_TEXT_DISABLED = TEXT_MUTED;

  /** Tooltip / popup fill. */
  public static final int POPUP_BG = 0xF2161B22;
  /** Tooltip / popup border. */
  public static final int POPUP_BORDER = 0xA08B949E;

  // ---- Surfaces ----

  /**
   * Backdrop behind a floating panel: a light dim over the world, or the Primer canvas color when
   * there is no world (opened from the mods list), so the panel never floats over a black void.
   */
  public static void backdrop(GuiGraphics g, int width, int height) {
    if (Minecraft.getInstance().level == null) {
      g.fill(0, 0, width, height, PANEL_BASE);
    } else {
      g.fill(0, 0, width, height, ROOT_BG);
    }
  }

  /** The translucent floating panel (rounded, with a soft border). */
  public static void surface(GuiGraphics g, int x, int y, int w, int h) {
    if (w <= 0 || h <= 0) {
      return;
    }
    Shapes.roundRect(g, x, y, w, h, PANEL_RADIUS, SURFACE_BG);
    Shapes.roundRectStroke(g, x, y, w, h, PANEL_RADIUS, Shapes.stroke(),
        withAlpha(WIDGET_BORDER, 0xD0));
  }

  /** Backdrop plus a floating panel: the frame of every modal dialog. */
  public static void dialog(GuiGraphics g, int screenW, int screenH, int x, int y, int w, int h) {
    backdrop(g, screenW, screenH);
    surface(g, x, y, w, h);
  }

  /** Full-screen work surface for editors: backdrop plus an edge-to-edge panel. */
  public static void screenSurface(GuiGraphics g, int width, int height) {
    backdrop(g, width, height);
    surface(g, 2, 2, width - 4, height - 4);
  }

  /** Draws a rounded card (list/detail pane). */
  public static void panel(GuiGraphics g, int x, int y, int w, int h) {
    fillPanel(g, x, y, w, h, PANEL_BG);
  }

  /** Draws a preview box (darker fill + border). */
  public static void previewPanel(GuiGraphics g, int x, int y, int w, int h) {
    fillPanel(g, x, y, w, h, PREVIEW_BG);
  }

  /** Draws a bordered rounded card with a custom fill color. */
  public static void fillPanel(GuiGraphics g, int x, int y, int w, int h, int fill) {
    if (w <= 0 || h <= 0) {
      return;
    }
    Shapes.roundRect(g, x, y, w, h, CARD_RADIUS, fill);
    Shapes.roundRectStroke(g, x, y, w, h, CARD_RADIUS, Shapes.stroke(), BORDER);
  }

  /** Draws only a rounded border (selection outlines, slot highlights). */
  public static void border(GuiGraphics g, int x, int y, int w, int h, int color) {
    if (w <= 0 || h <= 0) {
      return;
    }
    Shapes.roundRectStroke(g, x, y, w, h, Math.min(3F, Math.min(w, h) / 4F),
        Math.max(Shapes.stroke(), 1F), color);
  }

  /** A rounded hover/selection highlight behind a list row. */
  public static void rowHighlight(GuiGraphics g, int x, int y, int w, int h, int color) {
    Shapes.roundRect(g, x, y, w, h, CONTROL_RADIUS, color);
  }

  /** Horizontal hairline divider. */
  public static void divider(GuiGraphics g, int x0, int x1, float y) {
    Shapes.hairline(g, x0, x1, y, DIVIDER);
  }

  /** Thin pill scrollbar: track spanning {@code y..y+h}, thumb at {@code thumbY..thumbY+thumbH}. */
  public static void scrollbar(GuiGraphics g, int x, int y, int h, int thumbY, int thumbH) {
    Shapes.pill(g, x, y, 3, h, SCROLL_TRACK);
    Shapes.pill(g, x, thumbY, 3, Math.max(6, thumbH), SCROLL_THUMB);
  }

  /** Popup surface (context menus, custom tooltips). */
  public static void popup(GuiGraphics g, int x, int y, int w, int h) {
    Shapes.roundRect(g, x, y, w, h, CARD_RADIUS, POPUP_BG);
    Shapes.roundRectStroke(g, x, y, w, h, CARD_RADIUS, Shapes.stroke(), POPUP_BORDER);
  }

  // ---- Color math ----

  public static int lerpColor(int c1, int c2, float t) {
    t = Math.max(0, Math.min(1, t));
    int a = (int) (((c1 >> 24) & 0xFF) + (((c2 >> 24) & 0xFF) - ((c1 >> 24) & 0xFF)) * t);
    int r = (int) (((c1 >> 16) & 0xFF) + (((c2 >> 16) & 0xFF) - ((c1 >> 16) & 0xFF)) * t);
    int gr = (int) (((c1 >> 8) & 0xFF) + (((c2 >> 8) & 0xFF) - ((c1 >> 8) & 0xFF)) * t);
    int b = (int) ((c1 & 0xFF) + ((c2 & 0xFF) - (c1 & 0xFF)) * t);
    return (a << 24) | (r << 16) | (gr << 8) | b;
  }

  public static int withAlpha(int color, int alpha) {
    return (color & 0x00FFFFFF) | (alpha << 24);
  }
}
