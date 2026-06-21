package com.github.simcityexpansion.buildpack.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.github.simcityexpansion.buildpack.BuildPack;
import com.github.simcityexpansion.buildpack.I18nLog;
import com.github.simcityexpansion.buildpack.LocalizedIOException;
import com.github.simcityexpansion.buildpack.client.WorldSelection;
import com.github.simcityexpansion.buildpack.convert.NbtStructure;
import com.github.simcityexpansion.buildpack.convert.ParsedStructure;
import com.github.simcityexpansion.buildpack.install.BuildingInstaller;
import com.github.simcityexpansion.buildpack.install.InstallRegistry;
import com.github.simcityexpansion.buildpack.install.PackExporter;
import com.github.simcityexpansion.buildpack.install.PackInstaller;
import com.github.simcityexpansion.buildpack.install.PackReader;
import com.github.simcityexpansion.buildpack.install.SkFileReader;
import com.github.simcityexpansion.buildpack.model.BuildingCategory;
import com.github.simcityexpansion.buildpack.model.BuildingMetadata;
import com.github.simcityexpansion.buildpack.model.ImportFile;
import com.github.simcityexpansion.buildpack.model.ImportScanner;
import com.github.simcityexpansion.buildpack.model.InstalledBuilding;
import com.github.simcityexpansion.buildpack.model.InstalledScanner;
import com.github.simcityexpansion.buildpack.model.PackArchive;
import com.github.simcityexpansion.buildpack.model.StructureFormat;
import com.github.simcityexpansion.buildpack.model.StructureInfo;
import com.github.simcityexpansion.buildpack.ui.component.InfoPanel;
import com.github.simcityexpansion.buildpack.ui.component.MetadataForm;
import com.github.simcityexpansion.buildpack.ui.component.TreeView;
import com.github.simcityexpansion.buildpack.ui.tree.TreeNode;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 建筑拓展包管理器界面（原版 {@link Screen} 重写）：左栏来源页签 + 搜索 + 自绘文件树，
 * 右栏定宽信息/预览/元数据表单，底部操作与状态栏。支持把结构文件 / zip 拓展包拖进窗口导入。
 *
 * <p>整合了原 BuildPackView 控制器与各子组件：业务逻辑（扫描/安装/卸载/删除/导出/捕获选区）
 * 不变，仅界面层改用原版控件与手工布局。
 */
public final class BuildPackScreen extends Screen {

  private static final Logger LOGGER = LoggerFactory.getLogger(BuildPackScreen.class);

  private static final int PAD = 10;
  private static final int GAP = 4;
  private static final int TITLE_H = 12;
  private static final int TAB_H = 16;
  private static final int TAB_W = 90;
  private static final int SEARCH_H = 18;
  private static final int SORT_W = 64;
  private static final int BTN_H = 20;
  private static final int ROW2_GAP = 2;
  private static final int INFO_W = 170;
  private static final int REFRESH_W = 70;
  private static final int OPEN_W = 90;
  private static final int CLOSE_W = 70;

  /** 会话内记住上次停留的页签。 */
  private static SourceTab lastTab = SourceTab.IMPORT;

  /** 指定下次打开管理器时停留的页签（编辑器保存后用，便于看到新导入文件）。 */
  public static void setLastTab(SourceTab tab) {
    lastTab = tab;
  }

  /** 打开建筑拓展包管理器界面。 */
  public static void open() {
    Minecraft.getInstance().setScreen(new BuildPackScreen());
  }

  private final InstallRegistry registry;
  private final MetadataForm form;
  private final InfoPanel infoPanel;

  private SourceTab currentTab = lastTab;
  private SortMode sortMode = SortMode.NAME;
  private String searchText = "";
  private List<ImportFile> importFiles = List.of();
  private List<PackArchive> packs = List.of();
  private List<String> invalidZips = List.of();
  private List<InstalledBuilding> installed = List.of();
  private Object selected;
  private Path pendingDelete;
  private TreeNode<String, Object> currentRoot;

  private Component message = Component.empty();
  private boolean messageError;
  private Component count = Component.empty();

