package com.github.simcityexpansion.buildpack.ui;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import com.github.simcityexpansion.buildpack.BuildPack;
import com.github.simcityexpansion.buildpack.I18nLog;
import com.github.simcityexpansion.buildpack.LocalizedIOException;
import com.github.simcityexpansion.buildpack.convert.NbtStructure;
import com.github.simcityexpansion.buildpack.convert.StructureNbtReader;
import com.github.simcityexpansion.buildpack.install.PackExporter;
import com.github.simcityexpansion.buildpack.install.PackReader;
import com.github.simcityexpansion.buildpack.model.InstalledBuilding;
import com.github.simcityexpansion.buildpack.ui.preview.PackIcon;
import com.github.simcityexpansion.buildpack.ui.preview.PreviewSlot;
import com.github.simcityexpansion.buildpack.ui.preview.StructurePreview;
import net.minecraft.Util;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Export build pack dialog: edit pack name, version, author, and description; choose whether to
 * include .sk files and SimuKraft job/trade JSON; optionally bundle a pack icon (a custom PNG path,
 * or auto-generated from a building preview when left blank); then export the supplied buildings
 * (those checked in the UI or matching the current filter) to a zip file.
 */
public final class ExportScreen extends Screen {

  private static final Logger LOGGER = LoggerFactory.getLogger(ExportScreen.class);
  private static final int W = 260;
  private static final int H = 234;
  private static final int LABEL_W = 56;
  private static final int ICON_BOX = 44;
  // Y offsets (from top()) of the checkbox / icon rows, shared by init() and render().
  private static final int CHECK_SK = 116;
  private static final int CHECK_JSON = 132;
  private static final int CHECK_ICON = 148;
  private static final int ICON_ROW = 166;
  private static final int ICON_BOX_Y = 146;

  private static final byte[] NO_ICON = new byte[0];

  private final Screen parent;
  private final List<InstalledBuilding> buildings;
  private EditBox nameField;
  private EditBox versionField;
  private EditBox authorField;
  private EditBox descField;
  private Checkbox includeSk;
  private Checkbox includeJson;
  private Checkbox includeIcon;
  private EditBox iconPathField;
  private PreviewSlot iconSlot;
  private @Nullable byte[] autoIconCache;
  private Component status = Component.empty();
  private boolean exporting;

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

  private int iconBoxX() {
    return left() + W - 10 - ICON_BOX;
  }

