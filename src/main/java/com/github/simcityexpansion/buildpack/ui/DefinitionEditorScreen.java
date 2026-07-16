package com.github.simcityexpansion.buildpack.ui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.github.simcityexpansion.buildpack.I18nLog;
import com.github.simcityexpansion.buildpack.LocalizedIOException;
import com.github.simcityexpansion.buildpack.install.SimukraftZips;
import com.github.simcityexpansion.buildpack.integration.SimukraftBridge;
import com.github.simcityexpansion.buildpack.integration.SimukraftDefinitions;
import com.github.simcityexpansion.buildpack.integration.SimukraftDefinitions.Issue;
import com.github.simcityexpansion.buildpack.integration.SimukraftDefinitions.Kind;
import com.github.simcityexpansion.buildpack.model.InstalledBuilding;
import com.github.simcityexpansion.buildpack.ui.component.TreeView;
import com.github.simcityexpansion.buildpack.ui.definition.JsonEditBox;
import com.github.simcityexpansion.buildpack.ui.definition.VisualDefinitionEditor;
import com.github.simcityexpansion.buildpack.ui.definition.VisualDefinitionEditor.DefinitionNode;
import com.github.simcityexpansion.buildpack.ui.tree.TreeNode;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Editor for a building's SimuKraft commercial/industrial definition — the {@code <base>.json}
 * entry next to the .sk inside its zip package (paired by SimuKraft via the sibling-name rule).
 *
 * <p>Two editing modes share one document. The <b>visual</b> mode is a low-code editor
 * ({@link VisualDefinitionEditor}): a structure tree on the left, a small form for the selected
 * entry on the right, plus create buttons when the building has no definition yet. The
 * <b>JSON</b> mode is a syntax-highlighted text editor ({@link JsonEditBox}) with doc-accurate
 * starter templates for full control over advanced fields. Both run the same structural validator
 * ({@link SimukraftDefinitions#validate}); saving writes into the managed zip and triggers a
 * SimuKraft catalog reload so edits take effect without a restart. Only JSON syntax errors block
 * saving. Buildings in non-managed packages open read-only (JSON mode, copyable reference).
 */
public final class DefinitionEditorScreen extends Screen
    implements VisualDefinitionEditor.Host {

  private static final Logger LOGGER = LoggerFactory.getLogger(DefinitionEditorScreen.class);
  private static final Gson GSON =
      new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
  private static final Pattern SIZE_PATTERN =
      Pattern.compile("(\\d+)\\s*[xX×]\\s*(\\d+)\\s*[xX×]\\s*(\\d+)");
  private static final int MARGIN = 12;
  private static final int GAP = 4;
  private static final int STATUS_ROWS = 5;
  private static final int ROW_H = 10;
  private static final int BUTTON_H = 18;
  private static final int MODE_W = 56;
  private static final int MAX_LENGTH = 262144;

  private enum Mode {
    VISUAL,
    JSON
  }

  private final Screen parent;
  private final InstalledBuilding building;
  private final Runnable onChanged;
  private final boolean editable;
  private final String entryPath;
  private final int sizeX;
  private final int sizeY;
  private final int sizeZ;

  private String text;
  private boolean exists;
  private boolean dirty;
  private Mode mode;

  private final VisualDefinitionEditor visual = new VisualDefinitionEditor(this);
  private final TreeView treeView = new TreeView(0, 0, 0, 0, this::onTreeSelect);
  @Nullable
  private DefinitionNode selectedNode;
  @Nullable
  private JsonEditBox jsonBox;
  @Nullable
  private JsonEditBox.ViewState jsonView;
  @Nullable
  private ContextMenu contextMenu;
  private ThemedButton deleteButton;

  // Layout (computed in init, shared with render).
  private int contentY;
  private int contentBottom;
  private int treeW;

  private List<Issue> issues = List.of();
  private Component message;
  private int messageColor = BuildPackTheme.VALUE;
  private Kind pendingTemplate;
  private boolean pendingDelete;
  private boolean pendingDiscard;

  private DefinitionEditorScreen(
      Screen parent, InstalledBuilding building, Runnable onChanged, String initial,
      boolean exists) {
    super(Component.translatable("buildpack.definition.title", building.name()));
    this.parent = parent;
    this.building = building;
    this.onChanged = onChanged;
    this.editable = building.managed();
    this.entryPath = SimukraftZips.entryPath(building.category(), building.baseName() + ".json");
    this.text = initial;
    this.exists = exists;
    int[] size = parseSize(building.skFields().get("size"));
    this.sizeX = size[0];
    this.sizeY = size[1];
    this.sizeZ = size[2];
    treeView.setShowCheckboxes(false);
    this.mode = initialMode();
  }

  /** Visual mode whenever the document allows it: blank (create flow) or parseable with a kind. */
  private Mode initialMode() {
    if (!editable) {
      return Mode.JSON;
    }
    if (text.isBlank()) {
      return Mode.VISUAL;
    }
    return tryBindVisual() ? Mode.VISUAL : Mode.JSON;
  }

  /**
   * Opens the editor for the building's definition entry, loading the current JSON when present.
   * {@code onChanged} runs after every successful save or delete (e.g. to refresh the list).
   */
  public static void open(InstalledBuilding building, Runnable onChanged) {
    Minecraft mc = Minecraft.getInstance();
    String initial = "";
    boolean exists = false;
    try {
      byte[] bytes = SimukraftZips.readEntry(building.zipPath(),
          SimukraftZips.entryPath(building.category(), building.baseName() + ".json"))
          .orElse(null);
      if (bytes != null) {
        initial = new String(bytes, StandardCharsets.UTF_8);
        exists = true;
      }
    } catch (IOException e) {
      I18nLog.warn(LOGGER, e, "buildpack.log.definition_read_failed", building.zipPath());
    }
    mc.setScreen(new DefinitionEditorScreen(mc.screen, building, onChanged, initial, exists));
  }

  /** Parses the .sk size field ("X x Y x Z") for coordinate bounds checks; zeros when unknown. */
  private static int[] parseSize(String sizeField) {
    if (sizeField != null) {
      Matcher matcher = SIZE_PATTERN.matcher(sizeField);
      if (matcher.find()) {
        try {
          return new int[] {Integer.parseInt(matcher.group(1)),
              Integer.parseInt(matcher.group(2)), Integer.parseInt(matcher.group(3))};
        } catch (NumberFormatException ignored) {
          // Oversized numbers in a hand-edited .sk just disable the bounds check.
        }
      }
    }
    return new int[] {0, 0, 0};
  }

  // ---- Layout & widgets ----

  @Override
  protected void init() {
    contentY = MARGIN + 20;
    int buttonsY = height - MARGIN - BUTTON_H;
    int statusY = buttonsY - GAP - STATUS_ROWS * ROW_H;
    contentBottom = statusY - GAP;
    int w = width - MARGIN * 2;
    treeW = Math.max(120, Math.min(200, (int) (w * 0.32f)));

    if (editable) {
      int modeY = MARGIN - 8;
      addRenderableWidget(new ThemedButton(width - MARGIN - MODE_W * 2 - GAP, modeY, MODE_W, 14,
          Component.translatable("buildpack.definition.mode.visual"), () -> setMode(Mode.VISUAL))
          .selected(() -> mode == Mode.VISUAL));
      addRenderableWidget(new ThemedButton(width - MARGIN - MODE_W, modeY, MODE_W, 14,
          Component.translatable("buildpack.definition.mode.json"), () -> setMode(Mode.JSON))
          .selected(() -> mode == Mode.JSON));
    }

    if (mode == Mode.JSON) {
      initJsonMode(w);
    } else {
      initVisualMode();
    }
    initBottomButtons(buttonsY, w);
  }

  private void initJsonMode(int w) {
    jsonBox = new JsonEditBox(font, MARGIN, contentY, w, contentBottom - contentY,
        Component.translatable("buildpack.definition.hint"));
    jsonBox.setCharacterLimit(MAX_LENGTH);
    jsonBox.setValue(text);
    if (jsonView != null) {
      jsonBox.restoreViewState(jsonView);
    }
    jsonBox.setValueListener(this::onTextChanged);
    addRenderableWidget(jsonBox);
    setInitialFocus(jsonBox);
  }

  private void initVisualMode() {
    jsonBox = null;
    if (visual.root() == null) {
      int bw = 150;
      int cx = width / 2;
      int by = contentY + (contentBottom - contentY) / 2 - BUTTON_H;
      addRenderableWidget(new ThemedButton(cx - bw - GAP, by, bw, BUTTON_H,
          Component.translatable("buildpack.definition.visual.create_commercial"),
          () -> createFromTemplate(Kind.COMMERCIAL)));
      addRenderableWidget(new ThemedButton(cx + GAP, by, bw, BUTTON_H,
          Component.translatable("buildpack.definition.visual.create_industrial"),
          () -> createFromTemplate(Kind.INDUSTRIAL)));
      return;
    }
    treeView.setX(MARGIN + 2);
    treeView.setY(contentY + 2);
    treeView.setWidth(treeW - 4);
    treeView.setHeight(contentBottom - contentY - 4);
    addRenderableWidget(treeView);
    if (selectedNode != null) {
      visual.buildForm(selectedNode, font, formX(), contentY + 6, formW(),
          this::addRenderableWidget);
    }
  }

  private int formX() {
    return MARGIN + treeW + GAP + 6;
  }

  private int formW() {
    return width - MARGIN - 6 - formX();
  }

  private void initBottomButtons(int buttonsY, int w) {
    if (!editable) {
      int bw = 100;
      addRenderableWidget(new ThemedButton(MARGIN, buttonsY, bw, BUTTON_H,
          Component.translatable("buildpack.definition.validate"), this::runValidate));
      addRenderableWidget(new ThemedButton(MARGIN + bw + GAP, buttonsY, bw, BUTTON_H,
          Component.translatable("buildpack.prompt.cancel"), this::onClose));
      return;
    }
    boolean templates = mode == Mode.JSON;
    int count = templates ? 6 : 4;
    int bw = (w - GAP * (count - 1)) / count;
    int x = MARGIN;
    if (templates) {
      addRenderableWidget(new ThemedButton(x, buttonsY, bw, BUTTON_H,
          Component.translatable("buildpack.definition.template.commercial"),
          () -> applyTemplate(Kind.COMMERCIAL)));
      x += bw + GAP;
      addRenderableWidget(new ThemedButton(x, buttonsY, bw, BUTTON_H,
          Component.translatable("buildpack.definition.template.industrial"),
          () -> applyTemplate(Kind.INDUSTRIAL)));
      x += bw + GAP;
    }
    addRenderableWidget(new ThemedButton(x, buttonsY, bw, BUTTON_H,
        Component.translatable("buildpack.definition.validate"), this::runValidate));
    x += bw + GAP;
    addRenderableWidget(new ThemedButton(x, buttonsY, bw, BUTTON_H,
        Component.translatable("buildpack.definition.save"), this::save));
    x += bw + GAP;
    deleteButton = new ThemedButton(x, buttonsY, bw, BUTTON_H,
        Component.translatable("buildpack.definition.delete"), this::deleteDefinition);
    deleteButton.visible = exists;
    addRenderableWidget(deleteButton);
    x += bw + GAP;
    addRenderableWidget(new ThemedButton(x, buttonsY, bw, BUTTON_H,
        Component.translatable("buildpack.prompt.cancel"), this::onClose));
  }

  // ---- Mode switching & visual host ----

  private void setMode(Mode target) {
    if (mode == target || !editable) {
      return;
    }
    clearPendings();
    if (target == Mode.JSON) {
      syncTextFromVisual();
      jsonView = null;
      mode = Mode.JSON;
      rebuildWidgets();
      return;
    }
    if (text.isBlank()) {
      visual.bind(null, Kind.COMMERCIAL);
      mode = Mode.VISUAL;
      rebuildWidgets();
      return;
    }
    if (!tryBindVisual()) {
      setMessage(Component.translatable(SimukraftDefinitions.parses(text)
          ? "buildpack.definition.msg.unknown_kind_visual"
          : "buildpack.definition.msg.cannot_visual"), BuildPackTheme.MESSAGE_ERROR);
      return;
    }
    mode = Mode.VISUAL;
    rebuildWidgets();
  }

  /** Parses {@link #text} into the visual editor; true on success (object root + known kind). */
  private boolean tryBindVisual() {
    JsonObject root;
    try {
      JsonElement element = JsonParser.parseString(text);
      if (!element.isJsonObject()) {
        return false;
      }
      root = element.getAsJsonObject();
    } catch (RuntimeException e) {
      return false;
    }
    Kind kind = SimukraftDefinitions.detectRoot(root);
    if (kind == null) {
      return false;
    }
    visual.bind(root, kind);
    selectedNode = null;
    refreshTree(root);
    return true;
  }

  /** Generates a starter document in visual mode ("create" flow for missing definitions). */
  private void createFromTemplate(Kind kind) {
    text = templateFor(kind);
    dirty = true;
    if (tryBindVisual()) {
      rebuildWidgets();
    }
  }

  private String templateFor(Kind kind) {
    String name = building.name().isBlank() ? building.baseName() : building.name();
    String jobType = building.skFields().getOrDefault("job_type", "");
    return kind == Kind.COMMERCIAL
        ? SimukraftDefinitions.commercialTemplate(building.baseName(), name, jobType)
        : SimukraftDefinitions.industrialTemplate(building.baseName(), name, jobType);
  }

  private void syncTextFromVisual() {
    if (mode == Mode.VISUAL && visual.root() != null) {
      text = GSON.toJson(visual.root());
    }
  }

  private void onTreeSelect(Object content) {
    selectedNode = content instanceof DefinitionNode node ? node : null;
    clearPendings();
    rebuildWidgets();
  }

  private void refreshTree(@Nullable JsonElement select) {
    TreeNode<String, Object> root = visual.buildTree();
    treeView.setRoot(root);
    treeView.expandAll();
    selectedNode = null;
    if (select != null) {
      TreeNode<String, Object> node = visual.nodeFor(select);
      if (node != null && node.getContent() instanceof DefinitionNode definitionNode) {
        treeView.setSelectedNode(node);
        selectedNode = definitionNode;
      }
    }
  }

  @Override
  public void markDirty() {
    dirty = true;
    clearPendings();
    issues = List.of();
    message = null;
  }

  @Override
  public void structureChanged(@Nullable JsonElement select) {
    dirty = true;
    refreshTree(select);
    rebuildWidgets();
  }

  @Override
  public void openMenu(ContextMenu menu) {
    contextMenu = menu;
  }

  private void clearPendings() {
    pendingTemplate = null;
    pendingDelete = false;
    pendingDiscard = false;
  }

  // ---- Text editing (JSON mode) ----

  private void onTextChanged(String value) {
    if (value.equals(text)) {
      return;
    }
    text = value;
    dirty = true;
    clearPendings();
    issues = List.of();
    message = null;
  }

  /** Inserts a starter template; replacing non-blank text requires a second click to confirm. */
  private void applyTemplate(Kind kind) {
    pendingDelete = false;
    pendingDiscard = false;
    if (!text.isBlank() && pendingTemplate != kind) {
      pendingTemplate = kind;
      setMessage(Component.translatable("buildpack.definition.msg.confirm_template"),
          BuildPackTheme.MESSAGE_WARN);
      return;
    }
    if (jsonBox != null) {
      jsonBox.setValue(templateFor(kind));
    }
    pendingTemplate = null;
    setMessage(null, BuildPackTheme.VALUE);
  }

  // ---- Validate / save / delete ----

  private void runValidate() {
    clearPendings();
    syncTextFromVisual();
    issues = SimukraftDefinitions.validate(text, sizeX, sizeY, sizeZ);
    if (issues.isEmpty()) {
      Kind kind = SimukraftDefinitions.detect(text);
      setMessage(Component.translatable("buildpack.definition.msg.valid",
          kind == null ? "?" : kind.displayName()), BuildPackTheme.MESSAGE_OK);
    } else {
      long errors = issues.stream().filter(Issue::error).count();
      setMessage(Component.translatable("buildpack.definition.msg.issues", issues.size(), errors),
          errors > 0 ? BuildPackTheme.MESSAGE_ERROR : BuildPackTheme.MESSAGE_WARN);
    }
  }

  private void save() {
    clearPendings();
    if (!editable) {
      return;
    }
    syncTextFromVisual();
    if (text.isBlank()) {
      setMessage(Component.translatable("buildpack.definition.msg.empty"),
          BuildPackTheme.MESSAGE_WARN);
      return;
    }
    issues = SimukraftDefinitions.validate(text, sizeX, sizeY, sizeZ);
    if (!SimukraftDefinitions.parses(text)) {
      setMessage(Component.translatable("buildpack.definition.msg.syntax_block"),
          BuildPackTheme.MESSAGE_ERROR);
      return;
    }
    try {
      SimukraftZips.updateZip(building.zipPath(),
          Map.of(entryPath, text.getBytes(StandardCharsets.UTF_8)), List.of());
    } catch (IOException e) {
      I18nLog.warn(LOGGER, e, "buildpack.log.definition_write_failed", building.zipPath());
      setMessage(Component.translatable("buildpack.definition.msg.write_failed",
          LocalizedIOException.messageOf(e)), BuildPackTheme.MESSAGE_ERROR);
      return;
    }
    SimukraftBridge.requestCatalogReload();
    exists = true;
    dirty = false;
    if (deleteButton != null) {
      deleteButton.visible = true;
    }
    if (issues.isEmpty()) {
      setMessage(Component.translatable("buildpack.definition.msg.saved",
          building.baseName() + ".json"), BuildPackTheme.MESSAGE_OK);
    } else {
      setMessage(Component.translatable("buildpack.definition.msg.saved_issues", issues.size()),
          BuildPackTheme.MESSAGE_WARN);
    }
    onChanged.run();
  }

  /** Removes the definition entry from the zip (second click confirms); keeps the editor text. */
  private void deleteDefinition() {
    pendingTemplate = null;
    pendingDiscard = false;
    if (!editable || !exists) {
      return;
    }
    if (!pendingDelete) {
      pendingDelete = true;
      setMessage(Component.translatable("buildpack.definition.msg.confirm_delete"),
          BuildPackTheme.MESSAGE_WARN);
      return;
    }
    pendingDelete = false;
    try {
      SimukraftZips.updateZip(building.zipPath(), Map.of(), List.of(entryPath));
    } catch (IOException e) {
      I18nLog.warn(LOGGER, e, "buildpack.log.definition_write_failed", building.zipPath());
      setMessage(Component.translatable("buildpack.definition.msg.write_failed",
          LocalizedIOException.messageOf(e)), BuildPackTheme.MESSAGE_ERROR);
      return;
    }
    SimukraftBridge.requestCatalogReload();
    exists = false;
    dirty = !text.isBlank();
    if (deleteButton != null) {
      deleteButton.visible = false;
    }
    setMessage(Component.translatable("buildpack.definition.msg.deleted"),
        BuildPackTheme.MESSAGE_OK);
    onChanged.run();
  }

  private void setMessage(Component newMessage, int color) {
    message = newMessage;
    messageColor = color;
  }

  // ---- Input & rendering ----

  @Override
  public boolean mouseClicked(double mouseX, double mouseY, int button) {
    if (contextMenu != null) {
      contextMenu.click(mouseX, mouseY);
      contextMenu = null;
      return true;
    }
    return super.mouseClicked(mouseX, mouseY, button);
  }

  @Override
  public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
    if (editable && keyCode == 83 && Screen.hasControlDown()) {
      save();
      return true;
    }
    return super.keyPressed(keyCode, scanCode, modifiers);
  }

  @Override
  public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
    g.fill(0, 0, width, height, 0xF2101010);
  }

  @Override
  public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
    if (mode == Mode.VISUAL && visual.root() != null) {
      BuildPackTheme.panel(g, MARGIN, contentY, treeW, contentBottom - contentY);
      BuildPackTheme.panel(g, MARGIN + treeW + GAP, contentY,
          width - MARGIN * 2 - treeW - GAP, contentBottom - contentY);
    }
    super.render(g, mouseX, mouseY, partialTick);
    g.drawString(font, title, MARGIN, MARGIN - 4, BuildPackTheme.TITLE, true);
    if (!editable) {
      Component readonly = Component.translatable("buildpack.definition.readonly");
      g.drawString(font, readonly, width - MARGIN - font.width(readonly), MARGIN - 4,
          BuildPackTheme.MESSAGE_WARN, true);
    }

    if (mode == Mode.VISUAL && visual.root() != null) {
      if (selectedNode != null) {
        visual.renderLabels(g);
      } else {
        g.drawString(font, Component.translatable("buildpack.definition.visual.select_hint"),
            formX(), contentY + 6, BuildPackTheme.HINT, true);
      }
    } else if (mode == Mode.VISUAL) {
      Component hint = Component.translatable("buildpack.definition.visual.empty");
      g.drawString(font, hint, (width - font.width(hint)) / 2,
          contentY + (contentBottom - contentY) / 2 - BUTTON_H - 14, BuildPackTheme.LABEL, true);
    }

    int statusY = height - MARGIN - BUTTON_H - GAP - STATUS_ROWS * ROW_H;
    int rowY = statusY;
    if (message != null) {
      g.drawString(font, clip(message, width - MARGIN * 2), MARGIN, rowY, messageColor, true);
    }
    rowY += ROW_H;
    int shown = 0;
    for (Issue issue : issues) {
      if (shown >= STATUS_ROWS - 1) {
        break;
      }
      boolean last = shown == STATUS_ROWS - 2 && issues.size() > STATUS_ROWS - 1;
      Component line = last
          ? Component.translatable("buildpack.definition.issue.more",
              issues.size() - (STATUS_ROWS - 2))
          : issue.text();
      int color = last ? BuildPackTheme.LABEL
          : issue.error() ? BuildPackTheme.MESSAGE_ERROR : BuildPackTheme.MESSAGE_WARN;
      g.drawString(font, clip(line, width - MARGIN * 2), MARGIN, rowY, color, true);
      rowY += ROW_H;
      shown++;
    }

    if (contextMenu != null) {
      contextMenu.render(g, mouseX, mouseY);
    }
  }

  /** Truncates a component to one visual line so long issues never overlap the buttons. */
  private Component clip(Component component, int maxWidth) {
    if (font.width(component) <= maxWidth) {
      return component;
    }
    String clipped = font.substrByWidth(component, maxWidth - font.width("…")).getString();
    return Component.literal(clipped + "…");
  }

  @Override
  public void rebuildWidgets() {
    if (jsonBox != null) {
      jsonView = jsonBox.viewState();
    }
    super.rebuildWidgets();
  }

  @Override
  public void onClose() {
    if (editable && dirty && !pendingDiscard) {
      pendingDiscard = true;
      setMessage(Component.translatable("buildpack.definition.msg.confirm_discard"),
          BuildPackTheme.MESSAGE_WARN);
      return;
    }
    minecraft.setScreen(parent);
  }
}
