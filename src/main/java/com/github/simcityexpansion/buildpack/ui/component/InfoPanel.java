package com.github.simcityexpansion.buildpack.ui.component;

import java.util.ArrayList;
import java.util.List;

import com.github.simcityexpansion.buildpack.BuildPack;
import com.github.simcityexpansion.buildpack.convert.NbtStructure;
import com.github.simcityexpansion.buildpack.convert.StructureAnalysis;
import com.github.simcityexpansion.buildpack.model.BuildingMetadata;
import com.github.simcityexpansion.buildpack.model.ImportFile;
import com.github.simcityexpansion.buildpack.model.InstalledBuilding;
import com.github.simcityexpansion.buildpack.model.PackArchive;
import com.github.simcityexpansion.buildpack.model.StructureInfo;
import com.github.simcityexpansion.buildpack.ui.PackBuildingSelection;
import com.github.simcityexpansion.buildpack.ui.UiFormats;
import com.github.simcityexpansion.buildpack.ui.preview.StructurePreview;
import com.github.simcityexpansion.buildpack.ui.preview.TopDownPreview;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView;
import dev.vfyjxf.taffy.style.AlignContent;
import dev.vfyjxf.taffy.style.AlignItems;
import dev.vfyjxf.taffy.style.FlexDirection;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

/**
 * 右侧定宽信息面板，结构与配色完全对照 Litematica 的 WidgetSchematicBrowser 信息栏：
 * 上部元数据行（标签灰 0xC0C0C0、值白）、中部预览缩略图（描边方框），
 * 下部为本模组扩展的 .sk 元数据表单（导入页签可编辑，其余只读）。
 */
public final class InfoPanel {

  private final UIElement root;
  private final UIElement infoArea;
  private final UIElement previewArea;
  private final UIElement extrasArea;
  private final MetadataForm form;

  public InfoPanel(MetadataForm form) {
    this.form = form;

    root = new UIElement();
    root.addClass(BuildPack.cls("detail"));
    root.layout(layout -> layout.flexDirection(FlexDirection.COLUMN)
        .gapRow(4.0f).paddingAll(4.0f));

    infoArea = new UIElement();
    infoArea.addClass(BuildPack.cls("info"));
    infoArea.layout(layout -> layout.flexDirection(FlexDirection.COLUMN)
        .gapRow(3.0f).widthStretch());

    previewArea = new UIElement();
    previewArea.addClass(BuildPack.cls("preview"));
    previewArea.layout(layout -> layout.flexDirection(FlexDirection.COLUMN)
        .alignItems(AlignItems.CENTER).justifyContent(AlignContent.CENTER)
        .paddingAll(3.0f).widthStretch().height(98.0f));

    // 表单 + 材料清单行数多，放入滚动区，避免小窗口下溢出。
    extrasArea = new UIElement();
    extrasArea.addClass(BuildPack.cls("extras"));
    extrasArea.layout(layout -> layout.flexDirection(FlexDirection.COLUMN)
        .gapRow(4.0f).widthStretch());

    UIElement scrollContent = new UIElement();
    scrollContent.layout(layout -> layout.flexDirection(FlexDirection.COLUMN)
        .gapRow(4.0f).widthStretch());
    scrollContent.addChildren(form.root(), extrasArea);

    ScrollerView formScroller = new ScrollerView();
    formScroller.addClass(BuildPack.cls("form-scroller"));
    formScroller.layout(layout -> layout.widthStretch().flexGrow(1.0f));
    formScroller.addScrollViewChild(scrollContent);

    root.addChildren(infoArea, previewArea, formScroller);
    showEmpty();
  }

  /** 返回面板根元素。 */
  public UIElement root() {
    return root;
  }

  /** 空态：未选中任何条目。 */
  public void showEmpty() {
    setRows(Component.translatable("buildpack.detail.empty"));
    previewArea.clearAllChildren();
    extrasArea.clearAllChildren();
    form.setModel(new BuildingMetadata(), false);
  }

