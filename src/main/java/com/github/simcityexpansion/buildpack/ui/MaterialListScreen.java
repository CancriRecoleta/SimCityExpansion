package com.github.simcityexpansion.buildpack.ui;

import java.util.List;

import com.github.simcityexpansion.buildpack.convert.StructureAnalysis.MaterialEntry;
import com.github.simcityexpansion.buildpack.ui.component.MaterialListView;
import com.github.simcityexpansion.buildpack.ui.style.Shapes;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Full-screen material list for one building: the building name with summary pills (total blocks,
 * block types, missing types), a filter box, the sortable {@link MaterialListView} table, and a
 * button that copies the (filtered) list to the clipboard. "Back" returns to the opening screen.
 */
public final class MaterialListScreen extends Screen {

  private static final int PAD = 10;
  private static final int TITLE_H = 16;
  private static final int GAP = 4;
  private static final int BUTTON_H = 20;
  private static final int FILTER_W = 150;
  private static final int FILTER_H = 14;

  private final Screen previous;
  private final String buildingName;
  private final List<MaterialEntry> materials;
  private MaterialListView list;
  private String filterText = "";
  private Component status = Component.empty();

  private MaterialListScreen(Screen previous, String buildingName, List<MaterialEntry> materials) {
    super(Component.translatable("buildpack.materials.screen_title"));
    this.previous = previous;
    this.buildingName = buildingName;
    this.materials = materials;
  }

  /** Opens the material list screen (returns to the screen that opened it on close). */
  public static void open(String buildingName, List<MaterialEntry> materials) {
    Minecraft mc = Minecraft.getInstance();
    mc.setScreen(new MaterialListScreen(mc.screen, buildingName, materials));
  }

  private int boxY() {
    return PAD + TITLE_H + GAP;
  }

  private int boxHeight() {
    return height - PAD - BUTTON_H - GAP - boxY();
  }

  @Override
  protected void init() {
    list = new MaterialListView(PAD + 3, boxY() + 3, width - PAD * 2 - 6, boxHeight() - 6);
    list.setMaterials(materials);
    list.setFilter(filterText);
    addRenderableWidget(list);

    ThemedEditBox filter = new ThemedEditBox(font, width - PAD - FILTER_W, PAD - 1, FILTER_W,
        FILTER_H, Component.translatable("buildpack.materials.filter"));
    filter.setHint(Component.translatable("buildpack.materials.filter"));
    filter.setMaxLength(64);
    filter.setValue(filterText);
    filter.setResponder(text -> {
      filterText = text;
      list.setFilter(text);
    });
    addRenderableWidget(filter);

    int buttonY = height - PAD - BUTTON_H;
    addRenderableWidget(new ThemedButton(PAD, buttonY, 80, BUTTON_H,
        Component.translatable("buildpack.action.back"), this::onClose));
    ThemedButton copy = new ThemedButton(PAD + 80 + GAP, buttonY, 90, BUTTON_H,
        Component.translatable("buildpack.materials.copy"), this::copyList);
    copy.setTooltip(Tooltip.create(Component.translatable("buildpack.materials.copy.tooltip")));
    addRenderableWidget(copy);
  }

  private void copyList() {
    List<String> lines = list.exportLines();
    StringBuilder text = new StringBuilder(Component.translatable(
        "buildpack.materials.copy_header", buildingName, UiFormats.integer(list.totalCount()),
        list.typeCount()).getString());
    for (String line : lines) {
      text.append('\n').append(line);
    }
    minecraft.keyboardHandler.setClipboard(text.toString());
    status = Component.translatable("buildpack.materials.copied", lines.size());
  }

  @Override
  public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
    // Override vanilla: skip the Gaussian-blur background.
  }

  @Override
  public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
    BuildPackTheme.screenSurface(g, width, height);
    BuildPackTheme.panel(g, PAD, boxY(), width - PAD * 2, boxHeight());

    int textY = PAD + 2;
    int limit = width - PAD - FILTER_W - 8;
    Component heading = Component.literal(buildingName).withStyle(ChatFormatting.BOLD);
    String headingText = heading.getString();
    int x = PAD + 4;
    if (font.width(heading) > limit - x) {
      headingText = font.plainSubstrByWidth(headingText, Math.max(0, limit - x - 12)) + "…";
      heading = Component.literal(headingText).withStyle(ChatFormatting.BOLD);
    }
    g.drawString(font, heading, x, textY, BuildPackTheme.title(), false);
    x += font.width(heading) + 8;
    x = pill(g, x, textY, limit, Component.translatable("buildpack.materials.pill.blocks",
        UiFormats.integer(list.totalCount())).getString(), BuildPackTheme.accentLight());
    x = pill(g, x, textY, limit, Component.translatable("buildpack.materials.pill.types",
        list.typeCount()).getString(), BuildPackTheme.textSecondary());
    if (list.missingCount() > 0) {
      pill(g, x, textY, limit, Component.translatable("buildpack.materials.pill.missing",
          list.missingCount()).getString(), BuildPackTheme.messageError());
    }

    if (!status.getString().isEmpty()) {
      int buttonY = height - PAD - BUTTON_H;
      g.drawString(font, status, PAD + 80 + GAP + 90 + 8,
          buttonY + (BUTTON_H - font.lineHeight) / 2 + 1, BuildPackTheme.messageOk(), false);
    }
    super.render(g, mouseX, mouseY, partialTick);
  }

  /** Tinted Krypton-style pill; returns the x after it, or {@code x} if it would pass {@code limit}. */
  private int pill(GuiGraphics g, int x, int textY, int limit, String text, int color) {
    int w = font.width(text) + 8;
    if (x + w > limit) {
      return x;
    }
    Shapes.pill(g, x, textY - 2, w, 12, BuildPackTheme.withAlpha(color, 0x30));
    g.drawString(font, text, x + 4, textY, color, false);
    return x + w + 4;
  }

  @Override
  public void onClose() {
    if (previous != null) {
      minecraft.setScreen(previous);
    } else {
      BuildPackScreen.open();
    }
  }
}