  @Override
  protected void init() {
    int x = left() + 10 + LABEL_W;
    int fw = W - 20 - LABEL_W;
    nameField = field(x, top() + 26, fw, "");
    versionField = field(x, top() + 48, fw, "1.0.0");
    authorField = field(x, top() + 70, fw, defaultAuthor());
    descField = field(x, top() + 92, fw, "");

    includeSk = Checkbox.builder(Component.translatable("buildpack.export.include_sk"), font)
        .pos(left() + 10, top() + CHECK_SK).selected(true).build();
    addRenderableWidget(includeSk);
    includeJson = Checkbox.builder(Component.translatable("buildpack.export.include_json"), font)
        .pos(left() + 10, top() + CHECK_JSON).selected(true).build();
    addRenderableWidget(includeJson);
    includeIcon = Checkbox.builder(Component.translatable("buildpack.export.include_icon"), font)
        .pos(left() + 10, top() + CHECK_ICON).selected(true)
        .onValueChange((checkbox, value) -> updateIcon()).build();
    addRenderableWidget(includeIcon);

    int boxX = iconBoxX();
    iconPathField = new EditBox(font, x, top() + ICON_ROW, boxX - 6 - x, 18, Component.empty());
    iconPathField.setMaxLength(512);
    iconPathField.setHint(Component.translatable("buildpack.export.icon_hint"));
    iconPathField.setResponder(value -> updateIcon());
    addRenderableWidget(iconPathField);

    iconSlot = new PreviewSlot(boxX, top() + ICON_BOX_Y, ICON_BOX, ICON_BOX);
    addRenderableWidget(iconSlot);

    int by = top() + H - 26;
    int bw = (W - 30) / 2;
    addRenderableWidget(new ThemedButton(left() + 10, by, bw, 18,
        Component.translatable("buildpack.export.do"), this::doExport));
    addRenderableWidget(new ThemedButton(left() + 20 + bw, by, bw, 18,
        Component.translatable("buildpack.prompt.cancel"), this::onClose));

    updateIcon();
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

  /** Refreshes the icon preview from the current checkbox / path state (also drives export). */
  private void updateIcon() {
    boolean on = includeIcon.selected();
    iconPathField.setEditable(on);
    iconSlot.setChild(on ? StructurePreview.fromPng(resolveIconBytes()) : null);
  }

  /** Resolves the icon bytes: the custom PNG path if given, otherwise an auto-generated preview. */
  @Nullable
  private byte[] resolveIconBytes() {
    String path = iconPathField.getValue().trim();
    return path.isEmpty() ? autoIcon() : readIconFile(path);
  }

  @Nullable
  private byte[] readIconFile(String path) {
    try {
      Path file = Path.of(path);
      if (Files.isRegularFile(file)) {
        return Files.readAllBytes(file);
      }
    } catch (IOException | RuntimeException e) {
      I18nLog.warn(LOGGER, e, "buildpack.log.icon_failed");
    }
    return null;
  }

  /** Renders the first building that has a structure into a PNG icon; result cached across calls. */
  @Nullable
  private byte[] autoIcon() {
    if (autoIconCache != null) {
      return autoIconCache.length == 0 ? null : autoIconCache;
    }
    for (InstalledBuilding building : buildings) {
      if (building.structureEntry() == null) {
        continue;
      }
      try {
        byte[] bytes = PackReader.readEntryBytes(building.zipPath(), building.structureEntry());
        NbtStructure structure = StructureNbtReader.read(NbtIo.readCompressed(
            new ByteArrayInputStream(bytes), NbtAccounter.create(BuildPack.MAX_STRUCTURE_NBT_BYTES)));
        byte[] png = PackIcon.render(structure);
        if (png != null) {
          autoIconCache = png;
          return png;
        }
      } catch (IOException | RuntimeException e) {
        I18nLog.warn(LOGGER, e, "buildpack.log.icon_failed");
      }
    }
    autoIconCache = NO_ICON;
    return null;
  }

  private void doExport() {
    if (exporting) {
      return;
    }
    PackExporter.ExportOptions options = new PackExporter.ExportOptions(
        nameField.getValue().trim(), versionField.getValue().trim(),
        authorField.getValue().trim(), descField.getValue().trim(),
        includeSk.selected(), includeJson.selected());
    // Resolve the icon on the main thread (auto-rendering a preview touches client/GPU state);
    // only the heavy structure reads and zip writing run off-thread.
    byte[] icon = includeIcon.selected() ? resolveIconBytes() : null;
    exporting = true;
    status = Component.translatable("buildpack.status.exporting");
    CompletableFuture.supplyAsync(() -> {
      try {
        return PackExporter.export(buildings, options, icon);
      } catch (IOException e) {
        throw new CompletionException(e);
      }
    }).whenComplete((path, error) -> Minecraft.getInstance().execute(() -> {
      exporting = false;
      if (Minecraft.getInstance().screen != this) {
        return;
      }
      if (error != null) {
        Throwable cause = error.getCause() != null ? error.getCause() : error;
        I18nLog.warn(LOGGER, cause, "buildpack.log.export_failed");
        status = Component.translatable(
            "buildpack.msg.parse_failed", LocalizedIOException.messageOf(cause));
      } else {
        Util.getPlatform().openPath(PackExporter.exportDir());
        minecraft.setScreen(parent);
      }
    }));
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
    label(g, "buildpack.export.field.name", top() + 26);
    label(g, "buildpack.export.field.version", top() + 48);
    label(g, "buildpack.export.field.author", top() + 70);
    label(g, "buildpack.export.field.description", top() + 92);
    label(g, "buildpack.export.field.icon", top() + ICON_ROW);

    // Icon preview frame, drawn behind the slot widget.
    int boxX = iconBoxX();
    int boxY = top() + ICON_BOX_Y;
    g.fill(boxX - 1, boxY - 1, boxX + ICON_BOX + 1, boxY + ICON_BOX + 1, 0xFF000000);
    g.fill(boxX, boxY, boxX + ICON_BOX, boxY + ICON_BOX, 0xFF1A1A1A);

    if (!status.getString().isEmpty()) {
      g.drawString(font, status, left() + 10, top() + H - 40, BuildPackTheme.MESSAGE_ERROR, true);
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
