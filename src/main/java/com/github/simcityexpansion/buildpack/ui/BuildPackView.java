package com.github.simcityexpansion.buildpack.ui;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.github.simcityexpansion.buildpack.BuildPack;
import com.github.simcityexpansion.buildpack.LocalizedIOException;
import com.github.simcityexpansion.buildpack.convert.LitematicReader;
import com.github.simcityexpansion.buildpack.convert.StructureNbtReader;
import com.github.simcityexpansion.buildpack.install.BuildingInstaller;
import com.github.simcityexpansion.buildpack.install.InstallRegistry;
import com.github.simcityexpansion.buildpack.install.PackInstaller;
import com.github.simcityexpansion.buildpack.install.PackReader;
import com.github.simcityexpansion.buildpack.model.BuildingMetadata;
import com.github.simcityexpansion.buildpack.model.ImportFile;
import com.github.simcityexpansion.buildpack.model.ImportScanner;
import com.github.simcityexpansion.buildpack.model.InstalledBuilding;
import com.github.simcityexpansion.buildpack.model.InstalledScanner;
import com.github.simcityexpansion.buildpack.model.PackArchive;
import com.github.simcityexpansion.buildpack.model.StructureInfo;
import com.github.simcityexpansion.buildpack.ui.component.BottomBar;
import com.github.simcityexpansion.buildpack.ui.component.FileListPanel;
import com.github.simcityexpansion.buildpack.ui.component.InfoPanel;
import com.github.simcityexpansion.buildpack.ui.component.MetadataForm;
import com.github.simcityexpansion.buildpack.ui.component.SearchBar;
import com.github.simcityexpansion.buildpack.ui.component.SourceTabs;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView;
import dev.vfyjxf.taffy.style.FlexDirection;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 建筑拓展包管理器视图（控制器）：组装整套 DOM，持有运行时状态
 * （三种来源的数据、当前页签、搜索词、选中项、表单模型），并为各子组件提供回调入口。
 *
 * <p>布局（参考 Litematica 原理图浏览器）：顶部工具栏 → 中部左右分栏
 * （左：页签 + 搜索 + 文件树；右：信息/预览/元数据表单）→ 底部操作与状态栏。
 */
public final class BuildPackView {

  private static final Logger LOGGER = LoggerFactory.getLogger(BuildPackView.class);

  private final UIElement root;
  private final FileListPanel listPanel;
  private final MetadataForm form;
  private final InfoPanel infoPanel;
  private final BottomBar bottomBar;
  private final InstallRegistry registry;

  private SourceTab currentTab = SourceTab.IMPORT;
  private String searchText = "";
  private List<ImportFile> importFiles = List.of();
  private List<PackArchive> packs = List.of();
  private List<String> invalidZips = List.of();
  private List<InstalledBuilding> installed = List.of();
  private Object selected;

  public BuildPackView() {
    registry = InstallRegistry.load();
    form = new MetadataForm();
    infoPanel = new InfoPanel(form);
    listPanel = new FileListPanel(this::onNodeSelected);
    bottomBar = new BottomBar(this);
    root = buildRoot();
    refresh();

    // 连接远程服务器时给出提醒：文件只会写到本机，专用服务器需要管理员在服务端安装。
    Minecraft minecraft = Minecraft.getInstance();
    if (minecraft.level != null && minecraft.getSingleplayerServer() == null) {
      bottomBar.setMessage(Component.translatable("buildpack.status.remote_warning"), true);
    }
  }

  /** 返回视图根元素。 */
  public UIElement root() {
    return root;
  }

  /** 页签切换回调。 */
  public void onTabChanged(SourceTab tab) {
    currentTab = tab;
    clearSelection();
    rebuildTree();
  }

  /** 搜索框文本变化回调。 */
  public void onSearchText(String text) {
    searchText = text == null ? "" : text.toLowerCase(Locale.ROOT).trim();
    rebuildTree();
  }

