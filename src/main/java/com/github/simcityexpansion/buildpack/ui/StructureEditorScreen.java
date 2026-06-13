package com.github.simcityexpansion.buildpack.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;

import com.github.simcityexpansion.buildpack.BuildPack;
import com.github.simcityexpansion.buildpack.I18nLog;
import com.github.simcityexpansion.buildpack.LocalizedIOException;
import com.github.simcityexpansion.buildpack.convert.NbtStructure;
import com.github.simcityexpansion.buildpack.convert.StructureNbtWriter;
import com.github.simcityexpansion.buildpack.convert.StructureTransforms;
import com.github.simcityexpansion.buildpack.model.ImportScanner;
import com.github.simcityexpansion.buildpack.ui.preview.StructureScene;
import com.lowdragmc.lowdraglib2.gui.holder.ModularUIScreen;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextField;
import dev.vfyjxf.taffy.style.AlignItems;
import dev.vfyjxf.taffy.style.FlexDirection;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 建筑结构变换编辑器（重新设计）：左侧满高 3D 主视图（拖动旋转/平移/缩放/切顶，左上角实时显示尺寸），
 * 右侧分组工具面板（变换 / 编辑 / 历史 / 视图 / 保存）。「保存」把结果写到导入目录（.nbt）。
 */
public final class StructureEditorScreen extends ModularUIScreen {

  private static final Logger LOGGER = LoggerFactory.getLogger(StructureEditorScreen.class);

  private StructureEditorScreen(ModularUI modularUI) {
    super(modularUI, Component.translatable("buildpack.editor.title"));
  }

  /** 打开编辑器（结构超限/无法渲染时不打开）。 */
  public static void open(NbtStructure original, String baseName) {
    StructureScene scene = new StructureScene(true);
    if (!scene.setStructure(original)) {
      return;
    }
    NbtStructure[] work = {original};
    Deque<NbtStructure> undo = new ArrayDeque<>();
    Deque<NbtStructure> redo = new ArrayDeque<>();
    String[] outName = {sanitize(baseName) + "_edited"};
    Screen previous = Minecraft.getInstance().screen;

    UIElement root = new UIElement();
    root.addClass(BuildPack.cls("root"));
    root.layout(layout -> layout.widthPercent(100.0f).heightPercent(100.0f)
        .flexDirection(FlexDirection.COLUMN).paddingAll(10.0f).gapRow(4.0f));

    Label title = new Label();
    title.addClass(BuildPack.cls("title"));
    title.setValue(Component.translatable("buildpack.editor.title"));
    title.layout(layout -> layout.marginLeft(10.0f).height(12.0f));

    UIElement sceneBox = new UIElement();
    sceneBox.addClass(BuildPack.cls("preview"));
    sceneBox.layout(layout -> layout.flexDirection(FlexDirection.COLUMN).flexGrow(1.0f).heightStretch());
    sceneBox.addChild(scene);

    Label status = new Label();
    status.addClass(BuildPack.cls("status-message"));
    status.setValue(Component.empty());

    // ---- 工具面板按钮 ----
    Button rotate = button("buildpack.editor.rotate");
    rotate.setOnClick(e -> apply(scene, work, undo, redo, StructureTransforms.rotateClockwise(work[0])));
    Button rotateCcw = button("buildpack.editor.rotate_ccw");
    rotateCcw.setOnClick(e -> apply(scene, work, undo, redo, StructureTransforms.rotateCounterClockwise(work[0])));
    Button mirrorX = button("buildpack.editor.mirror_x");
    mirrorX.setOnClick(e -> apply(scene, work, undo, redo, StructureTransforms.mirrorX(work[0])));
    Button mirrorZ = button("buildpack.editor.mirror_z");
    mirrorZ.setOnClick(e -> apply(scene, work, undo, redo, StructureTransforms.mirrorZ(work[0])));
    Button crop = button("buildpack.editor.crop");
    crop.setOnClick(e -> apply(scene, work, undo, redo, StructureTransforms.crop(work[0])));
    Button hollow = button("buildpack.editor.hollow");
    hollow.setOnClick(e -> apply(scene, work, undo, redo, StructureTransforms.hollow(work[0])));

    Button remove = button("buildpack.editor.remove");
    remove.setOnClick(e -> {
      Screen editor = Minecraft.getInstance().screen;
      BlockPickerScreen.open(work[0], id -> {
        apply(scene, work, undo, redo, StructureTransforms.removeBlock(work[0], id));
        Minecraft.getInstance().setScreen(editor);
      });
    });
    Button replace = button("buildpack.editor.replace");
    replace.setOnClick(e -> {
      Screen editor = Minecraft.getInstance().screen;
      BlockPickerScreen.open(work[0], from ->
          BlockPickerScreen.open(work[0], to -> {
            apply(scene, work, undo, redo, StructureTransforms.replaceBlock(work[0], from, to));
            Minecraft.getInstance().setScreen(editor);
          }));
    });

    Button undoBtn = button("buildpack.editor.undo");
    undoBtn.setOnClick(e -> {
      if (!undo.isEmpty()) {
        redo.push(work[0]);
        work[0] = undo.pop();
        scene.setStructure(work[0], false);
      }
    });
    Button redoBtn = button("buildpack.editor.redo");
    redoBtn.setOnClick(e -> {
      if (!redo.isEmpty()) {
        undo.push(work[0]);
        work[0] = redo.pop();
        scene.setStructure(work[0], false);
      }
    });
    Button revert = button("buildpack.editor.revert");
    revert.setOnClick(e -> apply(scene, work, undo, redo, original));

    Button peelDown = button("buildpack.preview.peel");
    peelDown.setOnClick(e -> scene.peelTop());
    Button peelUp = button("buildpack.preview.unpeel");
    peelUp.setOnClick(e -> scene.unpeelTop());
    Button resetView = button("buildpack.preview.reset");
    resetView.setOnClick(e -> scene.resetView());

    TextField nameField = new TextField();
    nameField.setAnyString();
    nameField.setText(outName[0]);
    nameField.setTextResponder(value -> outName[0] = value);
    nameField.layout(layout -> layout.widthStretch().height(16.0f));

    Button save = button("buildpack.editor.save");
    save.setOnClick(e -> save(work[0], outName[0], status));
    Button back = button("buildpack.action.back");
    back.setOnClick(e -> {
      if (previous != null) {
        Minecraft.getInstance().setScreen(previous);
      } else {
        BuildPackScreen.open();
      }
    });

    UIElement tools = new UIElement();
    tools.addClass(BuildPack.cls("browser"));
    tools.layout(layout -> layout.flexDirection(FlexDirection.COLUMN)
        .gapRow(3.0f).paddingAll(4.0f).width(150.0f).heightStretch());
    tools.addChildren(
        section("buildpack.editor.group.transform"),
        row(rotate, rotateCcw), row(mirrorX, mirrorZ), row(crop, hollow),
        section("buildpack.editor.group.edit"),
        row(remove, replace),
        section("buildpack.editor.group.history"),
        row(undoBtn, redoBtn), row(revert),
        section("buildpack.editor.group.view"),
        row(peelDown, peelUp), row(resetView),
        section("buildpack.editor.group.save"),
        nameField, row(save, back));

    UIElement body = new UIElement();
    body.layout(layout -> layout.flexDirection(FlexDirection.ROW)
        .gapColumn(4.0f).widthStretch().flexGrow(1.0f));
    body.addChildren(sceneBox, tools);

    root.addChildren(title, body, status);

    UI ui = UI.of(root, BuildPack.STYLESHEET);
    Minecraft.getInstance().setScreen(new StructureEditorScreen(ModularUI.of(ui)));
  }

