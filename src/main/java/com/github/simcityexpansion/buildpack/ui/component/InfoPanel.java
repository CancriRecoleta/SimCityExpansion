package com.github.simcityexpansion.buildpack.ui.component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import com.github.simcityexpansion.buildpack.convert.NbtStructure;
import com.github.simcityexpansion.buildpack.convert.StructureAnalysis;
import com.github.simcityexpansion.buildpack.convert.StructureAnalysis.MaterialEntry;
import com.github.simcityexpansion.buildpack.model.BuildingMetadata;
import com.github.simcityexpansion.buildpack.model.ImportFile;
import com.github.simcityexpansion.buildpack.model.InstalledBuilding;
import com.github.simcityexpansion.buildpack.model.PackArchive;
import com.github.simcityexpansion.buildpack.model.StructureInfo;
import com.github.simcityexpansion.buildpack.ui.BuildPackTheme;
import com.github.simcityexpansion.buildpack.ui.MaterialListScreen;
import com.github.simcityexpansion.buildpack.ui.PackBuildingSelection;
import com.github.simcityexpansion.buildpack.ui.StructureEditorScreen;
import com.github.simcityexpansion.buildpack.ui.ThemedButton;
import com.github.simcityexpansion.buildpack.ui.UiFormats;
import com.github.simcityexpansion.buildpack.ui.preview.IsoPreview;
import com.github.simcityexpansion.buildpack.ui.preview.PreviewSlot;
import com.github.simcityexpansion.buildpack.ui.preview.StructurePreview;
import com.github.simcityexpansion.buildpack.ui.preview.StructureScene;
import com.github.simcityexpansion.buildpack.ui.preview.TopDownPreview;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

/**
 * 右侧定宽信息面板（对照 Litematica 的 WidgetSchematicBrowser 信息栏）：上部元数据行
 * （标签灰、值白），中部预览（{@link PreviewSlot} 托管 3D/等距/俯视/缩略图/占位），
 * 下部为 .sk 元数据表单（导入页签可编辑，其余只读）与材料清单/编辑器入口。
 *
 * <p>控件由宿主屏幕在 {@link #rebuild} 时创建并登记；信息行与表单标签文本由
 * {@link #renderText} 绘制。
 */
public final class InfoPanel {

  private static final int PAD = 4;
  private static final int INFO_ROW_H = 10;
  private static final int INFO_ROWS = 9;
  private static final int INFO_REGION_H = INFO_ROWS * INFO_ROW_H;
  private static final int PREVIEW_H = 110;
  private static final int BUTTON_H = 16;
  private static final int BUTTON_GAP = 2;

  private final MetadataForm form;

  private Font font;
  private int infoX;
  private int infoY;
  private int infoW;

  private PreviewSlot previewSlot;
  private ThemedButton materialButton;
  private ThemedButton editButton;

  private final List<Component> rows = new ArrayList<>();
  private AbstractWidget currentPreview;
  private String currentName = "";
  private NbtStructure currentStructure;
  private List<MaterialEntry> currentMaterials = List.of();

  public InfoPanel(MetadataForm form) {
    this.form = form;
    rows.add(Component.translatable("buildpack.detail.empty"));
  }

  /** 重建并放置面板控件（每次屏幕 init 调用一次）。 */
  public void rebuild(Font font, int x, int y, int width, int height, Consumer<AbstractWidget> add) {
    this.font = font;
    this.infoX = x + PAD;
    this.infoY = y + PAD;
    this.infoW = width - PAD * 2;

    int previewY = infoY + INFO_REGION_H + PAD;
    previewSlot = new PreviewSlot(infoX, previewY, infoW, PREVIEW_H);
    previewSlot.setChild(currentPreview);
    add.accept(previewSlot);

    int buttonsY = previewY + PREVIEW_H + PAD;
    materialButton = new ThemedButton(infoX, buttonsY, infoW, BUTTON_H,
        Component.translatable("buildpack.materials.open", 0),
        () -> MaterialListScreen.open(currentName, currentMaterials));
    materialButton.visible = !currentMaterials.isEmpty();
    if (!currentMaterials.isEmpty()) {
      materialButton.setMessage(
          Component.translatable("buildpack.materials.open", currentMaterials.size()));
    }
    add.accept(materialButton);

    int editY = buttonsY + BUTTON_H + BUTTON_GAP;
    editButton = new ThemedButton(infoX, editY, infoW, BUTTON_H,
        Component.translatable("buildpack.editor.open"),
        () -> {
          if (currentStructure != null) {
            StructureEditorScreen.open(currentStructure, currentName);
          }
        });
    editButton.visible = currentStructure != null;
    add.accept(editButton);

    int formY = editY + BUTTON_H + PAD;
    form.rebuild(font, infoX, formY, infoW, add);
  }

