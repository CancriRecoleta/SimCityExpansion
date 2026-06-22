package com.github.simcityexpansion.buildpack.ui;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import com.github.simcityexpansion.buildpack.I18nLog;
import com.github.simcityexpansion.buildpack.LocalizedIOException;
import com.github.simcityexpansion.buildpack.install.PackExporter;
import com.github.simcityexpansion.buildpack.model.InstalledBuilding;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Export build pack dialog: edit pack name, version, author, and description; choose whether to
 * include .sk files and SimuKraft job/trade JSON; then export the supplied buildings (those
 * checked in the UI or matching the current filter) to a zip file.
 */
public final class ExportScreen extends Screen {

  private static final Logger LOGGER = LoggerFactory.getLogger(ExportScreen.class);
  private static final int W = 260;
  private static final int H = 176;
  private static final int LABEL_W = 56;

  private final Screen parent;
  private final List<InstalledBuilding> buildings;
  private EditBox nameField;
  private EditBox versionField;
  private EditBox authorField;
  private EditBox descField;
  private Checkbox includeSk;
  private Checkbox includeJson;
  private Component status = Component.empty();

  private ExportScreen(Screen parent, List<InstalledBuilding> buildings) {
    super(Component.translatable("buildpack.export.title"));
    this.parent = parent;
    this.buildings = buildings;
  }

  /** Opens the export dialog. */
  public static void open(List<InstalledBuilding> buildings) {
    Minecraft mc = Minecraft.getInstance();
    mc.setScreen(new ExportScreen(mc.screen, buildings));
  }

  private int left() {
    return (width - W) / 2;
  }

  private int top() {
    return (height - H) / 2;
  }

  @Override
  protected void init() {
    int x = left() + 10 + LABEL_W;
    int fw = W - 20 - LABEL_W;
    int y = top() + 26;
    nameField = field(x, y, fw, "");
    y += 22;
    versionField = field(x, y, fw, "1.0.0");
    y += 22;
    authorField = field(x, y, fw, defaultAuthor());
    y += 22;
    descField = field(x, y, fw, "");
    y += 24;

    includeSk = Checkbox.builder(Component.translatable("buildpack.export.include_sk"), font)
        .pos(left() + 10, y).selected(true).build();
    addRenderableWidget(includeSk);
    y += 16;
    includeJson = Checkbox.builder(Component.translatable("buildpack.export.include_json"), font)
        .pos(left() + 10, y).selected(true).build();
    addRenderableWidget(includeJson);

    int by = top() + H - 26;
    int bw = (W - 30) / 2;
    addRenderableWidget(new ThemedButton(left() + 10, by, bw, 18,
        Component.translatable("buildpack.export.do"), this::doExport));
    addRenderableWidget(new ThemedButton(left() + 20 + bw, by, bw, 18,
        Component.translatable("buildpack.prompt.cancel"), this::onClose));
  }

  private EditBox field(int x, int y, int w, String initial) {
    EditBox box = new EditBox(font, x, y, w, 18, Component.empty());
    box.setMaxLength(256);
    box.setValue(initial);
    addRenderableWidget(box);
    return box;
  }

  private static String defaultAuthor() {
    Minecraft mc = Minecraft.getInstance();
    return mc.player != null ? mc.player.getGameProfile().getName() : "";
  }

  private void doExport() {
    PackExporter.ExportOptions options = new PackExporter.ExportOptions(
        nameField.getValue().trim(), versionField.getValue().trim(),
        authorField.getValue().trim(), descField.getValue().trim(),
        includeSk.selected(), includeJson.selected());
    try {
      PackExporter.export(buildings, options);
      Util.getPlatform().openPath(PackExporter.exportDir());
      minecraft.setScreen(parent);
    } catch (IOException | RuntimeException e) {
      I18nLog.warn(LOGGER, e, "buildpack.log.export_failed");
      status = Component.translatable(
          "buildpack.msg.parse_failed", LocalizedIOException.messageOf(e));
    }
  }

  @Override
  public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
    // No Gaussian-blur background.
  }

  @Override
  public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
    g.fill(0, 0, width, height, 0xC0000000);
    BuildPackTheme.fillPanel(g, left(), top(), W, H, 0xF0101010);
    g.drawString(font, Component.translatable("buildpack.export.heading", buildings.size()),
        left() + 10, top() + 10, BuildPackTheme.TITLE, true);
    int y = top() + 26;
    label(g, "buildpack.export.field.name", y);
    y += 22;
    label(g, "buildpack.export.field.version", y);
    y += 22;
    label(g, "buildpack.export.field.author", y);
    y += 22;
    label(g, "buildpack.export.field.description", y);
    if (!status.getString().isEmpty()) {
      g.drawString(font, status, left() + 10, top() + H - 42, BuildPackTheme.MESSAGE_ERROR, true);
    }
    super.render(g, mouseX, mouseY, partialTick);
  }

  private void label(GuiGraphics g, String key, int y) {
    g.drawString(font, Component.translatable(key), left() + 10,
        y + (18 - font.lineHeight) / 2, BuildPackTheme.LABEL, true);
  }

  @Override
  public void onClose() {
    minecraft.setScreen(parent);
  }
}