  private TreeView treeView;
  private ThemedButton sortButton;
  private final ThemedButton[] tabButtons = new ThemedButton[SourceTab.values().length];
  private ThemedButton installButton;
  private ThemedButton batchButton;
  private ThemedButton uninstallButton;
  private ThemedButton deleteButton;
  private ThemedButton exportButton;

  // 布局缓存（随屏幕尺寸计算，init 与 render 共用）。
  private int leftX;
  private int leftW;
  private int rightX;
  private int tabsY;
  private int tabW;
  private int searchY;
  private int treeY;
  private int bodyBottom;
  private int row2Y;
  private int statusX;

  public BuildPackScreen() {
    super(Component.translatable("buildpack.title"));
    registry = InstallRegistry.load();
    form = new MetadataForm();
    infoPanel = new InfoPanel(form);
    refresh();

    // 连接远程服务器时给出提醒：文件只会写到本机，专用服务器需要管理员在服务端安装。
    Minecraft minecraft = Minecraft.getInstance();
    if (minecraft.level != null && minecraft.getSingleplayerServer() == null) {
      setMessage(Component.translatable("buildpack.status.remote_warning"), true);
    }
  }

  private void computeLayout() {
    rightX = width - PAD - INFO_W;
    leftX = PAD;
    leftW = rightX - GAP - leftX;
    tabsY = PAD + TITLE_H + GAP;
    tabW = Math.min(TAB_W, (leftW - GAP * 2) / 3);
    searchY = tabsY + TAB_H + GAP;
    treeY = searchY + SEARCH_H + GAP;
    row2Y = height - PAD - BTN_H;
    int row1Y = row2Y - ROW2_GAP - BTN_H;
    bodyBottom = row1Y - GAP;
    statusX = PAD + REFRESH_W + GAP + OPEN_W + GAP;
  }

  @Override
  protected void init() {
    computeLayout();
    int row1Y = row2Y - ROW2_GAP - BTN_H;

    // 来源页签
    SourceTab[] tabs = SourceTab.values();
    for (int i = 0; i < tabs.length; i++) {
      SourceTab tab = tabs[i];
      ThemedButton button = new ThemedButton(leftX + i * (tabW + GAP), tabsY, tabW, TAB_H,
          tab.displayName(), () -> switchTab(tab));
      button.setTooltip(Tooltip.create(tab.tooltip()));
      tabButtons[i] = button;
      addRenderableWidget(button);
    }

    // 搜索 + 排序
    EditBox searchBox = new EditBox(font, leftX, searchY, leftW - SORT_W - GAP, SEARCH_H,
        Component.translatable("buildpack.search.placeholder"));
    searchBox.setHint(Component.translatable("buildpack.search.placeholder"));
    searchBox.setMaxLength(128);
    searchBox.setResponder(this::onSearchText);
    searchBox.setValue(searchText);
    addRenderableWidget(searchBox);

    sortButton = new ThemedButton(leftX + leftW - SORT_W, searchY, SORT_W, SEARCH_H,
        sortMode.displayName(), this::cycleSort);
    sortButton.setTooltip(Tooltip.create(Component.translatable("buildpack.tooltip.sort")));
    addRenderableWidget(sortButton);

    // 文件树
    treeView = new TreeView(leftX + 2, treeY + 2, leftW - 4, bodyBottom - treeY - 4,
        this::onNodeSelected);
    treeView.setRoot(currentRoot);
    addRenderableWidget(treeView);

    // 右栏信息面板
    infoPanel.rebuild(font, rightX, searchY, INFO_W, bodyBottom - searchY, this::addRenderableWidget);

    // 底部操作行
    int actionCount = 6;
    int actionW = (width - PAD * 2 - GAP * (actionCount - 1)) / actionCount;
    int ax = PAD;
    installButton = action("install", ax, row1Y, actionW, this::runInstall);
    ax += actionW + GAP;
    batchButton = action("batch", ax, row1Y, actionW, this::runBatchInstall);
    ax += actionW + GAP;
    uninstallButton = action("uninstall", ax, row1Y, actionW, this::runUninstall);
    ax += actionW + GAP;
    deleteButton = action("delete", ax, row1Y, actionW, this::runDelete);
    ax += actionW + GAP;
    exportButton = action("export", ax, row1Y, actionW, this::runExport);
    ax += actionW + GAP;
    action("capture", ax, row1Y, actionW, this::runCaptureSelection);

    // 底部工具行
    action("refresh", PAD, row2Y, REFRESH_W, this::refresh);
    action("open_folder", PAD + REFRESH_W + GAP, row2Y, OPEN_W, this::openFolder);
    action("close", width - PAD - CLOSE_W, row2Y, CLOSE_W, this::onClose);

    updateActionButtons();
  }

