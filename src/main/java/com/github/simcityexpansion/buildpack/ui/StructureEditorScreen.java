package com.github.simcityexpansion.buildpack.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import com.github.simcityexpansion.buildpack.I18nLog;
import com.github.simcityexpansion.buildpack.LocalizedIOException;
import com.github.simcityexpansion.buildpack.convert.NbtStructure;
import com.github.simcityexpansion.buildpack.convert.StructureNbtWriter;
import com.github.simcityexpansion.buildpack.convert.StructureTransforms;
import com.github.simcityexpansion.buildpack.model.ImportScanner;
import com.github.simcityexpansion.buildpack.ui.preview.StructureScene;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 建筑结构变换编辑器：左侧满高 3D 主视图（拖动旋转/平移/缩放/切顶），右侧分组工具面板
 * （变换 / 编辑 / 历史 / 视图 / 保存）。「保存」把结果写到导入目录（.nbt）。
 */
public final class StructureEditorScreen extends Screen {

  private static final Logger LOGGER = LoggerFactory.getLogger(StructureEditorScreen.class);

  private static final int PAD = 10;
  private static final int TITLE_H = 12;
  private static final int GAP = 4;
  private static final int STATUS_H = 12;
  private static final int TOOL_W = 150;
  private static final int TOOL_INNER = TOOL_W - 8;
  private static final int BTN_H = 16;
  private static final int ROW_H = 18;

  /** 一条分组小标题：文本 + 绘制坐标。 */
  private record SectionLabel(Component text, int x, int y) {}

  private final Screen previous;
  private final NbtStructure original;
  private final StructureScene scene;
  private final Deque<NbtStructure> undo = new ArrayDeque<>();
  private final Deque<NbtStructure> redo = new ArrayDeque<>();
  private final List<SectionLabel> sections = new ArrayList<>();

  private NbtStructure work;
  private String outName;
  private Component status = Component.empty();
  private boolean statusError;

  private StructureEditorScreen(Screen previous, NbtStructure original, StructureScene scene,
      String outName) {
    super(Component.translatable("buildpack.editor.title"));
    this.previous = previous;
    this.original = original;
    this.scene = scene;
    this.work = original;
    this.outName = outName;
  }

  /** 打开编辑器（结构超限/无法渲染时不打开）。 */
  public static void open(NbtStructure original, String baseName) {
    StructureScene scene = new StructureScene(0, 0, 0, 0, true);
    if (!scene.setStructure(original)) {
      return;
    }
    Minecraft mc = Minecraft.getInstance();
    mc.setScreen(new StructureEditorScreen(mc.screen, original, scene, sanitize(baseName) + "_edited"));
  }

  private int bodyY() {
    return PAD + TITLE_H + GAP;
  }

  private int bodyHeight() {
    return height - PAD - STATUS_H - GAP - bodyY();
  }

  private int toolX() {
    return width - PAD - TOOL_W;
  }

  @Override
  protected void init() {
    sections.clear();

    int leftW = toolX() - GAP - PAD;
    scene.setX(PAD + 1);
    scene.setY(bodyY() + 1);
    scene.setWidth(leftW - 2);
    scene.setHeight(bodyHeight() - 2);
    addRenderableWidget(scene);

    int x = toolX() + 4;
    int y = bodyY() + 4;
    y = section("buildpack.editor.group.transform", x, y);
    y = row2(x, y,
        "buildpack.editor.rotate", () -> apply(StructureTransforms.rotateClockwise(work)),
        "buildpack.editor.rotate_ccw", () -> apply(StructureTransforms.rotateCounterClockwise(work)));
    y = row2(x, y,
        "buildpack.editor.mirror_x", () -> apply(StructureTransforms.mirrorX(work)),
        "buildpack.editor.mirror_z", () -> apply(StructureTransforms.mirrorZ(work)));
    y = row2(x, y,
        "buildpack.editor.crop", () -> apply(StructureTransforms.crop(work)),
        "buildpack.editor.hollow", () -> apply(StructureTransforms.hollow(work)));

    y = section("buildpack.editor.group.edit", x, y);
    y = row2(x, y,
        "buildpack.editor.remove", () -> openRemove(),
        "buildpack.editor.replace", () -> openReplace());

    y = section("buildpack.editor.group.history", x, y);
    y = row2(x, y,
        "buildpack.editor.undo", () -> doUndo(),
        "buildpack.editor.redo", () -> doRedo());
    y = row1(x, y, "buildpack.editor.revert", () -> apply(original));

    y = section("buildpack.editor.group.view", x, y);
    y = row2(x, y,
        "buildpack.preview.peel", () -> scene.peelTop(),
        "buildpack.preview.unpeel", () -> scene.unpeelTop());
    y = row1(x, y, "buildpack.preview.reset", () -> scene.resetView());

    y = section("buildpack.editor.group.save", x, y);
    EditBox nameField = new EditBox(font, x, y, TOOL_INNER, BTN_H, Component.empty());
    nameField.setMaxLength(128);
    nameField.setValue(outName);
    nameField.setResponder(value -> outName = value);
    addRenderableWidget(nameField);
    y += ROW_H;
    row2(x, y,
        "buildpack.editor.save", () -> save(),
        "buildpack.action.back", () -> onClose());
  }

