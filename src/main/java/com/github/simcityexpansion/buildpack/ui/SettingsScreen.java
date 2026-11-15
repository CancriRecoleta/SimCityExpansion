package com.github.simcityexpansion.buildpack.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

import com.github.simcityexpansion.buildpack.ui.style.Anim;
import com.github.simcityexpansion.buildpack.ui.style.Shapes;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;

/**
 * Krypton-style settings panel for the BuildPack UI: a floating panel over the (still visible)
 * manager with appearance, world-capture, and controls sections. Rows are drawn immediate-mode —
 * each frame rebuilds the clickable {@link Hit} regions — with eased hover fades, a sliding
 * segmented-control pill, and sliding toggle knobs. Every change is applied and saved at once (see
 * {@link UiSettings}); theme and opacity changes show live on the manager behind the panel.
 */
public final class SettingsScreen extends Screen {

  private record Hit(int x, int y, int w, int h, Runnable action) {
    boolean contains(double mx, double my) {
      return mx >= x && mx < x + w && my >= y && my < y + h;
    }
  }

  private static final int MAX_W = 300;
  private static final int PAD = 10;
  private static final int HEADER_H = 26;
  private static final int SECTION_H = 15;
  private static final int ROW_H = 20;
  private static final int SECTION_GAP = 5;
  private static final int FOOTER_H = 24;
  private static final int ICON = 14;

  @Nullable
  private final Screen parent;
  private final Anim anim = new Anim();
  private final List<Hit> hits = new ArrayList<>();
  @Nullable
  private List<Component> tooltip;
  private int mouseX;
  private int mouseY;
  private int px;
  private int py;
  private int pw;
  private int ph;

  public SettingsScreen(@Nullable Screen parent) {
    super(Component.translatable("buildpack.settings.title"));
    this.parent = parent;
  }

  /** Opens the settings panel on top of {@code parent} (returned to on close). */
  public static void open(@Nullable Screen parent) {
    Minecraft.getInstance().setScreen(new SettingsScreen(parent));
  }

  @Override
  protected void init() {
    pw = Math.min(MAX_W, width - 16);
    ph = HEADER_H + 3 * (SECTION_H + SECTION_GAP) + 6 * ROW_H + FOOTER_H + 4;
    px = (width - pw) / 2;
    py = Math.max(4, (height - ph) / 2);
  }