  /** 重新扫描三种来源并刷新列表。 */
  public void refresh() {
    importFiles = ImportScanner.scan();

    List<PackArchive> parsed = new ArrayList<>();
    List<String> invalid = new ArrayList<>();
    for (Path zip : ImportScanner.scanZips()) {
      try {
        parsed.add(PackReader.read(zip));
      } catch (IOException | RuntimeException e) {
        LOGGER.warn("BuildPack: 拓展包无效 {}", zip, e);
        invalid.add(zip.getFileName().toString());
      }
    }
    packs = parsed;
    invalidZips = invalid;

    installed = InstalledScanner.scan(registry);
    clearSelection();
    rebuildTree();

    if (!invalidZips.isEmpty()) {
      bottomBar.setMessage(Component.translatable(
          "buildpack.msg.invalid_pack", String.join(", ", invalidZips)), true);
    }
  }

  /** 用系统文件管理器打开导入目录。 */
  public void openImportFolder() {
    Util.getPlatform().openPath(ImportScanner.ensureImportDir());
  }

  /** 关闭界面。 */
  public void close() {
    Minecraft.getInstance().setScreen(null);
  }

  /** 列表选中回调（分支或清空选择时为 {@code null}）。 */
  private void onNodeSelected(Object content) {
    selected = content;
    if (content instanceof ImportFile file) {
      showImportFile(file);
    } else if (content instanceof PackArchive pack) {
      infoPanel.showPack(pack, registry.find(pack.manifest().id()).isPresent());
      bottomBar.setActions(true, registry.find(pack.manifest().id()).isPresent());
    } else if (content instanceof InstalledBuilding building) {
      infoPanel.showInstalled(building);
      bottomBar.setActions(false, building.managed());
    } else {
      clearSelection();
    }
  }

  private void showImportFile(ImportFile file) {
    try {
      StructureInfo info = switch (file.format()) {
        case LITEMATIC -> LitematicReader.readInfo(file.path());
        case VANILLA_NBT -> StructureNbtReader.summarize(StructureNbtReader.read(file.path()));
      };
      BuildingMetadata model = new BuildingMetadata();
      model.prefill(info, file.baseName());
      infoPanel.showImport(info, model);
      bottomBar.setActions(true, false);
    } catch (IOException | RuntimeException e) {
      LOGGER.warn("BuildPack: 解析结构失败 {}", file.path(), e);
      infoPanel.showEmpty();
      bottomBar.setActions(false, false);
      bottomBar.setMessage(Component.translatable(
          "buildpack.msg.parse_failed", LocalizedIOException.messageOf(e)), true);
    }
  }

  /** 「安装」按钮回调。 */
  public void runInstall() {
    BuildingInstaller.InstallResult result;
    if (selected instanceof ImportFile file) {
      result = BuildingInstaller.install(file, form.model());
    } else if (selected instanceof PackArchive pack) {
      result = PackInstaller.installPack(pack, registry);
    } else {
      return;
    }
    showResult(result.messages(), !result.ok());
    refresh();
  }

  /** 「卸载」按钮回调。 */
  public void runUninstall() {
    if (selected instanceof InstalledBuilding building && building.managed()) {
      BuildingInstaller.uninstall(building);
      if (building.packId() != null) {
        String prefix = building.category().dirName() + "/";
        registry.removeFile(building.packId(), prefix + building.baseName() + ".sk");
        registry.removeFile(building.packId(), prefix + building.baseName() + ".nbt");
        registry.save();
      }
      bottomBar.setMessage(
          Component.translatable("buildpack.msg.uninstalled", building.name()), false);
      refresh();
    } else if (selected instanceof PackArchive pack) {
      if (PackInstaller.uninstallPack(pack.manifest().id(), registry)) {
        bottomBar.setMessage(Component.translatable(
            "buildpack.msg.uninstalled", pack.manifest().name()), false);
      }
      refresh();
    }
  }

  private void showResult(List<Component> messages, boolean error) {
    MutableComponent joined = Component.empty();
    for (int i = 0; i < messages.size(); i++) {
      if (i > 0) {
        joined.append(Component.literal(" · "));
      }
      joined.append(messages.get(i));
    }
    bottomBar.setMessage(joined, error);
  }

