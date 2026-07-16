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
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Editor for a building's SimuKraft commercial/industrial definition — the {@code <base>.json}
 * entry next to the .sk inside its zip package (paired by SimuKraft via the sibling-name rule).
 *
 * <p>Offers doc-accurate starter templates, a structural validator
 * ({@link SimukraftDefinitions#validate}), and saves straight into the managed zip followed by a
 * SimuKraft catalog reload so edits take effect without a restart. Only JSON syntax errors block
 * saving — structural warnings are shown but a work-in-progress definition can still be kept.
 * Buildings in non-managed packages open read-only (copyable reference).
 */
public final class DefinitionEditorScreen extends Screen {

  private static final Logger LOGGER = LoggerFactory.getLogger(DefinitionEditorScreen.class);
  private static final Pattern SIZE_PATTERN =
      Pattern.compile("(\\d+)\\s*[xX×]\\s*(\\d+)\\s*[xX×]\\s*(\\d+)");
  private static final int MARGIN = 12;
  private static final int STATUS_ROWS = 5;
  private static final int ROW_H = 10;
  private static final int BUTTON_H = 18;
  private static final int MAX_LENGTH = 262144;

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
  private MultiLineEditBox editor;
  private ThemedButton deleteButton;

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

  @Override
  protected void init() {
    int editorY = MARGIN + 20;
    int buttonsY = height - MARGIN - BUTTON_H;
    int statusY = buttonsY - 4 - STATUS_ROWS * ROW_H;
    int editorH = Math.max(40, statusY - 4 - editorY);
    int w = width - MARGIN * 2;

    editor = new MultiLineEditBox(font, MARGIN, editorY, w, editorH,
        Component.translatable("buildpack.definition.hint"), Component.empty());
    editor.setCharacterLimit(MAX_LENGTH);
    editor.setValue(text);
    editor.setValueListener(this::onTextChanged);
    addRenderableWidget(editor);
    setInitialFocus(editor);

    if (editable) {
      int gap = 4;
      int bw = (w - gap * 5) / 6;
      int x = MARGIN;
      addRenderableWidget(new ThemedButton(x, buttonsY, bw, BUTTON_H,
          Component.translatable("buildpack.definition.template.commercial"),
          () -> applyTemplate(Kind.COMMERCIAL)));
      x += bw + gap;
      addRenderableWidget(new ThemedButton(x, buttonsY, bw, BUTTON_H,
          Component.translatable("buildpack.definition.template.industrial"),
          () -> applyTemplate(Kind.INDUSTRIAL)));
      x += bw + gap;
      addRenderableWidget(new ThemedButton(x, buttonsY, bw, BUTTON_H,
          Component.translatable("buildpack.definition.validate"), this::runValidate));
      x += bw + gap;
      addRenderableWidget(new ThemedButton(x, buttonsY, bw, BUTTON_H,
          Component.translatable("buildpack.definition.save"), this::save));
      x += bw + gap;
      deleteButton = new ThemedButton(x, buttonsY, bw, BUTTON_H,
          Component.translatable("buildpack.definition.delete"), this::deleteDefinition);
      deleteButton.visible = exists;
      addRenderableWidget(deleteButton);
      x += bw + gap;
      addRenderableWidget(new ThemedButton(x, buttonsY, bw, BUTTON_H,
          Component.translatable("buildpack.prompt.cancel"), this::onClose));
    } else {
      int bw = 100;
      addRenderableWidget(new ThemedButton(MARGIN, buttonsY, bw, BUTTON_H,
          Component.translatable("buildpack.definition.validate"), this::runValidate));
      addRenderableWidget(new ThemedButton(MARGIN + bw + 4, buttonsY, bw, BUTTON_H,
          Component.translatable("buildpack.prompt.cancel"), this::onClose));
    }
  }

  private void onTextChanged(String value) {
    if (value.equals(text)) {
      return;
    }
    text = value;
    dirty = true;
    pendingTemplate = null;
    pendingDelete = false;
    pendingDiscard = false;
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
    String name = building.name().isBlank() ? building.baseName() : building.name();
    String jobType = building.skFields().getOrDefault("job_type", "");
    String template = kind == Kind.COMMERCIAL
        ? SimukraftDefinitions.commercialTemplate(building.baseName(), name, jobType)
        : SimukraftDefinitions.industrialTemplate(building.baseName(), name, jobType);
    editor.setValue(template);
    pendingTemplate = null;
    setMessage(null, BuildPackTheme.VALUE);
  }

  private void runValidate() {
    pendingTemplate = null;
    pendingDelete = false;
    pendingDiscard = false;
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
    pendingTemplate = null;
    pendingDelete = false;
    pendingDiscard = false;
    if (!editable) {
      return;
    }
    if (text.isBlank()) {
      setMessage(Component.translatable("buildpack.definition.msg.empty"), BuildPackTheme.MESSAGE_WARN);
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
    setMessage(Component.translatable("buildpack.definition.msg.deleted"), BuildPackTheme.MESSAGE_OK);
    onChanged.run();
  }

  private void setMessage(Component newMessage, int color) {
    message = newMessage;
    messageColor = color;
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
    super.render(g, mouseX, mouseY, partialTick);
    g.drawString(font, title, MARGIN, MARGIN - 4, BuildPackTheme.TITLE, true);
    String path = building.zipFileName() + " / " + entryPath;
    Component right = editable
        ? Component.literal(path)
        : Component.translatable("buildpack.definition.readonly");
    g.drawString(font, right, width - MARGIN - font.width(right), MARGIN - 4,
        editable ? BuildPackTheme.LABEL : BuildPackTheme.MESSAGE_WARN, true);

    int statusY = height - MARGIN - BUTTON_H - 4 - STATUS_ROWS * ROW_H;
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