  /** 空态：未选中任何条目。 */
  public void showEmpty() {
    setRows(Component.translatable("buildpack.detail.empty"));
    clearExtras();
    form.setModel(new BuildingMetadata(), false);
  }

  /** 导入文件：结构摘要 + 文件信息 + 预览 + 可编辑表单 + 材料清单入口。 */
  public void showImport(
      ImportFile file, StructureInfo info, NbtStructure structure, BuildingMetadata model) {
    List<Component> list = new ArrayList<>(List.of(
        row("buildpack.info.name", info.name() == null ? "-" : info.name()),
        row("buildpack.info.author", info.author() == null ? "-" : info.author()),
        row("buildpack.info.size", info.sizeString()),
        Component.translatable("buildpack.info.blocks",
            white(UiFormats.integer(info.totalBlocks())),
            white(UiFormats.integer(info.totalVolume()))),
        row("buildpack.info.regions", info.regionCount()),
        row("buildpack.info.data_version", info.dataVersion())));
    if (info.timeCreated() > 0L) {
      list.add(row("buildpack.info.created", UiFormats.dateTime(info.timeCreated())));
    }
    list.add(row("buildpack.info.modified", UiFormats.dateTime(file.modifiedAt().toEpochMilli())));
    list.add(row("buildpack.info.file_size", UiFormats.fileSize(file.sizeBytes())));
    setRows(list.toArray(Component[]::new));

    showStructureExtras(model.name, info, structure);
    form.setModel(model, true);
  }

  /** 包内建筑（直接从 zip 读取）：摘要 + 预览 + 材料清单 + 元数据只读。 */
  public void showPackBuilding(PackBuildingSelection selection, StructureInfo info,
      NbtStructure structure, BuildingMetadata model) {
    List<Component> list = new ArrayList<>(List.of(
        row("buildpack.info.name", model.name.isBlank() ? selection.entry().name() : model.name),
        row("buildpack.info.author", model.author.isBlank() ? "-" : model.author),
        row("buildpack.info.size", info.sizeString()),
        Component.translatable("buildpack.info.blocks",
            white(UiFormats.integer(info.totalBlocks())),
            white(UiFormats.integer(info.totalVolume()))),
        row("buildpack.info.data_version", info.dataVersion()),
        Component.translatable("buildpack.info.category",
            selection.entry().category().displayName().copy().withStyle(ChatFormatting.WHITE)),
        row("buildpack.info.from_pack", selection.pack().manifest().name())));
    if (info.timeCreated() > 0L) {
      list.add(row("buildpack.info.created", UiFormats.dateTime(info.timeCreated())));
    }
    setRows(list.toArray(Component[]::new));

    showStructureExtras(model.name, info, structure);
    form.setModel(model, false);
  }

  /** zip 拓展包：清单摘要（表单只读展示包信息）。 */
  public void showPack(PackArchive pack, boolean installed) {
    setRows(
        row("buildpack.info.name", pack.manifest().name()),
        row("buildpack.info.author",
            pack.manifest().author().isBlank() ? "-" : pack.manifest().author()),
        row("buildpack.info.version",
            pack.manifest().version().isBlank() ? "-" : pack.manifest().version()),
        row("buildpack.info.buildings", pack.buildings().size()),
        installed
            ? Component.translatable("buildpack.info.pack_installed")
            : Component.translatable("buildpack.info.pack_not_installed"));
    clearExtras();

    BuildingMetadata meta = new BuildingMetadata();
    meta.name = pack.manifest().name();
    meta.author = pack.manifest().author();
    meta.description = pack.manifest().description();
    form.setModel(meta, false);
  }

