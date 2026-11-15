package com.github.simcityexpansion.buildpack.ui;

import com.github.simcityexpansion.buildpack.ui.style.Shapes;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Color palette and surface rendering for the BuildPack UI.
 *
 * <p>Shares the look of the Krypton Hybrid dashboard: a GitHub-Primer inspired dark or light
 * {@link Palette}, a translucent floating panel with rounded corners over a light world dim,
 * rounded "cards" for the inner panes, pill-shaped controls, hairline dividers, and flat
 * (shadowless) text. Shapes are anti-aliased through {@link Shapes}.
 *
 * <p>Colors are read through accessor methods every frame, so switching the theme or the panel
 * opacity in {@link UiSettings} takes effect immediately on every open screen.
 */
public final class BuildPackTheme {
  private BuildPackTheme() {}

  /** Theme variants. */
  public enum Mode { DARK, LIGHT }

  /** One theme's base colors. */
  public record Palette(
      int panelBase, int headerBg, int widgetBg, int widgetBorder, int widgetBorderHover,
      int textPrimary, int textSecondary, int textMuted,
      int accent, int accentHover, int accentLight,
      int success, int warning, int danger,
      int inputBg, int scrollTrack, int scrollThumb, int divider,
      int cardBg, int previewBg, int border, int rowAlt, int popupBg, int popupBorder,
      int worldDim) {}

  /** Primer dark (Krypton's default). */
  public static final Palette DARK = new Palette(
      0xFF0D1117, 0xFF161B22, 0xFF21262D, 0xFF30363D, 0xFF8B949E,
      0xFFF0F6FC, 0xFFC9D1D9, 0xFF8B949E,
      0xFF58A6FF, 0xFF79C0FF, 0xFFA5D6FF,
      0xFF3FB950, 0xFFD29922, 0xFFF85149,
      0xC00D1117, 0xFF21262D, 0xFF484F58, 0xA0484F58,
      0xB021262D, 0x900D1117, 0xD030363D, 0x0CFFFFFF, 0xF2161B22, 0xA08B949E,
      0x48000000);

  /** Primer light. */
  public static final Palette LIGHT = new Palette(
      0xFFFFFFFF, 0xFFF6F8FA, 0xFFF6F8FA, 0xFFD0D7DE, 0xFF57606A,
      0xFF1F2328, 0xFF656D76, 0xFF8C959F,
      0xFF0969DA, 0xFF0550AE, 0xFF0969DA,
      0xFF1A7F37, 0xFF9A6700, 0xFFCF222E,
      0xE0FFFFFF, 0xFFEAEEF2, 0xFFAFB8C1, 0xFFD8DEE4,
      0xC0F6F8FA, 0xD0AFB8C1, 0xFFD0D7DE, 0x08000000, 0xF8FFFFFF, 0xFFD0D7DE,
      0x30000000);

  /** Corner radius of the floating panel. */
  public static final float PANEL_RADIUS = 8F;
  /** Corner radius of cards / inner panes. */
  public static final float CARD_RADIUS = 5F;
  /** Corner radius of small controls (rows, inputs). */
  public static final float CONTROL_RADIUS = 4F;

  /** The active palette. */
  public static Palette colors() {
    return UiSettings.theme() == Mode.LIGHT ? LIGHT : DARK;
  }

  /** Whether the light theme is active. */
  public static boolean light() {
    return UiSettings.theme() == Mode.LIGHT;
  }

  // ---- Base colors ----

  /** Floating panel fill before the panel opacity is applied. */
  public static int panelBase() {
    return colors().panelBase();
  }

  /** Header / tooltip surface. */
  public static int headerBg() {
    return colors().headerBg();
  }

  /** Widget (card, control) surface. */
  public static int widgetBg() {
    return colors().widgetBg();
  }

  /** Widget border. */
  public static int widgetBorder() {
    return colors().widgetBorder();
  }

  /** Widget border while hovered / off-state switch track. */
  public static int widgetBorderHover() {
    return colors().widgetBorderHover();
  }

  /** Primary text (titles, values). */
  public static int textPrimary() {
    return colors().textPrimary();
  }

  /** Secondary text (labels, inactive tabs). */
  public static int textSecondary() {
    return colors().textSecondary();
  }

  /** Muted text (hints, counts, footers). */
  public static int textMuted() {
    return colors().textMuted();
  }

  /** Accent color (active tab underline, highlights, primary buttons). */
  public static int accent() {
    return colors().accent();
  }

  /** Accent hover. */
  public static int accentHover() {
    return colors().accentHover();
  }

  /** Accent used for text on tinted accent fills (buttons, links, tooltip titles). */
  public static int accentLight() {
    return colors().accentLight();
  }

  /** Text-input fill. */
  public static int inputBg() {
    return colors().inputBg();
  }

