package com.github.simcityexpansion.buildpack.ui;

import java.io.ByteArrayInputStream;
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
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import com.github.simcityexpansion.buildpack.BuildPack;
import com.github.simcityexpansion.buildpack.I18nLog;
import com.github.simcityexpansion.buildpack.LocalizedIOException;
import com.github.simcityexpansion.buildpack.client.WorldSelection;
import com.github.simcityexpansion.buildpack.convert.NbtStructure;
import com.github.simcityexpansion.buildpack.convert.ParsedStructure;
import com.github.simcityexpansion.buildpack.convert.StructureMaterializer;
import com.github.simcityexpansion.buildpack.install.BuildingInstaller;
import com.github.simcityexpansion.buildpack.install.InstallRegistry;
import com.github.simcityexpansion.buildpack.install.LegacyMigration;
import com.github.simcityexpansion.buildpack.install.PackInstaller;
import com.github.simcityexpansion.buildpack.install.PackReader;
import com.github.simcityexpansion.buildpack.install.SkFileReader;
import com.github.simcityexpansion.buildpack.integration.ActivePackProvider;
import com.github.simcityexpansion.buildpack.integration.CreateSchematics;
import com.github.simcityexpansion.buildpack.integration.PackActivationService;
import com.github.simcityexpansion.buildpack.integration.SimukraftBridge;
import com.github.simcityexpansion.buildpack.model.BuildingCategory;
import com.github.simcityexpansion.buildpack.model.BuildingMetadata;
import com.github.simcityexpansion.buildpack.model.FileNames;
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
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Build pack manager screen (vanilla {@link Screen} reimplementation): left column with source
 * tabs, search, and a custom-drawn file tree; fixed-width right column for info/preview/metadata
 * form; bottom action and status bar. Supports drag-and-drop of structure files and zip build
 * packs into the window for import.
 *
 * <p>Integrates the original BuildPackView controller with all sub-components: business logic
 * (scan/install/uninstall/delete/export/capture selection) is unchanged; only the UI layer is
 * rewritten to use vanilla widgets with manual layout.
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
  private static final int INFO_W = 200;
  private static final int REFRESH_W = 70;
  private static final int OPEN_W = 90;
  private static final int DEDUPE_W = 90;
  private static final int GUIDE_W = 50;
  private static final int CLOSE_W = 70;
  private static final int SCALE_W = 18;

  /** Remembers the last active tab within a session. */
  private static SourceTab lastTab = SourceTab.IMPORT;

  /** Sets the tab to show the next time the manager is opened (used after an editor save, so the newly imported file is visible). */
  public static void setLastTab(SourceTab tab) {
    lastTab = tab;
  }

  /**
   * Parent captured by the most recent manager instance. Sub-screens (editor, preview, material
   * list) return via {@link #open()}, which would otherwise drop the mods-list parent of a
   * title-screen session; in-game sessions never inherit it (close returns to gameplay).
   */
  @Nullable
  private static Screen lastParent;

  /** Opens the build pack manager screen (keybind and sub-screen return path). */
  public static void open() {
    Minecraft minecraft = Minecraft.getInstance();
    minecraft.setScreen(new BuildPackScreen(minecraft.level == null ? lastParent : null));
  }

  /** Screen restored on close (mods list / title screen); null returns to gameplay as before. */
  @Nullable
  private final Screen parent;

  private final InstallRegistry registry;
  private final MetadataForm form;
  private final InfoPanel infoPanel;

  private SourceTab currentTab = lastTab;
  /** User-chosen manager UI scale; the whole screen is rendered through a matching pose transform. */
  private float uiScale = UiScale.preference();
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
  /** True while a background install/convert is running; gates the action buttons against re-entry. */
  private volatile boolean busy;
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
  private ThemedButton activateButton;
  private ThemedButton dedupeButton;

  // Layout cache (computed from screen dimensions; shared between init and render).
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
    this(null);
  }

  /**
   * @param parent screen to return to on close — set when opened outside a world (mods list
   *     "Config" entry), so pack development works without entering a save
   */
  public BuildPackScreen(@Nullable Screen parent) {
    super(Component.translatable("buildpack.title"));
    this.parent = parent;
    lastParent = parent;
    registry = InstallRegistry.load();
    form = new MetadataForm();
    infoPanel = new InfoPanel(form);
    infoPanel.setOnDefinitionChanged(this::refresh);
    refresh();

    // Warn when connected to a remote server: files are only written to the local machine;
    // a dedicated server requires an administrator to install them server-side.
    Minecraft minecraft = Minecraft.getInstance();
    if (minecraft.level != null && minecraft.getSingleplayerServer() == null) {
      setMessage(Component.translatable("buildpack.status.remote_warning"), true);
    }
  }

  /** Logical (pre-scale) width the layout is computed in; rendered through a {@code uiScale} pose transform to fill the screen. */
  private int viewW() {
    return Math.round(width / uiScale);
  }

  /** Logical (pre-scale) height the layout is computed in. */
  private int viewH() {
    return Math.round(height / uiScale);
  }

  private void computeLayout() {
    rightX = viewW() - PAD - INFO_W;
    leftX = PAD;
    leftW = rightX - GAP - leftX;
    tabsY = PAD + TITLE_H + GAP;
    tabW = Math.min(TAB_W, (leftW - GAP * 2) / 3);
    searchY = tabsY + TAB_H + GAP;
    treeY = searchY + SEARCH_H + GAP;
    row2Y = viewH() - PAD - BTN_H;
    int row1Y = row2Y - ROW2_GAP - BTN_H;
    bodyBottom = row1Y - GAP;
    statusX = PAD + REFRESH_W + GAP + OPEN_W + GAP + DEDUPE_W + GAP;
  }

  @Override
  protected void init() {
    computeLayout();
    int row1Y = row2Y - ROW2_GAP - BTN_H;

    // Source tabs
    SourceTab[] tabs = SourceTab.values();
    for (int i = 0; i < tabs.length; i++) {
      SourceTab tab = tabs[i];
      ThemedButton button = new ThemedButton(leftX + i * (tabW + GAP), tabsY, tabW, TAB_H,
          tab.displayName(), () -> switchTab(tab));
      button.setTooltip(Tooltip.create(tab.tooltip()));
      tabButtons[i] = button;
      addRenderableWidget(button);
    }

    // Search + sort
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

    // File tree
    treeView = new TreeView(leftX + 2, treeY + 2, leftW - 4, bodyBottom - treeY - 4,
        this::onNodeSelected);
    treeView.setOnCheckedChanged(this::updateActionButtons);
    treeView.setOnContext(this::openContextMenu);
    treeView.setRoot(currentRoot);
    addRenderableWidget(treeView);

    // Right-column info panel
    infoPanel.rebuild(font, rightX, searchY, INFO_W, bodyBottom - searchY, this::addRenderableWidget);

    // Bottom action row
    int actionCount = 7;
    int actionW = (viewW() - PAD * 2 - GAP * (actionCount - 1)) / actionCount;
    int ax = PAD;
    installButton = action("install", ax, row1Y, actionW, this::runInstall);
    ax += actionW + GAP;
    batchButton = action("batch", ax, row1Y, actionW, this::runBatchInstall);
    ax += actionW + GAP;
    uninstallButton = action("uninstall", ax, row1Y, actionW, this::runUninstall);
    ax += actionW + GAP;
    activateButton = action("activate", ax, row1Y, actionW, this::runActivateToggle);
    ax += actionW + GAP;
    deleteButton = action("delete", ax, row1Y, actionW, this::runDelete);
    ax += actionW + GAP;
    exportButton = action("export", ax, row1Y, actionW, this::runExport);
    ax += actionW + GAP;
    ThemedButton captureButton = action("capture", ax, row1Y, actionW, this::runCaptureSelection);
    // Capturing reads blocks from the loaded world; from the title screen there is none.
    if (minecraft.level == null) {
      captureButton.active = false;
      captureButton.setTooltip(
          Tooltip.create(Component.translatable("buildpack.tooltip.capture_no_world")));
    }

    // Bottom toolbar row
    action("refresh", PAD, row2Y, REFRESH_W, this::refresh);
    action("open_folder", PAD + REFRESH_W + GAP, row2Y, OPEN_W, this::openFolder);
    dedupeButton = action("dedupe",
        PAD + REFRESH_W + GAP + OPEN_W + GAP, row2Y, DEDUPE_W, this::runCleanDuplicates);
    // Pack-making guide in the title row's free right corner (the bottom rows are full at
    // narrow widths).
    action("guide", viewW() - PAD - GUIDE_W, PAD - 2, GUIDE_W, PackGuideScreen::open)
        .setHeight(TAB_H - 2);
    int closeX = viewW() - PAD - CLOSE_W;
    int scaleUpX = closeX - GAP - SCALE_W;
    int scaleDownX = scaleUpX - GAP - SCALE_W;
    action("scale_down", scaleDownX, row2Y, SCALE_W, () -> changeScale(-UiScale.STEP));
    action("scale_up", scaleUpX, row2Y, SCALE_W, () -> changeScale(UiScale.STEP));
    action("close", closeX, row2Y, CLOSE_W, this::onClose);

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

  // ---- Rendering ----

  @Override
  public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
    // Override vanilla: no Gaussian-blur background; the dim overlay and panels are drawn by render().
  }

  @Override
  public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
    // Render the whole manager through a uiScale pose transform; convert the real cursor position to
    // the matching logical space so widget hover/click line up. UiScale.current lets scissor-using
    // components correct their clip rects (enableScissor ignores the pose matrix).
    int lmx = Math.round(mouseX / uiScale);
    int lmy = Math.round(mouseY / uiScale);
    UiScale.set(uiScale);
    g.pose().pushPose();
    g.pose().scale(uiScale, uiScale, 1.0f);
    try {
      computeLayout();
      g.fill(0, 0, viewW() + 2, viewH() + 2, BuildPackTheme.ROOT_BG);
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

      super.render(g, lmx, lmy, partialTick);

      // Active-tab underline (drawn on top of the tab buttons).
      int activeX = leftX + currentTab.ordinal() * (tabW + GAP);
      g.fill(activeX, tabsY + TAB_H, activeX + tabW, tabsY + TAB_H + 1, BuildPackTheme.ACCENT);

      if (contextMenu != null) {
        contextMenu.render(g, lmx, lmy);
      }
    } finally {
      g.pose().popPose();
      UiScale.set(1.0f);
    }
  }

  // ---- Tabs / search / sort ----

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

  // ---- Data refresh ----

  /** Rescans all three sources and refreshes the list. */
  private void refresh() {
    // Pick up registry changes made elsewhere (e.g. the dedicated-server command) before scanning.
    registry.reload();
    // Fold any pre-2.0 loose building files into the managed zip before scanning (no-op when clean).
    List<Component> migrationMessages = new ArrayList<>();
    LegacyMigration.migrateIfNeeded(registry, migrationMessages);
    if (!migrationMessages.isEmpty()) {
      setMessage(migrationMessages.get(0), false);
      SimukraftBridge.requestCatalogReload();
    }
    // Import sources: the import directory plus (when present) Create's schematics directory.
    List<ImportFile> scanned = new ArrayList<>(ImportScanner.scan());
    scanned.addAll(ImportScanner.scanCreateSchematics());
    importFiles = scanned;

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

  /** Enriches the import-file index in the background (parses summaries and content hashes), then refreshes duplicate markers and the list. */
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

  /** Returns all tree-checked items that are instances of the given type. */
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

  /** Matches an import file against the search text (substring of file name, index name, author, or tags); supports the {@code fav} and {@code tag:} prefixes. */
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

  // ---- Selection ----

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
    if (building.structureEntry() != null) {
      try {
        byte[] bytes = PackReader.readEntryBytes(building.zipPath(), building.structureEntry());
        ParsedStructure parsed = ParsedStructure.parse(bytes, StructureFormat.VANILLA_NBT);
        info = parsed.info();
        structure = parsed.structure();
      } catch (IOException | RuntimeException e) {
        I18nLog.warn(LOGGER, e, "buildpack.log.structure_parse_failed", building.structureEntry());
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
    if (!model.author.isBlank()) {
      return;
    }
    // Outside a world (title-screen pack development) fall back to the launcher account name.
    model.author = minecraft.player != null
        ? minecraft.player.getGameProfile().getName()
        : minecraft.getUser().getName();
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
    setActive(installButton, canInstall && !busy);
    setActive(uninstallButton, canUninstall && !busy);
    setActive(deleteButton, !busy
        && (selected instanceof ImportFile || !checkedOfType(ImportFile.class).isEmpty()));
    setActive(exportButton, !busy && currentTab == SourceTab.INSTALLED
        && installed.stream().anyMatch(building -> matches(building.name())));
    setActive(batchButton, !busy && currentTab == SourceTab.IMPORT
        && importFiles.stream().anyMatch(file -> matches(file.fileName())));
    setActive(activateButton, selected instanceof PackArchive && !busy);
    if (activateButton != null) {
      boolean active = selected instanceof PackArchive pack
          && ActivePackProvider.isActive(pack.manifest().id());
      activateButton.setMessage(Component.translatable(
          active ? "buildpack.action.deactivate" : "buildpack.action.activate"));
    }
  }

  private static void setActive(AbstractWidget widget, boolean active) {
    if (widget != null) {
      widget.active = active;
    }
  }

  // ---- Actions ----

  private void runInstall() {
    if (busy) {
      return;
    }
    if (selected instanceof ImportFile file) {
      // Snapshot the live form model: the installer writes the computed size back into it, and the
      // user may keep editing the fields while the background thread runs.
      BuildingMetadata meta = form.model().copy();
      boolean overwrite = form.overwrite();
      runAsyncInstall(() -> BuildingInstaller.install(file, meta, overwrite));
    } else if (selected instanceof PackArchive pack) {
      // Install and activate are mutually exclusive; switching to file-install drops the virtual one.
      if (ActivePackProvider.isActive(pack.manifest().id())) {
        PackActivationService.deactivate(pack.manifest().id());
      }
      registry.reload();
      runAsyncInstall(() -> PackInstaller.installPack(pack, registry));
    } else if (selected instanceof PackBuildingSelection selection) {
      runAsyncInstall(() -> PackInstaller.installSingle(selection.pack(), selection.entry()));
    }
  }

  /**
   * Runs a heavy convert/install off the render thread (DataFixer upgrades and large-structure
   * conversion would otherwise freeze the game), then applies the result and refreshes back on the
   * main thread. Re-entry is blocked by {@link #busy}, which also disables the action buttons.
   */
  private void runAsyncInstall(Supplier<BuildingInstaller.InstallResult> work) {
    busy = true;
    updateActionButtons();
    setMessage(Component.translatable("buildpack.status.installing"), false);
    CompletableFuture.supplyAsync(work).whenComplete((result, error) ->
        Minecraft.getInstance().execute(() -> {
          busy = false;
          if (Minecraft.getInstance().screen != this) {
            return;
          }
          if (error != null) {
            Throwable cause = error.getCause() != null ? error.getCause() : error;
            I18nLog.warn(LOGGER, cause, "buildpack.log.install_failed", "");
            setMessage(Component.translatable(
                "buildpack.msg.parse_failed", LocalizedIOException.messageOf(cause)), true);
          } else {
            showResult(result.messages(), !result.ok());
          }
          refresh();
        }));
  }

  /** Converts an import file to vanilla NBT and writes it into Create's schematics directory. */
  private void runExportCreate(ImportFile file) {
    runAsyncCreateExport(() -> {
      byte[] bytes = Files.readAllBytes(file.path());
      StructureMaterializer.Result result =
          StructureMaterializer.toVanilla(bytes, file.format(), new ArrayList<>());
      return CreateSchematics.export(result.nbt(), file.baseName());
    });
  }

  /** Copies an installed building's structure out of its zip package into Create's schematics directory. */
  private void runExportCreate(InstalledBuilding building) {
    runAsyncCreateExport(() -> {
      byte[] bytes = PackReader.readEntryBytes(building.zipPath(), building.structureEntry());
      CompoundTag tag = NbtIo.readCompressed(new ByteArrayInputStream(bytes),
          NbtAccounter.create(BuildPack.MAX_STRUCTURE_NBT_BYTES));
      return CreateSchematics.export(tag, building.baseName());
    });
  }

  /** Runs a Create-schematic export off the render thread and reports the created file. */
  private void runAsyncCreateExport(Callable<Path> work) {
    if (busy) {
      return;
    }
    busy = true;
    updateActionButtons();
    setMessage(Component.translatable("buildpack.status.exporting"), false);
    CompletableFuture.supplyAsync(() -> {
      try {
        return work.call();
      } catch (Exception e) {
        throw new java.util.concurrent.CompletionException(e);
      }
    }).whenComplete((target, error) ->
        Minecraft.getInstance().execute(() -> {
          busy = false;
          if (Minecraft.getInstance().screen != this) {
            return;
          }
          if (error != null) {
            Throwable cause = error.getCause() != null ? error.getCause() : error;
            I18nLog.warn(LOGGER, cause, "buildpack.log.export_failed");
            setMessage(Component.translatable(
                "buildpack.msg.parse_failed", LocalizedIOException.messageOf(cause)), true);
            updateActionButtons();
          } else {
            setMessage(Component.translatable("buildpack.msg.create_exported",
                "schematics/" + target.getFileName()), false);
            // The schematics directory is also an import source; show the new file.
            refresh();
          }
        }));
  }

  private void runBatchInstall() {
    if (busy) {
      return;
    }
    List<ImportFile> checked = checkedOfType(ImportFile.class);
    List<ImportFile> files = checked.isEmpty() ? filteredImportFiles() : checked;
    if (files.isEmpty()) {
      return;
    }
    List<ImportFile> snapshot = List.copyOf(files);
    BuildingCategory category = form.model().category;
    // Resolve the author on the main thread; the background thread must not touch Minecraft.player.
    String author = Minecraft.getInstance().player != null
        ? Minecraft.getInstance().player.getGameProfile().getName() : "";
    busy = true;
    updateActionButtons();
    setMessage(Component.translatable("buildpack.status.installing"), false);
    CompletableFuture.supplyAsync(() -> batchInstall(snapshot, category, author))
        .whenComplete((counts, error) -> Minecraft.getInstance().execute(() -> {
          busy = false;
          if (Minecraft.getInstance().screen != this) {
            return;
          }
          if (error != null) {
            Throwable cause = error.getCause() != null ? error.getCause() : error;
            I18nLog.warn(LOGGER, cause, "buildpack.log.batch_failed", "");
            setMessage(Component.translatable(
                "buildpack.msg.parse_failed", LocalizedIOException.messageOf(cause)), true);
          } else {
            setMessage(Component.translatable(
                "buildpack.msg.batch_done", counts[0], counts[1]), counts[1] > 0);
          }
          refresh();
        }));
  }

  /** Installs every file into {@code category} on a background thread; returns {@code {succeeded, failed}}. */
  private static int[] batchInstall(List<ImportFile> files, BuildingCategory category, String author) {
    int ok = 0;
    int failed = 0;
    for (ImportFile file : files) {
      try {
        BuildingMetadata meta = new BuildingMetadata();
        meta.prefill(ParsedStructure.parse(file.path(), file.format()).info(), file.baseName());
        if (meta.author.isBlank()) {
          meta.author = author;
        }
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
    return new int[] {ok, failed};
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

  /** Batch-deletes the checked import files (requires a second click to confirm). */
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
    if (busy) {
      return;
    }
    if (selected instanceof InstalledBuilding building && building.managed()) {
      registry.reload();
      BuildingInstaller.uninstall(building, registry);
      setMessage(Component.translatable("buildpack.msg.uninstalled", building.name()), false);
      refresh();
    } else if (selected instanceof PackArchive pack) {
      registry.reload();
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

  /** Toggles activation of the selected pack (main action button). */
  private void runActivateToggle() {
    if (selected instanceof PackArchive pack) {
      if (ActivePackProvider.isActive(pack.manifest().id())) {
        deactivatePack(pack);
      } else {
        activatePack(pack);
      }
    }
  }

  /** Activates a pack (convert into cache + serve to SimuKraft virtually) off the render thread. */
  private void activatePack(PackArchive pack) {
    if (busy) {
      return;
    }
    // Activating only makes sense where this game instance runs the world; on a remote server the
    // admin must use /buildpack activate (the local conversion would not reach the server).
    if (Minecraft.getInstance().level != null
        && Minecraft.getInstance().getSingleplayerServer() == null) {
      setMessage(Component.translatable("buildpack.msg.activate_remote"), true);
      return;
    }
    busy = true;
    updateActionButtons();
    setMessage(Component.translatable("buildpack.status.activating"), false);
    CompletableFuture.supplyAsync(() -> {
      List<Component> messages = new ArrayList<>();
      try {
        // Mutually exclusive with file-install: drop an existing install so buildings don't double up.
        registry.reload();
        if (registry.find(pack.manifest().id()).isPresent()
            && PackInstaller.uninstallPack(pack.manifest().id(), registry)) {
          messages.add(Component.translatable("buildpack.msg.replaced_install"));
        }
        PackActivationService.activate(pack, messages);
      } catch (IOException | RuntimeException e) {
        I18nLog.warn(LOGGER, e, "buildpack.log.activate_failed", pack.manifest().id());
        messages.add(Component.translatable(
            "buildpack.msg.parse_failed", LocalizedIOException.messageOf(e)));
      }
      return messages;
    }).whenComplete((messages, error) -> Minecraft.getInstance().execute(() -> {
      busy = false;
      if (Minecraft.getInstance().screen != this) {
        return;
      }
      if (error != null) {
        Throwable cause = error.getCause() != null ? error.getCause() : error;
        setMessage(Component.translatable(
            "buildpack.msg.parse_failed", LocalizedIOException.messageOf(cause)), true);
      } else {
        messages.add(0,
            Component.translatable("buildpack.msg.pack_activated", pack.manifest().name()));
        showResult(messages, false);
      }
      refresh();
    }));
  }

  private void deactivatePack(PackArchive pack) {
    PackActivationService.deactivate(pack.manifest().id());
    setMessage(Component.translatable("buildpack.msg.pack_deactivated", pack.manifest().name()), false);
    refresh();
  }

  private void runCaptureSelection() {
    WorldSelection.CaptureResult result = WorldSelection.capture();
    setMessage(result.message(), !result.ok());
    if (result.ok()) {
      refresh();
    }
  }

  /** "Dedupe" callback (requires a second click to confirm): deletes redundant copies with identical content, keeping one per group. */
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

  // ---- Right-click context menu ----

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
      if (CreateSchematics.available()) {
        items.add(new ContextMenu.Item(Component.translatable("buildpack.menu.export_create"),
            () -> runExportCreate(file)));
      }
      items.add(new ContextMenu.Item(
          Component.translatable("buildpack.menu.delete"), () -> deleteImportFileNow(file)));
    } else if (content instanceof InstalledBuilding building) {
      if (building.managed()) {
        items.add(new ContextMenu.Item(Component.translatable("buildpack.menu.recategorize"),
            () -> openCategoryMenu(building, mouseX, mouseY)));
      }
      // Commercial/industrial definition: editable in managed zips, viewable when one exists.
      if (building.managed() || building.hasJson()) {
        items.add(new ContextMenu.Item(Component.translatable("buildpack.menu.definition"),
            () -> DefinitionEditorScreen.open(building, this::refresh)));
      }
      items.add(new ContextMenu.Item(Component.translatable("buildpack.menu.reveal"),
          () -> reveal(building.zipPath())));
      if (CreateSchematics.available() && building.hasStructure()) {
        items.add(new ContextMenu.Item(Component.translatable("buildpack.menu.export_create"),
            () -> runExportCreate(building)));
      }
      if (building.managed()) {
        items.add(new ContextMenu.Item(Component.translatable("buildpack.menu.uninstall"), () -> {
          selected = building;
          runUninstall();
        }));
      }
    } else if (content instanceof PackArchive pack) {
      boolean active = ActivePackProvider.isActive(pack.manifest().id());
      items.add(new ContextMenu.Item(
          Component.translatable(active ? "buildpack.menu.deactivate" : "buildpack.menu.activate"),
          () -> {
            if (active) {
              deactivatePack(pack);
            } else {
              activatePack(pack);
            }
          }));
    }
    contextMenu = items.isEmpty() ? null : new ContextMenu(mouseX, mouseY, items);
  }

  private void openCategoryMenu(InstalledBuilding building, int mouseX, int mouseY) {
    List<ContextMenu.Item> items = new ArrayList<>();
    for (BuildingCategory category : BuildingCategory.values()) {
      items.add(new ContextMenu.Item(category.displayName(), () -> {
        registry.reload();
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

  // ---- File drag-and-drop ----

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
    return FileNames.unique(dir, FileNames.baseName(fileName), FileNames.extension(fileName));
  }

  // ---- Input (coordinates are converted from real pixels to the uiScale logical space) ----

  @Override
  public boolean mouseClicked(double mouseX, double mouseY, int button) {
    double lx = mouseX / uiScale;
    double ly = mouseY / uiScale;
    if (contextMenu != null) {
      ContextMenu menu = contextMenu;
      contextMenu = null;
      menu.click(lx, ly);
      return true;
    }
    return super.mouseClicked(lx, ly, button);
  }

  @Override
  public boolean mouseReleased(double mouseX, double mouseY, int button) {
    return super.mouseReleased(mouseX / uiScale, mouseY / uiScale, button);
  }

  @Override
  public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
    return super.mouseDragged(
        mouseX / uiScale, mouseY / uiScale, button, dragX / uiScale, dragY / uiScale);
  }

  @Override
  public void mouseMoved(double mouseX, double mouseY) {
    super.mouseMoved(mouseX / uiScale, mouseY / uiScale);
  }

  @Override
  public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
    // Ctrl + wheel zooms the manager; otherwise forward in logical space (e.g. tree scrolling).
    if (hasControlDown()) {
      changeScale(scrollY > 0 ? UiScale.STEP : -UiScale.STEP);
      return true;
    }
    return super.mouseScrolled(mouseX / uiScale, mouseY / uiScale, scrollX, scrollY);
  }

  /** Adjusts and persists the UI scale, then re-lays out all widgets for the new scale. */
  private void changeScale(float delta) {
    float applied = UiScale.setPreference(uiScale + delta);
    if (applied != uiScale) {
      uiScale = applied;
      rebuildWidgets();
      setMessage(Component.translatable(
          "buildpack.msg.ui_scale", Math.round(applied * 100)), false);
    }
  }

  @Override
  public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
    if (contextMenu != null && keyCode == 256) {
      contextMenu = null;
      return true;
    }
    // Ctrl+V (when no text field is focused): import the file path from the clipboard.
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

  // ---- Import directory watcher (auto-refresh when files are added externally) ----

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
        // Failure to close the watcher requires no action.
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
    minecraft.setScreen(parent);
  }
}