  @Override
  public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
    // Drawn by render(): the parent screen, then a light dim.
  }

  @Override
  public void onClose() {
    minecraft.setScreen(parent);
  }

  // ---- Rendering ----

  @Override
  public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
    this.mouseX = mouseX;
    this.mouseY = mouseY;
    hits.clear();
    tooltip = null;
    anim.frame();

    if (drawParent(g, partialTick)) {
      g.fill(0, 0, width, height, BuildPackTheme.rootBg());
    } else {
      BuildPackTheme.backdrop(g, width, height);
    }
    BuildPackTheme.surface(g, px, py, pw, ph);
    renderHeader(g);

    int x = px + PAD - 4;
    int w = pw - (PAD - 4) * 2;
    int y = py + HEADER_H + 2;

    y = section(g, y, "buildpack.settings.section.appearance");
    String[] themes = {
        translate("buildpack.settings.theme.dark"), translate("buildpack.settings.theme.light")};
    y = segmentedRow(g, x, y, w, "theme", "buildpack.settings.theme", themes,
        UiSettings.theme().ordinal(),
        i -> UiSettings.setTheme(BuildPackTheme.Mode.values()[i]));
    String[] opacities = new String[UiSettings.OPACITY_STEPS.length];
    int opacityIndex = -1;
    for (int i = 0; i < opacities.length; i++) {
      opacities[i] = UiSettings.OPACITY_STEPS[i] + "%";
      if (UiSettings.OPACITY_STEPS[i] == UiSettings.panelOpacity()) {
        opacityIndex = i;
      }
    }
    y = segmentedRow(g, x, y, w, "opacity", "buildpack.settings.opacity", opacities,
        opacityIndex, i -> UiSettings.setPanelOpacity(UiSettings.OPACITY_STEPS[i]));
    y = stepperRow(g, x, y, w, "scale", "buildpack.settings.scale",
        Math.round(UiSettings.scale() * 100) + "%",
        UiSettings.scale() > UiScale.MIN + 0.001f, UiSettings.scale() < UiScale.MAX - 0.001f,
        delta -> changeScale(delta * UiScale.STEP));

    y = section(g, y + SECTION_GAP, "buildpack.settings.section.capture");
    y = toggleRow(g, x, y, w, "capture_contents", "buildpack.settings.capture_contents",
        UiSettings.captureBlockEntities(),
        () -> UiSettings.setCaptureBlockEntities(!UiSettings.captureBlockEntities()));
    y = toggleRow(g, x, y, w, "selection_hud", "buildpack.settings.selection_hud",
        UiSettings.selectionHud(), () -> UiSettings.setSelectionHud(!UiSettings.selectionHud()));

    y = section(g, y + SECTION_GAP, "buildpack.settings.section.controls");
    actionRow(g, x, y, w, "keybinds", "buildpack.settings.keybinds",
        translate("buildpack.settings.keybinds.open"),
        () -> minecraft.setScreen(new KeyBindsScreen(this, minecraft.options)));

    renderFooter(g);
    super.render(g, mouseX, mouseY, partialTick);
    if (tooltip != null && !tooltip.isEmpty()) {
      renderTooltip(g, tooltip, mouseX + 10, mouseY + 10);
    }
  }

  /**
   * Draws the screen this panel was opened from underneath it, so theme and opacity changes are
   * visible live. Skipped when the parent was laid out for a different window size.
   */
  private boolean drawParent(GuiGraphics g, float partialTick) {
    if (parent == null || parent.width != width || parent.height != height) {
      return false;
    }
    // Off-screen cursor: no hover states or tooltips on the parent.
    parent.render(g, -10_000, -10_000, partialTick);
    g.flush();
    // The parent may draw items or 3D previews at high z; start this panel on a clean depth buffer.
    RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
    return true;
  }

  private void renderHeader(GuiGraphics g) {
    Component heading = title.copy().withStyle(ChatFormatting.BOLD);
    g.drawString(font, heading, px + PAD, py + 9, BuildPackTheme.textPrimary(), false);
    iconButton(g, px + pw - 6 - ICON, py + 6, IconButton.CLOSE,
        List.of(Component.translatable("buildpack.settings.close")), this::onClose);
    BuildPackTheme.divider(g, px + PAD, px + pw - PAD, py + HEADER_H - 2);
  }

  private void renderFooter(GuiGraphics g) {
    int y = py + ph - FOOTER_H;
    BuildPackTheme.divider(g, px + PAD, px + pw - PAD, y);
    int doneLeft = textButton(g, px + pw - PAD, y + 6, "done",
        translate("buildpack.settings.done"), this::onClose);
    String hint = translate("buildpack.settings.footer");
    int maxW = doneLeft - 6 - (px + PAD);
    if (font.width(hint) > maxW) {
      hint = font.plainSubstrByWidth(hint, Math.max(0, maxW - font.width("…"))) + "…";
    }
    g.drawString(font, hint, px + PAD, y + 9, BuildPackTheme.textMuted(), false);
  }

  private void changeScale(float delta) {
    float before = UiSettings.scale();
    if (UiSettings.setScale(before + delta) != before && parent instanceof BuildPackScreen manager
        && parent.width == width && parent.height == height) {
      manager.reloadScale();
    }
  }

  // ---- Rows (Krypton dashboard style) ----

  private int section(GuiGraphics g, int y, String key) {
    g.drawString(font, translate(key), px + PAD, y + 4, BuildPackTheme.textSecondary(), false);
    return y + SECTION_H;
  }

  /** Row with a hover highlight, a label, and a tooltip from {@code key} and {@code key.tip}. */
  private boolean rowBase(GuiGraphics g, int x, int y, int w, String id, String key) {
    boolean hover = inside(x, y, w, ROW_H);
    float hoverAnim = anim.hover("row." + id, hover);
    if (hoverAnim > 0.01F) {
      Shapes.roundRect(g, x, y, w, ROW_H - 1, BuildPackTheme.CONTROL_RADIUS,
          BuildPackTheme.withAlpha(BuildPackTheme.accent(), (int) (0x24 * hoverAnim)));
    }
    if (hover) {
      tooltip = List.of(Component.translatable(key), Component.translatable(key + ".tip"));
    }
    g.drawString(font, translate(key), x + 6, y + (ROW_H - 8) / 2, BuildPackTheme.textPrimary(),
        false);
    return hover;
  }

  private int toggleRow(GuiGraphics g, int x, int y, int w, String id, String key, boolean on,
      Runnable toggle) {
    rowBase(g, x, y, w, id, key);
    toggleSwitch(g, x + w - 24, y + (ROW_H - 10) / 2F, id, on);
    hits.add(new Hit(x, y, w, ROW_H, toggle));
    return y + ROW_H;
  }

  private int segmentedRow(GuiGraphics g, int x, int y, int w, String id, String key,
      String[] options, int selected, IntConsumer select) {
    rowBase(g, x, y, w, id, key);
    segmented(g, x + w - 7, y + (ROW_H - 11) / 2F, id, options, selected, y, ROW_H, select);
    return y + ROW_H;
  }

  /** Row with "-  value  +" on the right; {@code step} receives -1 or +1. */
  private int stepperRow(GuiGraphics g, int x, int y, int w, String id, String key, String value,
      boolean canDecrease, boolean canIncrease, IntConsumer step) {
    rowBase(g, x, y, w, id, key);
    int bw = 16;
    int bh = 12;
    int by = y + (ROW_H - bh) / 2;
    int plusX = x + w - 7 - bw;
    int valueW = Math.max(font.width(value), font.width("200%")) + 10;
    int minusX = plusX - valueW - bw;
    stepButton(g, minusX, by, bw, bh, id + ".minus", "-", canDecrease, () -> step.accept(-1));
    stepButton(g, plusX, by, bw, bh, id + ".plus", "+", canIncrease, () -> step.accept(1));
    g.drawString(font, value, minusX + bw + (valueW - font.width(value)) / 2, by + 2,
        BuildPackTheme.textPrimary(), false);
    return y + ROW_H;
  }

  /** Row with a value pill ending in "›"; the whole row triggers {@code action}. */
  private int actionRow(GuiGraphics g, int x, int y, int w, String id, String key, String label,
      Runnable action) {
    boolean hover = rowBase(g, x, y, w, id, key);
    float hoverAnim = anim.hover("action." + id, hover);
    String text = label + "  ›";
    float bw = font.width(text) + 12;
    float bx = x + w - 7 - bw;
    float by = y + (ROW_H - 11) / 2F;
    Shapes.pill(g, bx, by, bw, 11F,
        BuildPackTheme.withAlpha(BuildPackTheme.accent(), 0x38 + (int) (0x30 * hoverAnim)));
    g.drawString(font, text, (int) (bx + 6), (int) (by + 2), BuildPackTheme.accentLight(), false);
    hits.add(new Hit(x, y, w, ROW_H, action));
    return y + ROW_H;
  }

  // ---- Small controls ----

  /** 18x10 switch with a sliding knob that turns green when on. */
  private void toggleSwitch(GuiGraphics g, float x, float y, String id, boolean on) {
    float t = anim.approach("toggle." + id, on ? 1F : 0F, 14F);
    float tw = 18F;
    float th = 10F;
    int offTrack = BuildPackTheme.withAlpha(BuildPackTheme.widgetBorderHover(), 0xA0);
    Shapes.pill(g, x, y, tw, th, BuildPackTheme.lerpColor(offTrack, BuildPackTheme.messageOk(), t));
    float knob = th - 3F;
    Shapes.circle(g, x + 1.5F + knob / 2 + t * (tw - th), y + th / 2, knob / 2, 0xFFFFFFFF);
  }

  private int segmentedWidth(String[] options) {
    int total = 0;
    for (String option : options) {
      total += font.width(option) + 12;
    }
    return total;
  }

  /**
   * Segmented control ending at {@code right}; the selection pill slides to the chosen option.
   * Each option registers a hit spanning {@code hitY..hitY+hitH}.
   */
  private void segmented(GuiGraphics g, float right, float y, String id, String[] options,
      int selected, int hitY, int hitH, IntConsumer select) {
    int pad = 6;
    int total = segmentedWidth(options);
    float th = 11F;
    float tx = right - total;
    Shapes.pill(g, tx - 1, y - 1, total + 2, th + 2,
        BuildPackTheme.withAlpha(BuildPackTheme.widgetBorder(), 0xB0));
    if (selected >= 0) {
      float selX = tx;
      for (int i = 0; i < selected; i++) {
        selX += font.width(options[i]) + pad * 2;
      }
      float sx = anim.approach("segment.x." + id, selX, 18F);
      float sw = anim.approach("segment.w." + id, font.width(options[selected]) + pad * 2, 18F);
      Shapes.pill(g, sx, y, sw, th, BuildPackTheme.accent());
    }
    float ox = tx;
    for (int i = 0; i < options.length; i++) {
      int w = font.width(options[i]) + pad * 2;
      boolean hover = inside((int) ox, (int) y, w, (int) th);
      int color = i == selected ? 0xFFFFFFFF
          : BuildPackTheme.lerpColor(BuildPackTheme.textSecondary(), BuildPackTheme.textPrimary(),
              anim.hover("segment." + id + "." + i, hover));
      g.drawString(font, options[i], (int) ox + pad, (int) y + 2, color, false);
      final int index = i;
      hits.add(new Hit((int) ox, hitY, w, hitH, () -> select.accept(index)));
      ox += w;
    }
  }

  private void stepButton(GuiGraphics g, int x, int y, int w, int h, String id, String glyph,
      boolean enabled, Runnable action) {
    float hover = anim.hover("step." + id, enabled && inside(x, y, w, h));
    int bg = enabled
        ? BuildPackTheme.withAlpha(BuildPackTheme.accent(), 0x30 + (int) (0x30 * hover))
        : BuildPackTheme.buttonBgDisabled();
    Shapes.pill(g, x, y, w, h, bg);
    int color = enabled ? BuildPackTheme.accentLight() : BuildPackTheme.textMuted();
    g.drawString(font, glyph, x + (w - font.width(glyph) + 1) / 2, y + 2, color, false);
    if (enabled) {
      hits.add(new Hit(x, y, w, h, action));
    }
  }

  /** Text button drawn as a solid accent pill ending at {@code rightX}; returns its left x. */
  private int textButton(GuiGraphics g, int rightX, int y, String id, String label,
      Runnable action) {
    int w = font.width(label) + 16;
    int h = 13;
    int x = rightX - w;
    float hover = anim.hover("button." + id, inside(x, y, w, h));
    Shapes.pill(g, x, y, w, h,
        BuildPackTheme.lerpColor(BuildPackTheme.accent(), BuildPackTheme.accentHover(), hover));
    g.drawString(font, label, x + 8, y + 3, 0xFFFFFFFF, false);
    hits.add(new Hit(x, y, w, h, action));
    return x;
  }

  /** Round icon button; the close glyph tints red on hover, others use the accent. */
  private void iconButton(GuiGraphics g, int x, int y, String glyph, List<Component> tip,
      Runnable action) {
    boolean hover = inside(x, y, ICON, ICON);
    float hoverAnim = anim.hover("icon." + glyph, hover);
    int tint = IconButton.CLOSE.equals(glyph)
        ? BuildPackTheme.messageError() : BuildPackTheme.accent();
    if (hoverAnim > 0.01F) {
      Shapes.circle(g, x + ICON / 2F, y + ICON / 2F, ICON / 2F,
          BuildPackTheme.withAlpha(tint, (int) (0x50 * hoverAnim)));
    }
    int color = BuildPackTheme.lerpColor(BuildPackTheme.textSecondary(),
        BuildPackTheme.textPrimary(), hoverAnim);
    g.drawString(font, glyph, x + (ICON - font.width(glyph) + 1) / 2, y + (ICON - 8) / 2, color,
        false);
    hits.add(new Hit(x, y, ICON, ICON, action));
    if (hover) {
      tooltip = tip;
    }
  }

  /** Rounded popup tooltip: first line in the accent, the rest wrapped in secondary text. */
  private void renderTooltip(GuiGraphics g, List<Component> lines, int x, int y) {
    int wrapW = 200;
    List<FormattedCharSequence> wrapped = new ArrayList<>();
    int titleLines = 0;
    for (int i = 0; i < lines.size(); i++) {
      List<FormattedCharSequence> split = font.split(lines.get(i), wrapW);
      wrapped.addAll(split);
      if (i == 0) {
        titleLines = split.size();
      }
    }
    int maxW = 0;
    for (FormattedCharSequence line : wrapped) {
      maxW = Math.max(maxW, font.width(line));
    }
    int pad = 5;
    int w = maxW + pad * 2;
    int h = wrapped.size() * 10 + pad * 2 - 1;
    int tx = Math.max(2, Math.min(x, width - w - 4));
    int ty = Math.max(2, Math.min(y, height - h - 4));
    g.pose().pushPose();
    g.pose().translate(0, 0, 400);
    BuildPackTheme.popup(g, tx, ty, w, h);
    for (int i = 0; i < wrapped.size(); i++) {
      int color = i < titleLines ? BuildPackTheme.accentLight() : BuildPackTheme.textSecondary();
      g.drawString(font, wrapped.get(i), tx + pad, ty + pad + i * 10, color, false);
    }
    g.pose().popPose();
  }

  // ---- Input ----

  @Override
  public boolean mouseClicked(double mouseX, double mouseY, int button) {
    if (super.mouseClicked(mouseX, mouseY, button)) {
      return true;
    }
    if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
      // Later regions were drawn on top, so they win where regions overlap.
      List<Hit> snapshot = List.copyOf(hits);
      for (int i = snapshot.size() - 1; i >= 0; i--) {
        Hit hit = snapshot.get(i);
        if (hit.contains(mouseX, mouseY)) {
          hit.action().run();
          return true;
        }
      }
      // A click outside the panel closes it, like the context menu.
      if (!(mouseX >= px && mouseX < px + pw && mouseY >= py && mouseY < py + ph)) {
        onClose();
        return true;
      }
    }
    return false;
  }

  private boolean inside(int x, int y, int w, int h) {
    return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
  }

  private static String translate(String key) {
    return Component.translatable(key).getString();
  }
}
