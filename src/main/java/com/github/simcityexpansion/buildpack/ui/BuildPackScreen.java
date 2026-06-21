package com.github.simcityexpansion.buildpack.ui;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import com.github.simcityexpansion.buildpack.BuildPack;
import com.github.simcityexpansion.buildpack.I18nLog;
import com.github.simcityexpansion.buildpack.LocalizedIOException;
import com.github.simcityexpansion.buildpack.client.WorldSelection;
import com.github.simcityexpansion.buildpack.convert.NbtStructure;
import com.github.simcityexpansion.buildpack.convert.ParsedStructure;
import com.github.simcityexpansion.buildpack.install.BuildingInstaller;
import com.github.simcityexpansion.buildpack.install.InstallRegistry;
import com.github.simcityexpansion.buildpack.install.PackInstaller;
import com.github.simcityexpansion.buildpack.install.PackReader;
import com.github.simcityexpansion.buildpack.install.SkFileReader;
import com.github.simcityexpansion.buildpack.model.BuildingCategory;
import com.github.simcityexpansion.buildpack.model.BuildingMetadata;
import com.github.simcityexpansion.buildpack.model.FileOps;
import com.github.simcityexpansion.buildpack.model.ImportFile;
import com.github.simcityexpansion.buildpack.model.ImportIndex;
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
  private static final int DEDUPE_W = 90;
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
  private boolean pendingCleanDup;
  private boolean pendingBatchDelete;
  private TreeNode<String, Object> currentRoot;
  private Set<Path> duplicates = Set.of();
  private volatile boolean scanning;
  private volatile int enrichGen;
  private WatchService watcher;
  private final AtomicBoolean refreshPending = new AtomicBoolean();

  private Component message = Component.empty();
  private boolean messageError;
  private Component count = Component.empty();

  private TreeView treeView;
  private ContextMenu contextMenu;
  private ThemedButton sortButton;
  private final ThemedButton[] tabButtons = new ThemedButton[SourceTab.values().length];
  private ThemedButton installButton;
  private ThemedButton batchButton;
  private ThemedButton uninstallButton;
  private ThemedButton deleteButton;
  private ThemedButton exportButton;
  private ThemedButton dedupeButton;

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
    statusX = PAD + REFRESH_W + GAP + OPEN_W + GAP + DEDUPE_W + GAP;
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
    treeView.setOnCheckedChanged(this::updateActionButtons);
    treeView.setOnContext(this::openContextMenu);
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
    dedupeButton = action("dedupe",
        PAD + REFRESH_W + GAP + OPEN_W + GAP, row2Y, DEDUPE_W, this::runCleanDuplicates);
    action("close", width - PAD - CLOSE_W, row2Y, CLOSE_W, this::onClose);

    updateActionButtons();
    startWatch();
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
    int textX = statusX;
    g.drawString(font, count, textX, statusY, BuildPackTheme.COUNT, true);
    textX += font.width(count.getString()) + 8;
    if (scanning) {
      Component scan = Component.translatable("buildpack.status.scanning");
      g.drawString(font, scan, textX, statusY, BuildPackTheme.HINT, true);
      textX += font.width(scan.getString()) + 8;
    }
    if (!message.getString().isEmpty()) {
      g.drawString(font, message, textX, statusY,
          messageError ? BuildPackTheme.MESSAGE_ERROR : BuildPackTheme.MESSAGE_OK, true);
    }

    super.render(g, mouseX, mouseY, partialTick);

    // 当前页签下划线（画在页签按钮之上）。
    int activeX = leftX + currentTab.ordinal() * (tabW + GAP);
    g.fill(activeX, tabsY + TAB_H, activeX + tabW, tabsY + TAB_H + 1, 0xFFFFFFFF);

    if (contextMenu != null) {
      contextMenu.render(g, mouseX, mouseY);
    }
  }

  // ---- 页签 / 搜索 / 排序 ----

  private void switchTab(SourceTab tab) {
    currentTab = tab;
    lastTab = tab;
    pendingCleanDup = false;
    pendingBatchDelete = false;
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
    startEnrich();
  }

  /** 后台富集导入文件索引（解析摘要 + 内容哈希），完成后刷新重复标记与列表。 */
  private void startEnrich() {
    int gen = ++enrichGen;
    List<ImportFile> snapshot = List.copyOf(importFiles);
    if (snapshot.isEmpty()) {
      scanning = false;
      duplicates = Set.of();
      return;
    }
    scanning = true;
    CompletableFuture.runAsync(() -> {
      for (ImportFile file : snapshot) {
        if (gen != enrichGen) {
          return;
        }
        ImportIndex.ensureEnriched(file.path(), file.format());
      }
      ImportIndex.save();
    }).whenComplete((result, error) -> Minecraft.getInstance().execute(() -> {
      if (gen != enrichGen || Minecraft.getInstance().screen != this) {
        return;
      }
      scanning = false;
      duplicates = ImportIndex.duplicatePaths(snapshot);
      if (currentTab == SourceTab.IMPORT) {
        rebuildTree();
      }
    }));
  }

  private void rebuildTree() {
    switch (currentTab) {
      case IMPORT -> {
        List<ImportFile> filtered = filteredImportFiles();
        currentRoot = DirectoryTree.buildImport(BuildPack.importDir(), filtered, duplicates);
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
    if (dedupeButton != null) {
      dedupeButton.visible = currentTab == SourceTab.IMPORT && !duplicates.isEmpty();
    }
    updateActionButtons();
  }

  /** 树中当前勾选、且为指定类型的内容。 */
  @SuppressWarnings("unchecked")
  private <T> List<T> checkedOfType(Class<T> type) {
    if (treeView == null) {
      return List.of();
    }
    return treeView.checked().stream()
        .filter(type::isInstance)
        .map(value -> (T) value)
        .toList();
  }

  private List<ImportFile> filteredImportFiles() {
    return importFiles.stream()
        .filter(this::matchesImport)
        .sorted(sortMode.comparator())
        .toList();
  }

  /** 导入文件匹配：文件名/索引名/作者/标签 子串；支持 {@code fav} 与 {@code tag:} 前缀。 */
  private boolean matchesImport(ImportFile file) {
    if (searchText.isEmpty()) {
      return true;
    }
    if (searchText.equals("fav") || searchText.equals("favorite")) {
      return ImportIndex.favorite(file.path());
    }
    if (searchText.startsWith("tag:")) {
      String needle = searchText.substring(4);
      return ImportIndex.tags(file.path()).stream()
          .anyMatch(tag -> tag.toLowerCase(Locale.ROOT).contains(needle));
    }
    return matches(file.fileName())
        || matches(ImportIndex.name(file.path()))
        || matches(ImportIndex.author(file.path()))
        || ImportIndex.tags(file.path()).stream().anyMatch(this::matches);
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
    setActive(deleteButton,
        selected instanceof ImportFile || !checkedOfType(ImportFile.class).isEmpty());
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
    List<ImportFile> files = checkedOfType(ImportFile.class);
    if (files.isEmpty()) {
      files = filteredImportFiles();
    }
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
    List<ImportFile> checkedFiles = checkedOfType(ImportFile.class);
    if (!checkedFiles.isEmpty()) {
      runBatchDelete(checkedFiles);
      return;
    }
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

  /** 批量删除勾选的导入文件（两击确认）。 */
  private void runBatchDelete(List<ImportFile> files) {
    if (!pendingBatchDelete) {
      pendingBatchDelete = true;
      setMessage(Component.translatable("buildpack.msg.batch_delete_confirm", files.size()), false);
      return;
    }
    pendingBatchDelete = false;
    int removed = 0;
    for (ImportFile file : files) {
      try {
        Files.deleteIfExists(file.path());
        ImportIndex.forget(file.path());
        removed++;
      } catch (IOException e) {
        I18nLog.warn(LOGGER, e, "buildpack.log.import_delete_failed", file.path());
      }
    }
    setMessage(Component.translatable("buildpack.msg.batch_deleted", removed), false);
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
    List<InstalledBuilding> toExport = checkedOfType(InstalledBuilding.class);
    if (toExport.isEmpty()) {
      toExport = installed.stream().filter(building -> matches(building.name())).toList();
    }
    if (toExport.isEmpty()) {
      setMessage(Component.translatable("buildpack.error.export_empty"), true);
      return;
    }
    ExportScreen.open(toExport);
  }

  private void runCaptureSelection() {
    WorldSelection.CaptureResult result = WorldSelection.capture();
    setMessage(result.message(), !result.ok());
    if (result.ok()) {
      refresh();
    }
  }

  /** 「清理重复」回调（两击确认）：删除内容相同的多余副本，每组保留一个。 */
  private void runCleanDuplicates() {
    List<Path> redundant = ImportIndex.redundantDuplicates(importFiles);
    if (redundant.isEmpty()) {
      setMessage(Component.translatable("buildpack.msg.dedupe_none"), false);
      return;
    }
    if (!pendingCleanDup) {
      pendingCleanDup = true;
      setMessage(Component.translatable("buildpack.msg.dedupe_confirm", redundant.size()), false);
      return;
    }
    pendingCleanDup = false;
    int removed = 0;
    for (Path path : redundant) {
      try {
        Files.deleteIfExists(path);
        ImportIndex.forget(path);
        removed++;
      } catch (IOException e) {
        I18nLog.warn(LOGGER, e, "buildpack.log.import_delete_failed", path);
      }
    }
    setMessage(Component.translatable("buildpack.msg.dedupe_done", removed), false);
    refresh();
  }

  // ---- 右键上下文菜单 ----

  private void openContextMenu(Object content, int mouseX, int mouseY) {
    List<ContextMenu.Item> items = new ArrayList<>();
    if (content instanceof ImportFile file) {
      Path path = file.path();
      items.add(new ContextMenu.Item(
          Component.translatable(ImportIndex.favorite(path)
              ? "buildpack.menu.unfavorite" : "buildpack.menu.favorite"),
          () -> {
            ImportIndex.toggleFavorite(path);
            rebuildTree();
          }));
      items.add(new ContextMenu.Item(
          Component.translatable("buildpack.menu.rename"), () -> promptRename(file)));
      items.add(new ContextMenu.Item(
          Component.translatable("buildpack.menu.move"), () -> promptMove(file)));
      items.add(new ContextMenu.Item(
          Component.translatable("buildpack.menu.tags"), () -> promptTags(file)));
      items.add(new ContextMenu.Item(
          Component.translatable("buildpack.menu.note"), () -> promptNote(file)));
      items.add(new ContextMenu.Item(
          Component.translatable("buildpack.menu.reveal"), () -> reveal(path)));
      items.add(new ContextMenu.Item(
          Component.translatable("buildpack.menu.delete"), () -> deleteImportFileNow(file)));
    } else if (content instanceof InstalledBuilding building) {
      items.add(new ContextMenu.Item(Component.translatable("buildpack.menu.recategorize"),
          () -> openCategoryMenu(building, mouseX, mouseY)));
      items.add(new ContextMenu.Item(Component.translatable("buildpack.menu.reveal"),
          () -> reveal(building.skPath())));
      if (building.managed()) {
        items.add(new ContextMenu.Item(Component.translatable("buildpack.menu.uninstall"), () -> {
          selected = building;
          runUninstall();
        }));
      }
    }
    contextMenu = items.isEmpty() ? null : new ContextMenu(mouseX, mouseY, items);
  }

  private void openCategoryMenu(InstalledBuilding building, int mouseX, int mouseY) {
    List<ContextMenu.Item> items = new ArrayList<>();
    for (BuildingCategory category : BuildingCategory.values()) {
      items.add(new ContextMenu.Item(category.displayName(), () -> {
        BuildingInstaller.recategorize(building, category, registry);
        refresh();
      }));
    }
    contextMenu = new ContextMenu(mouseX, mouseY, items);
  }

  private void promptRename(ImportFile file) {
    TextPromptScreen.open(
        Component.translatable("buildpack.menu.rename"),
        Component.translatable("buildpack.prompt.rename"),
        file.baseName(),
        name -> {
          FileOps.rename(file.path(), name);
          refresh();
        });
  }

  private void promptMove(ImportFile file) {
    TextPromptScreen.open(
        Component.translatable("buildpack.menu.move"),
        Component.translatable("buildpack.prompt.move"),
        "",
        folder -> {
          FileOps.moveToFolder(file.path(), folder);
          refresh();
        });
  }

  private void promptTags(ImportFile file) {
    TextPromptScreen.open(
        Component.translatable("buildpack.menu.tags"),
        Component.translatable("buildpack.prompt.tags"),
        String.join(", ", ImportIndex.tags(file.path())),
        text -> {
          List<String> tags = Arrays.stream(text.split(","))
              .map(String::trim)
              .filter(tag -> !tag.isEmpty())
              .toList();
          ImportIndex.setTags(file.path(), tags);
          rebuildTree();
        });
  }

  private void promptNote(ImportFile file) {
    TextPromptScreen.open(
        Component.translatable("buildpack.menu.note"),
        Component.translatable("buildpack.prompt.note"),
        ImportIndex.note(file.path()),
        note -> ImportIndex.setNote(file.path(), note));
  }

  private void deleteImportFileNow(ImportFile file) {
    try {
      Files.deleteIfExists(file.path());
      ImportIndex.forget(file.path());
      setMessage(Component.translatable("buildpack.msg.deleted", file.fileName()), false);
    } catch (IOException e) {
      I18nLog.warn(LOGGER, e, "buildpack.log.import_delete_failed", file.path());
      setMessage(Component.translatable(
          "buildpack.msg.parse_failed", LocalizedIOException.messageOf(e)), true);
    }
    refresh();
  }

  private void reveal(Path path) {
    Path dir = path.getParent();
    if (dir != null) {
      Util.getPlatform().openPath(dir);
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
  public boolean mouseClicked(double mouseX, double mouseY, int button) {
    if (contextMenu != null) {
      ContextMenu menu = contextMenu;
      contextMenu = null;
      menu.click(mouseX, mouseY);
      return true;
    }
    return super.mouseClicked(mouseX, mouseY, button);
  }

  @Override
  public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
    if (contextMenu != null && keyCode == 256) {
      contextMenu = null;
      return true;
    }
    // Ctrl+V（未聚焦输入框时）：把剪贴板里的文件路径导入。
    if (keyCode == 86 && Screen.hasControlDown() && !(getFocused() instanceof EditBox)) {
      importFromClipboard();
      return true;
    }
    return super.keyPressed(keyCode, scanCode, modifiers);
  }

  private void importFromClipboard() {
    String clip = minecraft.keyboardHandler.getClipboard();
    if (clip == null || clip.isBlank()) {
      return;
    }
    try {
      Path src = Path.of(clip.trim());
      if (!Files.isRegularFile(src) || !isImportable(src)) {
        setMessage(Component.translatable("buildpack.msg.clipboard_invalid"), true);
        return;
      }
      Files.copy(src, uniqueTarget(ImportScanner.ensureImportDir(), src.getFileName().toString()));
      setMessage(Component.translatable("buildpack.msg.files_dropped", 1), false);
      refresh();
    } catch (IOException | RuntimeException e) {
      I18nLog.warn(LOGGER, e, "buildpack.log.drop_copy_failed", clip);
      setMessage(Component.translatable("buildpack.msg.clipboard_invalid"), true);
    }
  }

  // ---- 导入目录监听（外部放入文件自动刷新）----

  private void startWatch() {
    if (watcher != null) {
      return;
    }
    try {
      Path dir = ImportScanner.ensureImportDir();
      watcher = FileSystems.getDefault().newWatchService();
      dir.register(watcher, StandardWatchEventKinds.ENTRY_CREATE,
          StandardWatchEventKinds.ENTRY_DELETE, StandardWatchEventKinds.ENTRY_MODIFY);
      WatchService active = watcher;
      Thread thread = new Thread(() -> watchLoop(active), "buildpack-import-watch");
      thread.setDaemon(true);
      thread.start();
    } catch (IOException e) {
      I18nLog.warn(LOGGER, e, "buildpack.log.import_dir_failed", BuildPack.importDir());
    }
  }

  private void watchLoop(WatchService active) {
    while (true) {
      WatchKey key;
      try {
        key = active.take();
      } catch (InterruptedException | ClosedWatchServiceException e) {
        return;
      }
      key.pollEvents();
      if (refreshPending.compareAndSet(false, true)) {
        Minecraft.getInstance().execute(() -> {
          refreshPending.set(false);
          if (Minecraft.getInstance().screen == this) {
            refresh();
          }
        });
      }
      if (!key.reset()) {
        return;
      }
    }
  }

  private void stopWatch() {
    if (watcher != null) {
      try {
        watcher.close();
      } catch (IOException ignored) {
        // 关闭监听失败无需处理。
      }
      watcher = null;
    }
  }

  @Override
  public void removed() {
    stopWatch();
  }

  @Override
  public void onClose() {
    minecraft.setScreen(null);
  }
}