  private int section(String key, int x, int y) {
    sections.add(new SectionLabel(Component.translatable(key), x, y + 2));
    return y + 12;
  }

  private int row1(int x, int y, String key, Runnable onPress) {
    addRenderableWidget(
        new ThemedButton(x, y, TOOL_INNER, BTN_H, Component.translatable(key), onPress));
    return y + ROW_H;
  }

  private int row2(int x, int y, String k1, Runnable p1, String k2, Runnable p2) {
    int w = (TOOL_INNER - 3) / 2;
    addRenderableWidget(new ThemedButton(x, y, w, BTN_H, Component.translatable(k1), p1));
    addRenderableWidget(
        new ThemedButton(x + w + 3, y, TOOL_INNER - w - 3, BTN_H, Component.translatable(k2), p2));
    return y + ROW_H;
  }

  // ---- 操作 ----

  private void apply(NbtStructure result) {
    undo.push(work);
    redo.clear();
    work = result;
    scene.setStructure(result, false);
  }

  private void doUndo() {
    if (!undo.isEmpty()) {
      redo.push(work);
      work = undo.pop();
      scene.setStructure(work, false);
    }
  }

  private void doRedo() {
    if (!redo.isEmpty()) {
      undo.push(work);
      work = redo.pop();
      scene.setStructure(work, false);
    }
  }

  private void openRemove() {
    BlockPickerScreen.open(work, id -> {
      apply(StructureTransforms.removeBlock(work, id));
      minecraft.setScreen(this);
    });
  }

  private void openReplace() {
    BlockPickerScreen.open(work, from ->
        BlockPickerScreen.open(work, to -> {
          apply(StructureTransforms.replaceBlock(work, from, to));
          minecraft.setScreen(this);
        }));
  }

  private void save() {
    try {
      Path dir = ImportScanner.ensureImportDir();
      Path target = uniqueTarget(dir, sanitize(outName) + ".nbt");
      StructureNbtWriter.write(work, target);
      BuildPackScreen.setLastTab(SourceTab.IMPORT);
      BuildPackScreen.open();
    } catch (IOException | RuntimeException e) {
      I18nLog.warn(LOGGER, e, "buildpack.log.export_failed");
      status = Component.translatable("buildpack.editor.save_failed", LocalizedIOException.messageOf(e));
      statusError = true;
    }
  }

  // ---- 渲染 ----

  @Override
  public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
    // 覆盖原版：不做高斯模糊背景；遮罩由 render() 自行绘制。
  }

  @Override
  public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
    g.fill(0, 0, width, height, BuildPackTheme.ROOT_BG);
    int leftW = toolX() - GAP - PAD;
    BuildPackTheme.previewPanel(g, PAD, bodyY(), leftW, bodyHeight());
    BuildPackTheme.panel(g, toolX(), bodyY(), TOOL_W, bodyHeight());
    g.drawString(font, Component.translatable("buildpack.editor.title"),
        PAD + 10, PAD, BuildPackTheme.TITLE, true);
    for (SectionLabel label : sections) {
      g.drawString(font, label.text(), label.x(), label.y(), BuildPackTheme.TITLE, true);
    }
    super.render(g, mouseX, mouseY, partialTick);
    if (!status.getString().isEmpty()) {
      g.drawString(font, status, PAD, height - PAD - STATUS_H,
          statusError ? BuildPackTheme.MESSAGE_ERROR : BuildPackTheme.MESSAGE_OK, true);
    }
  }

  @Override
  public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
    if (button != 0 && scene.isMouseOver(mouseX, mouseY)) {
      scene.applyDrag(button, dragX, dragY);
      return true;
    }
    return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
  }

  @Override
  public void onClose() {
    if (previous != null) {
      minecraft.setScreen(previous);
    } else {
      BuildPackScreen.open();
    }
  }

  // ---- 工具 ----

  private static String sanitize(String name) {
    String cleaned = name == null ? "" : name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
    return cleaned.isBlank() ? "building" : cleaned;
  }

  private static Path uniqueTarget(Path dir, String fileName) {
    Path target = dir.resolve(fileName);
    int dot = fileName.lastIndexOf('.');
    String base = dot > 0 ? fileName.substring(0, dot) : fileName;
    String extension = dot > 0 ? fileName.substring(dot) : "";
    int suffix = 2;
    while (Files.exists(target)) {
      target = dir.resolve(base + "_" + suffix++ + extension);
    }
    return target;
  }
}