  /** Divider / hairline color. */
  public static int divider() {
    return colors().divider();
  }

  // ---- Semantic colors ----

  /** World dim drawn behind the floating panel. */
  public static int rootBg() {
    return colors().worldDim();
  }

  /** Floating panel fill at the configured opacity. */
  public static int surfaceBg() {
    return withAlpha(panelBase(), UiSettings.panelOpacity() * 255 / 100);
  }

  /** Card / list / detail pane fill. */
  public static int panelBg() {
    return colors().cardBg();
  }

  /** Preview box fill (a neutral canvas, so block models stand out). */
  public static int previewBg() {
    return colors().previewBg();
  }

  /** Card / panel border. */
  public static int border() {
    return colors().border();
  }

  /** Title text color. */
  public static int title() {
    return textPrimary();
  }

  /** Info-row and form label color. */
  public static int label() {
    return textSecondary();
  }

  /** Info value color. */
  public static int value() {
    return textPrimary();
  }

  /** Count text color. */
  public static int count() {
    return textMuted();
  }

  /** Hint text color. */
  public static int hint() {
    return textMuted();
  }

  /** Empty-state/placeholder text color. */
  public static int none() {
    return textMuted();
  }

  /** Success status message (green). */
  public static int messageOk() {
    return colors().success();
  }

  /** Warning status message (amber). */
  public static int messageWarn() {
    return colors().warning();
  }

  /** Error status message (red). */
  public static int messageError() {
    return colors().danger();
  }

  /** Row hover tint. */
  public static int rowHover() {
    return withAlpha(accent(), 0x24);
  }

  /** Row selection tint. */
  public static int rowSelected() {
    return withAlpha(accent(), 0x44);
  }

  /** Alternating-row tint. */
  public static int rowAlt() {
    return colors().rowAlt();
  }

  /** Button disabled fill color. */
  public static int buttonBgDisabled() {
    return withAlpha(widgetBorder(), 0x60);
  }

  /** Button disabled text color. */
  public static int buttonTextDisabled() {
    return textMuted();
  }

  /** Tooltip / popup fill. */
  public static int popupBg() {
    return colors().popupBg();
  }

  /** Tooltip / popup border. */
  public static int popupBorder() {
    return colors().popupBorder();
  }

  // ---- Surfaces ----

  /**
   * Backdrop behind a floating panel: a light dim over the world, or the panel base color when
   * there is no world (opened from the mods list), so the panel never floats over a black void.
   */
  public static void backdrop(GuiGraphics g, int width, int height) {
    if (Minecraft.getInstance().level == null) {
      g.fill(0, 0, width, height, panelBase());
    } else {
      g.fill(0, 0, width, height, rootBg());
    }
  }

  /** The translucent floating panel (rounded, with a soft border). */
  public static void surface(GuiGraphics g, int x, int y, int w, int h) {
    if (w <= 0 || h <= 0) {
      return;
    }
    Shapes.roundRect(g, x, y, w, h, PANEL_RADIUS, surfaceBg());
    Shapes.roundRectStroke(g, x, y, w, h, PANEL_RADIUS, Shapes.stroke(),
        withAlpha(widgetBorder(), 0xD0));
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
    fillPanel(g, x, y, w, h, panelBg());
  }

  /** Draws a preview box (canvas fill + border). */
  public static void previewPanel(GuiGraphics g, int x, int y, int w, int h) {
    fillPanel(g, x, y, w, h, previewBg());
  }

  /** Draws a bordered rounded card with a custom fill color. */
  public static void fillPanel(GuiGraphics g, int x, int y, int w, int h, int fill) {
    if (w <= 0 || h <= 0) {
      return;
    }
    Shapes.roundRect(g, x, y, w, h, CARD_RADIUS, fill);
    Shapes.roundRectStroke(g, x, y, w, h, CARD_RADIUS, Shapes.stroke(), border());
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
    Shapes.hairline(g, x0, x1, y, divider());
  }

  /** Thin pill scrollbar: track spanning {@code y..y+h}, thumb at {@code thumbY..thumbY+thumbH}. */
  public static void scrollbar(GuiGraphics g, int x, int y, int h, int thumbY, int thumbH) {
    Shapes.pill(g, x, y, 3, h, colors().scrollTrack());
    Shapes.pill(g, x, thumbY, 3, Math.max(6, thumbH), colors().scrollThumb());
  }

  /** Popup surface (context menus, custom tooltips). */
  public static void popup(GuiGraphics g, int x, int y, int w, int h) {
    Shapes.roundRect(g, x, y, w, h, CARD_RADIUS, popupBg());
    Shapes.roundRectStroke(g, x, y, w, h, CARD_RADIUS, Shapes.stroke(), popupBorder());
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