  /** 导入文件：结构摘要 + 文件信息 + 预览（内嵌图或俯视图）+ 可编辑表单 + 材料清单。 */
  public void showImport(
      ImportFile file, StructureInfo info, NbtStructure structure, BuildingMetadata model) {
    List<Component> rows = new ArrayList<>(List.of(
        row("buildpack.info.name", info.name() == null ? "-" : info.name()),
        row("buildpack.info.author", info.author() == null ? "-" : info.author()),
        row("buildpack.info.size", info.sizeString()),
        Component.translatable("buildpack.info.blocks",
            white(UiFormats.integer(info.totalBlocks())),
            white(UiFormats.integer(info.totalVolume()))),
        row("buildpack.info.regions", info.regionCount()),
        row("buildpack.info.data_version", info.dataVersion())));
    // Litematica 信息面板同款时间行：创建时间来自 litematic 元数据，修改时间来自文件。
    if (info.timeCreated() > 0L) {
      rows.add(row("buildpack.info.created", UiFormats.dateTime(info.timeCreated())));
    }
    rows.add(row("buildpack.info.modified",
        UiFormats.dateTime(file.modifiedAt().toEpochMilli())));
    rows.add(row("buildpack.info.file_size", UiFormats.fileSize(file.sizeBytes())));
    setRows(rows.toArray(Component[]::new));

    showStructureExtras(info, structure);
    form.setModel(model, true);
  }

  /** 包内建筑（直接从 zip 读取）：摘要 + 预览 + 材料清单 + 元数据只读。 */
  public void showPackBuilding(PackBuildingSelection selection, StructureInfo info,
      NbtStructure structure, BuildingMetadata model) {
    List<Component> rows = new ArrayList<>(List.of(
        row("buildpack.info.name",
            model.name.isBlank() ? selection.entry().name() : model.name),
        row("buildpack.info.author", model.author.isBlank() ? "-" : model.author),
        row("buildpack.info.size", info.sizeString()),
        Component.translatable("buildpack.info.blocks",
            white(UiFormats.integer(info.totalBlocks())),
            white(UiFormats.integer(info.totalVolume()))),
        row("buildpack.info.data_version", info.dataVersion()),
        Component.translatable("buildpack.info.category",
            selection.entry().category().displayName().copy()
                .withStyle(ChatFormatting.WHITE)),
        row("buildpack.info.from_pack", selection.pack().manifest().name())));
    if (info.timeCreated() > 0L) {
      rows.add(row("buildpack.info.created", UiFormats.dateTime(info.timeCreated())));
    }
    setRows(rows.toArray(Component[]::new));

    showStructureExtras(info, structure);
    form.setModel(model, false);
  }

  /** 预览（内嵌缩略图 → 俯视图 → 占位）与材料清单。 */
  private void showStructureExtras(StructureInfo info, NbtStructure structure) {
    previewArea.clearAllChildren();
    UIElement preview = StructurePreview.embedded(info);
    if (preview == null) {
      preview = TopDownPreview.create(structure);
    }
    previewArea.addChild(preview != null ? preview : StructurePreview.placeholder());

    extrasArea.clearAllChildren();
    UIElement materials = MaterialList.build(StructureAnalysis.materials(structure));
    if (materials != null) {
      extrasArea.addChild(materials);
    }
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
    previewArea.clearAllChildren();
    extrasArea.clearAllChildren();

    BuildingMetadata meta = new BuildingMetadata();
    meta.name = pack.manifest().name();
    meta.author = pack.manifest().author();
    meta.description = pack.manifest().description();
    form.setModel(meta, false);
  }

  /** 已安装建筑：.sk 字段只读展示。 */
  public void showInstalled(InstalledBuilding building) {
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
    previewArea.clearAllChildren();
    extrasArea.clearAllChildren();

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

  /** 信息行：标签部分用面板灰（样式表），参数值统一白色（Litematica 的标签/值双色）。 */
  private static Component row(String key, Object value) {
    return Component.translatable(key, white(value));
  }

  private static Component white(Object value) {
    return Component.literal(String.valueOf(value)).withStyle(ChatFormatting.WHITE);
  }

  private void setRows(Component... rows) {
    infoArea.clearAllChildren();
    for (Component row : rows) {
      Label label = new Label();
      label.addClass(BuildPack.cls("info-row"));
      label.setValue(row);
      infoArea.addChild(label);
    }
  }
}