  private ThemedButton action(String key, int x, int y, int w, Runnable onClick) {
    ThemedButton button = new ThemedButton(
        x, y, w, BTN_H, Component.translatable("buildpack.action." + key), onClick);
    button.setTooltip(Tooltip.create(Component.translatable("buildpack.tooltip." + key)));
    addRenderableWidget(button);
    return button;
  }

  // ---- 渲染 ----

  @Override
  public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
    // 覆盖原版：不做高斯模糊背景；遮罩与面板由 render() 自行绘制。
  }

  @Override
  public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
    computeLayout();
    g.fill(0, 0, width, height, BuildPackTheme.ROOT_BG);
    BuildPackTheme.panel(g, leftX, treeY, leftW, bodyBottom - treeY);
    BuildPackTheme.panel(g, rightX, searchY, INFO_W, bodyBottom - searchY);
    g.drawString(font, Component.translatable("buildpack.title"), leftX + 2, PAD,
        BuildPackTheme.TITLE, true);

    infoPanel.renderText(g);

    int statusY = row2Y + (BTN_H - font.lineHeight) / 2;
    g.drawString(font, count, statusX, statusY, BuildPackTheme.COUNT, true);
    if (!message.getString().isEmpty()) {
      g.drawString(font, message, statusX + 70, statusY,
          messageError ? BuildPackTheme.MESSAGE_ERROR : BuildPackTheme.MESSAGE_OK, true);
    }

    super.render(g, mouseX, mouseY, partialTick);

    // 当前页签下划线（画在页签按钮之上）。
    int activeX = leftX + currentTab.ordinal() * (tabW + GAP);
    g.fill(activeX, tabsY + TAB_H, activeX + tabW, tabsY + TAB_H + 1, 0xFFFFFFFF);
  }

  // ---- 页签 / 搜索 / 排序 ----

  private void switchTab(SourceTab tab) {
    currentTab = tab;
    lastTab = tab;
    clearSelection();
    rebuildTree();
  }

  private void onSearchText(String text) {
    searchText = text == null ? "" : text.toLowerCase(Locale.ROOT).trim();
    rebuildTree();
  }

  private void cycleSort() {
    SortMode[] modes = SortMode.values();
    sortMode = modes[(sortMode.ordinal() + 1) % modes.length];
    sortButton.setMessage(sortMode.displayName());
    rebuildTree();
  }

  // ---- 数据刷新 ----

  /** 重新扫描三种来源并刷新列表。 */
  private void refresh() {
    importFiles = ImportScanner.scan();

    List<PackArchive> parsed = new ArrayList<>();
    List<String> invalid = new ArrayList<>();
    for (Path zip : ImportScanner.scanZips()) {
      try {
        parsed.add(PackReader.read(zip));
      } catch (IOException | RuntimeException e) {
        I18nLog.warn(LOGGER, e, "buildpack.log.pack_invalid", zip);
        invalid.add(zip.getFileName().toString());
      }
    }
    packs = parsed;
    invalidZips = invalid;

    installed = InstalledScanner.scan(registry);
    clearSelection();
    rebuildTree();

    if (!invalidZips.isEmpty()) {
      setMessage(Component.translatable(
          "buildpack.msg.invalid_pack", String.join(", ", invalidZips)), true);
    }
  }

  private void rebuildTree() {
    switch (currentTab) {
      case IMPORT -> {
        List<ImportFile> filtered = filteredImportFiles();
        currentRoot = DirectoryTree.buildImport(BuildPack.importDir(), filtered);
        updateCount(filtered.size());
      }
      case PACKS -> {
        List<PackArchive> filtered = packs.stream()
            .filter(pack -> matches(pack.manifest().name()) || matches(pack.fileName()))
            .toList();
        currentRoot = DirectoryTree.buildPacks(filtered);
        updateCount(filtered.size());
      }
      case INSTALLED -> {
        List<InstalledBuilding> filtered = installed.stream()
            .filter(building -> matches(building.name()))
            .toList();
        currentRoot = DirectoryTree.buildInstalled(filtered);
        updateCount(filtered.size());
      }
    }
    if (treeView != null) {
      treeView.setRoot(currentRoot);
    }
    updateActionButtons();
  }

  private List<ImportFile> filteredImportFiles() {
    return importFiles.stream()
        .filter(file -> matches(file.fileName()))
        .sorted(sortMode.comparator())
        .toList();
  }

  private boolean matches(String text) {
    return searchText.isEmpty() || text.toLowerCase(Locale.ROOT).contains(searchText);
  }

  private void updateCount(int shown) {
    count = Component.translatable("buildpack.status.count", shown, installed.size());
  }

  // ---- 选中 ----

  private void onNodeSelected(Object content) {
    selected = content;
    pendingDelete = null;
    if (content instanceof ImportFile file) {
      showImportFile(file);
    } else if (content instanceof PackArchive pack) {
      infoPanel.showPack(pack, registry.find(pack.manifest().id()).isPresent());
    } else if (content instanceof PackBuildingSelection selection) {
      showPackBuilding(selection);
    } else if (content instanceof InstalledBuilding building) {
      showInstalled(building);
    } else {
      clearSelection();
      return;
    }
    updateActionButtons();
  }

  private void showImportFile(ImportFile file) {
    try {
      ParsedStructure parsed = ParsedStructure.parse(file.path(), file.format());
      BuildingMetadata model = new BuildingMetadata();
      model.prefill(parsed.info(), file.baseName());
      prefillAuthor(model);
      infoPanel.showImport(file, parsed.info(), parsed.structure(), model);
    } catch (IOException | RuntimeException e) {
      I18nLog.warn(LOGGER, e, "buildpack.log.structure_parse_failed", file.path());
      infoPanel.showEmpty();
      setMessage(Component.translatable(
          "buildpack.msg.parse_failed", LocalizedIOException.messageOf(e)), true);
    }
  }

  private void showInstalled(InstalledBuilding building) {
    StructureInfo info = null;
    NbtStructure structure = null;
    if (building.structurePath() != null) {
      try {
        ParsedStructure parsed =
            ParsedStructure.parse(building.structurePath(), StructureFormat.VANILLA_NBT);
        info = parsed.info();
        structure = parsed.structure();
      } catch (IOException | RuntimeException e) {
        I18nLog.warn(LOGGER, e, "buildpack.log.structure_parse_failed", building.structurePath());
      }
    }
    infoPanel.showInstalled(building, info, structure);
  }

  private void showPackBuilding(PackBuildingSelection selection) {
    try {
      byte[] bytes = PackReader.readEntryBytes(
          selection.pack().zipPath(), selection.entry().structureEntry());
      ParsedStructure parsed = ParsedStructure.parse(bytes, selection.entry().format());
      BuildingMetadata meta = readZipMeta(selection, parsed);
      infoPanel.showPackBuilding(selection, parsed.info(), parsed.structure(), meta);
    } catch (IOException | RuntimeException e) {
      I18nLog.warn(LOGGER, e, "buildpack.log.pack_entry_read_failed",
          selection.entry().structureEntry());
      infoPanel.showEmpty();
      setMessage(Component.translatable(
          "buildpack.msg.parse_failed", LocalizedIOException.messageOf(e)), true);
    }
  }

  private static BuildingMetadata readZipMeta(
      PackBuildingSelection selection, ParsedStructure parsed) throws IOException {
    BuildingMetadata meta;
    if (selection.entry().skEntry() != null) {
      var fields = SkFileReader.parseFields(PackReader.readEntryBytes(
          selection.pack().zipPath(), selection.entry().skEntry()));
      meta = new BuildingMetadata();
      meta.name = fields.getOrDefault("name", "");
      meta.amount = fields.getOrDefault("amount", "");
      meta.author = fields.getOrDefault("author", "");
      meta.description = fields.getOrDefault("description", "");
      meta.tags = fields.getOrDefault("tags", "");
      meta.jobType = fields.getOrDefault("job_type", "");
    } else if (selection.entry().metaJsonEntry() != null) {
      meta = PackInstaller.readJsonMeta(PackReader.readEntryBytes(
          selection.pack().zipPath(), selection.entry().metaJsonEntry()));
    } else {
      meta = new BuildingMetadata();
    }
    meta.prefill(parsed.info(), selection.entry().name());
    meta.category = selection.entry().category();
    return meta;
  }

  private static void prefillAuthor(BuildingMetadata model) {
    Minecraft minecraft = Minecraft.getInstance();
    if (model.author.isBlank() && minecraft.player != null) {
      model.author = minecraft.player.getGameProfile().getName();
    }
  }

  private void clearSelection() {
    selected = null;
    pendingDelete = null;
    infoPanel.showEmpty();
    updateActionButtons();
  }

  private void updateActionButtons() {
    boolean canInstall = selected instanceof ImportFile
        || selected instanceof PackArchive
        || selected instanceof PackBuildingSelection;
    boolean canUninstall =
        (selected instanceof InstalledBuilding b && b.managed())
        || (selected instanceof PackArchive p && registry.find(p.manifest().id()).isPresent());
    setActive(installButton, canInstall);
    setActive(uninstallButton, canUninstall);
    setActive(deleteButton, selected instanceof ImportFile);
    setActive(exportButton, currentTab == SourceTab.INSTALLED
        && installed.stream().anyMatch(building -> matches(building.name())));
    setActive(batchButton, currentTab == SourceTab.IMPORT
        && importFiles.stream().anyMatch(file -> matches(file.fileName())));
  }

  private static void setActive(AbstractWidget widget, boolean active) {
    if (widget != null) {
      widget.active = active;
    }
  }

  // ---- 操作 ----

  private void runInstall() {
    BuildingInstaller.InstallResult result;
    if (selected instanceof ImportFile file) {
      result = BuildingInstaller.install(file, form.model(), form.overwrite());
    } else if (selected instanceof PackArchive pack) {
      result = PackInstaller.installPack(pack, registry);
    } else if (selected instanceof PackBuildingSelection selection) {
      result = PackInstaller.installSingle(selection.pack(), selection.entry());
    } else {
      return;
    }
    showResult(result.messages(), !result.ok());
    refresh();
  }

  private void runBatchInstall() {
    List<ImportFile> files = filteredImportFiles();
    if (files.isEmpty()) {
      return;
    }
    BuildingCategory category = form.model().category;
    int ok = 0;
    int failed = 0;
    for (ImportFile file : files) {
      try {
        BuildingMetadata meta = new BuildingMetadata();
        meta.prefill(ParsedStructure.parse(file.path(), file.format()).info(), file.baseName());
        prefillAuthor(meta);
        meta.category = category;
        if (BuildingInstaller.install(file, meta, false).ok()) {
          ok++;
        } else {
          failed++;
        }
      } catch (IOException | RuntimeException e) {
        I18nLog.warn(LOGGER, e, "buildpack.log.batch_failed", file.path());
        failed++;
      }
    }
    setMessage(Component.translatable("buildpack.msg.batch_done", ok, failed), failed > 0);
    refresh();
  }

  private void runDelete() {
    if (!(selected instanceof ImportFile file)) {
      return;
    }
    if (!file.path().equals(pendingDelete)) {
      pendingDelete = file.path();
      setMessage(Component.translatable("buildpack.msg.delete_confirm", file.fileName()), false);
      return;
    }
    try {
      Files.deleteIfExists(file.path());
      setMessage(Component.translatable("buildpack.msg.deleted", file.fileName()), false);
    } catch (IOException e) {
      I18nLog.warn(LOGGER, e, "buildpack.log.import_delete_failed", file.path());
      setMessage(Component.translatable(
          "buildpack.msg.parse_failed", LocalizedIOException.messageOf(e)), true);
    }
    pendingDelete = null;
    refresh();
  }

  private void runUninstall() {
    if (selected instanceof InstalledBuilding building && building.managed()) {
      BuildingInstaller.uninstall(building);
      if (building.packId() != null) {
        String prefix = building.category().dirName() + "/";
        registry.removeFile(building.packId(), prefix + building.baseName() + ".sk");
        registry.removeFile(building.packId(), prefix + building.baseName() + ".nbt");
        registry.save();
      }
      setMessage(Component.translatable("buildpack.msg.uninstalled", building.name()), false);
      refresh();
    } else if (selected instanceof PackArchive pack) {
      if (PackInstaller.uninstallPack(pack.manifest().id(), registry)) {
        setMessage(Component.translatable("buildpack.msg.uninstalled", pack.manifest().name()), false);
      }
      refresh();
    }
  }

  private void runExport() {
    List<InstalledBuilding> toExport = installed.stream()
        .filter(building -> matches(building.name()))
        .toList();
    try {
      Path zip = PackExporter.export(toExport);
      setMessage(Component.translatable("buildpack.msg.exported", zip.getFileName().toString()), false);
      Util.getPlatform().openPath(PackExporter.exportDir());
    } catch (IOException | RuntimeException e) {
      I18nLog.warn(LOGGER, e, "buildpack.log.export_failed");
      setMessage(Component.translatable(
          "buildpack.msg.parse_failed", LocalizedIOException.messageOf(e)), true);
    }
  }

  private void runCaptureSelection() {
    WorldSelection.CaptureResult result = WorldSelection.capture();
    setMessage(result.message(), !result.ok());
    if (result.ok()) {
      refresh();
    }
  }

  private void openFolder() {
    if (currentTab == SourceTab.INSTALLED) {
      try {
        Files.createDirectories(BuildPack.simukraftDir());
      } catch (IOException e) {
        I18nLog.warn(LOGGER, e, "buildpack.log.building_dir_failed");
      }
      Util.getPlatform().openPath(BuildPack.simukraftDir());
    } else {
      Util.getPlatform().openPath(ImportScanner.ensureImportDir());
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
    setMessage(joined, error);
  }

  private void setMessage(Component message, boolean error) {
    this.message = message;
    this.messageError = error;
  }

  // ---- 文件拖入 ----

  @Override
  public void onFilesDrop(List<Path> paths) {
    Path importDir = ImportScanner.ensureImportDir();
    int copied = 0;
    for (Path path : paths) {
      if (!Files.isRegularFile(path) || !isImportable(path)) {
        continue;
      }
      try {
        Files.copy(path, uniqueTarget(importDir, path.getFileName().toString()));
        copied++;
      } catch (IOException e) {
        I18nLog.warn(LOGGER, e, "buildpack.log.drop_copy_failed", path);
      }
    }
    if (copied > 0) {
      refresh();
      setMessage(Component.translatable("buildpack.msg.files_dropped", copied), false);
    }
  }

  private static boolean isImportable(Path path) {
    String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
    return StructureFormat.byFileName(name).isPresent() || name.endsWith(".zip");
  }

  private static Path uniqueTarget(Path dir, String fileName) {
    Path target = dir.resolve(fileName);
    if (!Files.exists(target)) {
      return target;
    }
    int dot = fileName.lastIndexOf('.');
    String base = dot > 0 ? fileName.substring(0, dot) : fileName;
    String extension = dot > 0 ? fileName.substring(dot) : "";
    int suffix = 2;
    while (Files.exists(target)) {
      target = dir.resolve(base + "_" + suffix++ + extension);
    }
    return target;
  }

  @Override
  public void onClose() {
    minecraft.setScreen(null);
  }
}
