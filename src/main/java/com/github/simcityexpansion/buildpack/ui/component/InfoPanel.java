package com.github.simcityexpansion.buildpack.ui.component;

import com.github.simcityexpansion.buildpack.BuildPack;
import com.github.simcityexpansion.buildpack.model.BuildingMetadata;
import com.github.simcityexpansion.buildpack.model.InstalledBuilding;
import com.github.simcityexpansion.buildpack.model.PackArchive;
import com.github.simcityexpansion.buildpack.model.StructureInfo;
import com.github.simcityexpansion.buildpack.ui.preview.StructurePreview;
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

    // 表单行数多，放入滚动区，避免小窗口下溢出。
    ScrollerView formScroller = new ScrollerView();
    formScroller.addClass(BuildPack.cls("form-scroller"));
    formScroller.layout(layout -> layout.widthStretch().flexGrow(1.0f));
    formScroller.addScrollViewChild(form.root());

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
    form.setModel(new BuildingMetadata(), false);
  }

  /** 导入文件：结构摘要 + 预览 + 可编辑表单。 */
  public void showImport(StructureInfo info, BuildingMetadata model) {
    setRows(
        row("buildpack.info.name", info.name() == null ? "-" : info.name()),
        row("buildpack.info.author", info.author() == null ? "-" : info.author()),
        row("buildpack.info.size", info.sizeString()),
        Component.translatable("buildpack.info.blocks",
            white(info.totalBlocks()), white(info.totalVolume())),
        row("buildpack.info.regions", info.regionCount()),
        row("buildpack.info.data_version", info.dataVersion()));
    previewArea.clearAllChildren();
    previewArea.addChild(StructurePreview.create(info));
    form.setModel(model, true);
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