  /** 已安装建筑：.sk 字段只读展示 +（若能解析到结构）预览与材料清单。 */
  public void showInstalled(InstalledBuilding building, StructureInfo info, NbtStructure structure) {
    setRows(
        row("buildpack.info.name", building.name()),
        row("buildpack.info.author", building.skFields().getOrDefault("author", "-")),
        row("buildpack.info.size", building.skFields().getOrDefault("size", "-")),
        Component.translatable("buildpack.info.category",
            building.category().displayName().copy().withStyle(ChatFormatting.WHITE)),
        building.packId() != null
            ? row("buildpack.info.from_pack", building.packId())
            : (building.managed()
                ? Component.translatable("buildpack.info.managed")
                : Component.translatable("buildpack.info.external")));
    if (structure != null) {
      showStructureExtras(building.name(), info, structure);
    } else {
      clearExtras();
    }

    BuildingMetadata meta = new BuildingMetadata();
    meta.name = building.name();
    meta.amount = building.skFields().getOrDefault("amount", "");
    meta.author = building.skFields().getOrDefault("author", "");
    meta.description = building.skFields().getOrDefault("description", "");
    meta.tags = building.skFields().getOrDefault("tags", "");
    meta.jobType = building.skFields().getOrDefault("job_type", "");
    meta.category = building.category();
    form.setModel(meta, false);
  }

  /** 预览（内嵌缩略图 → 真实方块 3D → 等距 → 俯视 → 占位）与材料/编辑入口。 */
  private void showStructureExtras(String name, StructureInfo info, NbtStructure structure) {
    currentName = name;
    currentStructure = structure;
    currentMaterials = StructureAnalysis.materials(structure);

    StructureScene scene = new StructureScene(0, 0, 0, 0, false);
    if (scene.setStructure(structure)) {
      currentPreview = scene;
    } else {
      AbstractWidget preview = StructurePreview.embedded(info);
      if (preview == null) {
        preview = IsoPreview.create(structure);
      }
      if (preview == null) {
        preview = TopDownPreview.create(structure);
      }
      currentPreview = preview != null ? preview : StructurePreview.placeholder();
    }
    if (previewSlot != null) {
      previewSlot.setChild(currentPreview);
    }
    if (materialButton != null) {
      materialButton.visible = !currentMaterials.isEmpty();
      materialButton.setMessage(
          Component.translatable("buildpack.materials.open", currentMaterials.size()));
    }
    if (editButton != null) {
      editButton.visible = true;
    }
  }

  private void clearExtras() {
    currentStructure = null;
    currentMaterials = List.of();
    currentPreview = null;
    if (previewSlot != null) {
      previewSlot.setChild(null);
    }
    if (materialButton != null) {
      materialButton.visible = false;
    }
    if (editButton != null) {
      editButton.visible = false;
    }
  }

  /** 绘制信息行与表单标签（控件本身由屏幕 widget 渲染绘出）。 */
  public void renderText(GuiGraphics g) {
    if (font == null) {
      return;
    }
    g.enableScissor(infoX, infoY, infoX + infoW, infoY + INFO_REGION_H);
    int rowY = infoY;
    for (Component row : rows) {
      g.drawString(font, row, infoX, rowY, BuildPackTheme.LABEL, true);
      rowY += INFO_ROW_H;
    }
    g.disableScissor();
    form.renderLabels(g);
  }

  private void setRows(Component... newRows) {
    rows.clear();
    rows.addAll(List.of(newRows));
  }

  private static Component row(String key, Object value) {
    return Component.translatable(key, white(value));
  }

  private static Component white(Object value) {
    return Component.literal(String.valueOf(value)).withStyle(ChatFormatting.WHITE);
  }
}