  /** 一排按钮（等宽）。 */
  private static UIElement row(Button... buttons) {
    UIElement r = new UIElement();
    r.layout(layout -> layout.flexDirection(FlexDirection.ROW)
        .alignItems(AlignItems.CENTER).gapColumn(3.0f).widthStretch().height(18.0f));
    for (Button button : buttons) {
      button.layout(layout -> layout.flexGrow(1.0f).heightStretch());
    }
    r.addChildren(buttons);
    return r;
  }

  private static Label section(String key) {
    Label label = new Label();
    label.addClass(BuildPack.cls("form-title"));
    label.setValue(Component.translatable(key));
    label.layout(layout -> layout.marginTop(2.0f).height(10.0f));
    return label;
  }

  private static void apply(StructureScene scene, NbtStructure[] work,
      Deque<NbtStructure> undo, Deque<NbtStructure> redo, NbtStructure result) {
    undo.push(work[0]);
    redo.clear();
    work[0] = result;
    scene.setStructure(result, false);
  }

  private static void save(NbtStructure structure, String name, Label status) {
    try {
      Path dir = ImportScanner.ensureImportDir();
      Path target = uniqueTarget(dir, sanitize(name) + ".nbt");
      StructureNbtWriter.write(structure, target);
      BuildPackView.setLastTab(SourceTab.IMPORT);
      BuildPackScreen.open();
    } catch (IOException | RuntimeException e) {
      I18nLog.warn(LOGGER, e, "buildpack.log.export_failed");
      status.setValue(Component.translatable(
          "buildpack.editor.save_failed", LocalizedIOException.messageOf(e)));
      status.addClass(BuildPack.cls("status-error"));
    }
  }

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

  private static Button button(String key) {
    Button button = new Button().setText(Component.translatable(key));
    button.addClass(BuildPack.cls("action"));
    return button;
  }
}
