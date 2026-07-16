package com.github.simcityexpansion.buildpack.ui;

import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/**
 * Scrollable step-by-step guide for making an expansion pack: prepare structures, install into
 * SimuKraft, write the commerce/industry definition, validate and test, export the pack, and
 * distribute it. Content lives in the language files (six title/body pairs), so translations stay
 * in one place; the screen only lays the sections out with wrapping and mouse-wheel scrolling.
 */
public final class PackGuideScreen extends Screen {

  private static final int STEPS = 6;
  private static final int MARGIN = 16;
  private static final int PAD = 8;
  private static final int TITLE_GAP = 3;
  private static final int SECTION_GAP = 9;
  private static final int BUTTON_H = 18;

  private final Screen parent;
  private double scroll;
  private int contentHeight;

  private PackGuideScreen(Screen parent) {
    super(Component.translatable("buildpack.guide.title"));
    this.parent = parent;
  }

  /** Opens the guide on top of the current screen. */
  public static void open() {
    Minecraft mc = Minecraft.getInstance();
    mc.setScreen(new PackGuideScreen(mc.screen));
  }

  @Override
  protected void init() {
    addRenderableWidget(new ThemedButton((width - 100) / 2, height - MARGIN - BUTTON_H, 100,
        BUTTON_H, Component.translatable("buildpack.action.back"), this::onClose));
  }

  private int areaTop() {
    return MARGIN + 16;
  }

  private int areaBottom() {
    return height - MARGIN - BUTTON_H - 6;
  }

  @Override
  public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
    g.fill(0, 0, width, height, 0xF2101010);
  }

  @Override
  public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
    BuildPackTheme.panel(g, MARGIN, areaTop(), width - MARGIN * 2, areaBottom() - areaTop());
    super.render(g, mouseX, mouseY, partialTick);
    g.drawString(font, title, MARGIN, MARGIN - 6, BuildPackTheme.TITLE, true);

    int textX = MARGIN + PAD;
    int textW = width - (MARGIN + PAD) * 2;
    int y = areaTop() + PAD - (int) scroll;
    g.enableScissor(MARGIN + 1, areaTop() + 1, width - MARGIN - 1, areaBottom() - 1);
    for (int step = 1; step <= STEPS; step++) {
      g.drawString(font, Component.translatable("buildpack.guide.s" + step + ".title"),
          textX, y, BuildPackTheme.ACCENT, true);
      y += font.lineHeight + TITLE_GAP;
      List<FormattedCharSequence> lines =
          font.split(Component.translatable("buildpack.guide.s" + step + ".body"), textW);
      for (FormattedCharSequence line : lines) {
        g.drawString(font, line, textX, y, BuildPackTheme.LABEL, true);
        y += font.lineHeight + 1;
      }
      y += SECTION_GAP;
    }
    g.disableScissor();
    contentHeight = y + (int) scroll - (areaTop() + PAD);

    if (maxScroll() > 0) {
      // Slim scrollbar on the panel's right edge.
      int track = areaBottom() - areaTop() - 2;
      int thumb = Math.max(12, track * track / Math.max(track, contentHeight));
      int thumbY = areaTop() + 1 + (int) ((track - thumb) * (scroll / maxScroll()));
      g.fill(width - MARGIN - 3, areaTop() + 1, width - MARGIN - 1, areaBottom() - 1, 0x30FFFFFF);
      g.fill(width - MARGIN - 3, thumbY, width - MARGIN - 1, thumbY + thumb, 0xA0FFFFFF);
    }
  }

  private int maxScroll() {
    return Math.max(0, contentHeight - (areaBottom() - areaTop() - PAD * 2));
  }

  @Override
  public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
    scroll = Math.max(0, Math.min(maxScroll(), scroll - scrollY * font.lineHeight * 2));
    return true;
  }

  @Override
  public void onClose() {
    minecraft.setScreen(parent);
  }
}