  private void clearSelection() {
    selected = null;
    infoPanel.showEmpty();
    bottomBar.setActions(false, false);
  }

  private void rebuildTree() {
    switch (currentTab) {
      case IMPORT -> {
        List<ImportFile> filtered = importFiles.stream()
            .filter(file -> matches(file.fileName()))
            .toList();
        listPanel.setRoot(DirectoryTree.buildImport(BuildPack.importDir(), filtered));
        updateCount(filtered.size());
      }
      case PACKS -> {
        List<PackArchive> filtered = packs.stream()
            .filter(pack -> matches(pack.manifest().name()) || matches(pack.fileName()))
            .toList();
        listPanel.setRoot(DirectoryTree.buildPacks(filtered));
        updateCount(filtered.size());
      }
      case INSTALLED -> {
        List<InstalledBuilding> filtered = installed.stream()
            .filter(building -> matches(building.name()))
            .toList();
        listPanel.setRoot(DirectoryTree.buildInstalled(filtered));
        updateCount(filtered.size());
      }
    }
  }

  private boolean matches(String text) {
    return searchText.isEmpty() || text.toLowerCase(Locale.ROOT).contains(searchText);
  }

  private void updateCount(int shown) {
    bottomBar.setCount(
        Component.translatable("buildpack.status.count", shown, installed.size()));
  }

  /** Litematica 信息面板的固定宽度（WidgetSchematicBrowser.infoWidth = 170）。 */
  private static final float INFO_PANEL_WIDTH = 170.0f;

  private UIElement buildRoot() {
    // 完全沿用 Litematica 的屏幕骨架：整屏半透明遮罩（GuiBase.TOOLTIP_BACKGROUND），
    // 左上角标题（LEFT=20, TOP=10）→ 页签按钮行 → 文件浏览器（右贴定宽信息面板）
    // → 底部按钮行。背景与边框全部由样式表绘制。
    UIElement container = new UIElement();
    container.addClass(BuildPack.cls("root"));
    container.layout(layout -> layout
        .widthPercent(100.0f).heightPercent(100.0f)
        .flexDirection(FlexDirection.COLUMN)
        .paddingAll(10.0f).gapRow(4.0f));

    Label title = new Label();
    title.addClass(BuildPack.cls("title"));
    title.setValue(Component.translatable("buildpack.title"));
    title.layout(layout -> layout.marginLeft(10.0f).height(12.0f));

    UIElement body = new UIElement();
    body.layout(layout -> layout.flexDirection(FlexDirection.ROW)
        .gapColumn(4.0f).widthStretch().flexGrow(1.0f));
    body.addChildren(buildBrowserColumn(), buildInfoColumn());

    container.addChildren(title, SourceTabs.build(this, currentTab), body, bottomBar.root());
    return container;
  }

  /** 左列：搜索/导航条 + 文件列表（Litematica 的 WidgetFileBrowserBase 结构）。 */
  private UIElement buildBrowserColumn() {
    UIElement column = new UIElement();
    column.layout(layout -> layout.flexDirection(FlexDirection.COLUMN)
        .gapRow(3.0f).heightStretch().flexGrow(1.0f));

    UIElement browserBox = new UIElement();
    browserBox.addClass(BuildPack.cls("browser"));
    browserBox.layout(layout -> layout.flexDirection(FlexDirection.COLUMN)
        .paddingAll(2.0f).widthStretch().flexGrow(1.0f));

    ScrollerView treeScroller = new ScrollerView();
    treeScroller.addClass(BuildPack.cls("tree-scroller"));
    treeScroller.layout(layout -> layout.widthStretch().flexGrow(1.0f));
    treeScroller.addScrollViewChild(listPanel.element());
    browserBox.addChild(treeScroller);

    column.addChildren(SearchBar.build(this), browserBox);
    return column;
  }

  /** 右列：定宽 170px 的信息面板（Litematica 的 schematic info 栏）。 */
  private UIElement buildInfoColumn() {
    UIElement column = infoPanel.root();
    column.layout(layout -> layout.width(INFO_PANEL_WIDTH).heightStretch());
    return column;
  }
}
