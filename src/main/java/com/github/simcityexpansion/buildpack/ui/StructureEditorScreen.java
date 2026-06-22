package com.github.simcityexpansion.buildpack.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.UnaryOperator;

import com.github.simcityexpansion.buildpack.I18nLog;
import com.github.simcityexpansion.buildpack.LocalizedIOException;
import com.github.simcityexpansion.buildpack.convert.LitematicWriter;
import com.github.simcityexpansion.buildpack.convert.NbtStructure;
import com.github.simcityexpansion.buildpack.convert.StructureNbtWriter;
import com.github.simcityexpansion.buildpack.convert.StructureTransforms;
import com.github.simcityexpansion.buildpack.install.BuildingInstaller;
import com.github.simcityexpansion.buildpack.model.BuildingCategory;
import com.github.simcityexpansion.buildpack.model.BuildingMetadata;
import com.github.simcityexpansion.buildpack.model.ImportFile;
import com.github.simcityexpansion.buildpack.model.ImportScanner;
import com.github.simcityexpansion.buildpack.model.StructureFormat;
import com.github.simcityexpansion.buildpack.ui.preview.StructureScene;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Structure transform editor: full-height 3D main view on the left (drag to rotate/pan/zoom/peel
 * top), grouped tool panel on the right (transform, edit, history, view, save). "Save" writes
 * the result to the import directory (.nbt).
 */
public final class StructureEditorScreen extends Screen {

  private static final Logger LOGGER = LoggerFactory.getLogger(StructureEditorScreen.class);

  private static final int PAD = 10;
  private static final int TITLE_H = 12;
  private static final int GAP = 4;
  private static final int STATUS_H = 12;
  private static final int TOOL_W = 150;
  private static final int TOOL_INNER = TOOL_W - 8;
  private static final int BTN_H = 14;
  private static final int ROW_H = 16;
  private static final int MAX_HISTORY = 30;

  /** A section sub-header: display text and render coordinates. */
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
  private final int[] selMin = {0, 0, 0};
  private final int[] selMax = {0, 0, 0};
  private boolean selectionShown;

  private enum EditTool { NONE, PLACE, BREAK, PICK, REPLACE, SELECT }

  private EditTool tool = EditTool.NONE;
  private String paintBlock = "minecraft:stone";
  private int brushRadius;
  private boolean strokeActive;
  private int paintRowY;
  private boolean showLegend;
  private NbtStructure clipboard;
  private int symmetryAxis;
  private EditBox nameField;
  private final long origBlocks;
  private long workBlocks;

  private StructureEditorScreen(Screen previous, NbtStructure original, StructureScene scene,
      String outName) {
    super(Component.translatable("buildpack.editor.title"));
    this.previous = previous;
    this.original = original;
    this.scene = scene;
    this.work = original;
    this.outName = outName;
    this.selMax[0] = original.sizeX - 1;
    this.selMax[1] = original.sizeY - 1;
    this.selMax[2] = original.sizeZ - 1;
    this.origBlocks = original.countNonAir();
    this.workBlocks = this.origBlocks;
  }

  /** Opens the editor (does nothing if the structure exceeds limits or cannot be rendered). */
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
    scene.ensureBaked();
    scene.setEditCallback(this::onEdit);
    scene.setSelectCallback(this::onSelectCorners);
    scene.setEditMode(tool != EditTool.NONE && tool != EditTool.SELECT);
    scene.setSelectMode(tool == EditTool.SELECT);

    int x = toolX() + 4;
    int y = bodyY() + 4;
    y = section("buildpack.editor.group.transform", x, y);
    y = row3(x, y,
        "buildpack.editor.rotate", () -> apply(StructureTransforms.rotateClockwise(work)),
        "buildpack.editor.rotate_ccw", () -> apply(StructureTransforms.rotateCounterClockwise(work)),
        "buildpack.editor.mirror_x", () -> apply(StructureTransforms.mirrorX(work)));
    y = row3(x, y,
        "buildpack.editor.mirror_z", () -> apply(StructureTransforms.mirrorZ(work)),
        "buildpack.editor.mirror_y", () -> apply(StructureTransforms.mirrorY(work)),
        "buildpack.editor.rotate_x", () -> apply(StructureTransforms.rotateX(work)));
    y = row3(x, y,
        "buildpack.editor.rotate_z", () -> apply(StructureTransforms.rotateZ(work)),
        "buildpack.editor.crop", () -> apply(StructureTransforms.crop(work)),
        "buildpack.editor.hollow", () -> apply(StructureTransforms.hollow(work)));
    y = row3(x, y,
        "buildpack.editor.fill", () -> apply(StructureTransforms.fill(work)),
        "buildpack.editor.frame", () -> apply(StructureTransforms.frame(work)),
        "buildpack.editor.expand", () -> apply(StructureTransforms.expand(work)));

    y = section("buildpack.editor.group.edit", x, y);
    y = row2(x, y,
        "buildpack.editor.remove", () -> openRemove(),
        "buildpack.editor.replace", () -> openReplace());
    int toolGap = 3;
    int toolW = (TOOL_INNER - toolGap * 2) / 3;
    addRenderableWidget(new ThemedButton(x, y, toolW, BTN_H,
        Component.translatable("buildpack.editor.place"), () -> setTool(EditTool.PLACE))
        .selected(() -> tool == EditTool.PLACE));
    addRenderableWidget(new ThemedButton(x + toolW + toolGap, y, toolW, BTN_H,
        Component.translatable("buildpack.editor.break"), () -> setTool(EditTool.BREAK))
        .selected(() -> tool == EditTool.BREAK));
    addRenderableWidget(new ThemedButton(x + (toolW + toolGap) * 2, y,
        TOOL_INNER - (toolW + toolGap) * 2, BTN_H,
        Component.translatable("buildpack.editor.pick"), () -> setTool(EditTool.PICK))
        .selected(() -> tool == EditTool.PICK));
    y += ROW_H;
    paintRowY = y;
    y = row1(x, y, "buildpack.editor.paint", () -> openPaint());

    y = section("buildpack.editor.group.region", x, y);
    addRenderableWidget(new ThemedButton(x, y, TOOL_INNER, BTN_H,
        Component.translatable("buildpack.editor.region_select"), () -> setTool(EditTool.SELECT))
        .selected(() -> tool == EditTool.SELECT));
    y += ROW_H;
    y = row3(x, y,
        "buildpack.editor.region_all", () -> selectAll(),
        "buildpack.editor.region_set", () -> openSelection(),
        "buildpack.editor.region_clear", () -> clearSelection());
    y = row3(x, y,
        "buildpack.editor.region_delete", () -> regionDelete(),
        "buildpack.editor.region_crop", () -> regionCrop(),
        "buildpack.editor.region_fill", () -> regionFill());
    y = row3(x, y,
        "buildpack.editor.region_replace", () -> regionReplace(),
        "buildpack.editor.region_hollow", () -> regionHollow(),
        "buildpack.editor.region_frame", () -> regionFrame());

    y = section("buildpack.editor.group.history", x, y);
    y = row3(x, y,
        "buildpack.editor.undo", () -> doUndo(),
        "buildpack.editor.redo", () -> doRedo(),
        "buildpack.editor.revert", () -> apply(original));

    y = section("buildpack.editor.group.view", x, y);
    y = row3(x, y,
        "buildpack.preview.peel", () -> scene.peelTop(),
        "buildpack.preview.unpeel", () -> scene.unpeelTop(),
        "buildpack.preview.reset", () -> scene.resetView());

    y = section("buildpack.editor.group.save", x, y);
    nameField = new EditBox(font, x, y, TOOL_INNER, BTN_H, Component.empty());
    nameField.setMaxLength(128);
    nameField.setValue(outName);
    nameField.setResponder(value -> outName = value);
    addRenderableWidget(nameField);
    y += ROW_H;
    y = row2(x, y,
        "buildpack.editor.save", () -> save(),
        "buildpack.action.back", () -> onClose());
    row2(x, y,
        "buildpack.editor.export_lite", () -> exportLitematic(),
        "buildpack.editor.save_install", () -> openInstall());
  }

  private int section(String key, int x, int y) {
    sections.add(new SectionLabel(Component.translatable(key), x, y + 2));
    return y + 11;
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

  private int row3(int x, int y, String k1, Runnable p1, String k2, Runnable p2,
      String k3, Runnable p3) {
    int gap = 3;
    int w = (TOOL_INNER - gap * 2) / 3;
    addRenderableWidget(new ThemedButton(x, y, w, BTN_H, Component.translatable(k1), p1));
    addRenderableWidget(new ThemedButton(x + w + gap, y, w, BTN_H, Component.translatable(k2), p2));
    addRenderableWidget(new ThemedButton(
        x + (w + gap) * 2, y, TOOL_INNER - (w + gap) * 2, BTN_H, Component.translatable(k3), p3));
    return y + ROW_H;
  }

  // ---- Operations ----

  private void apply(NbtStructure result) {
    undo.push(work);
    while (undo.size() > MAX_HISTORY) {
      undo.removeLast();
    }
    redo.clear();
    work = result;
    workBlocks = work.countNonAir();
    scene.setStructure(result, false);
    resetSelectionState();
  }

  /** Like {@link #apply} but coalesces a whole paint stroke into one undo step. */
  private void paintApply(NbtStructure result) {
    if (!strokeActive) {
      undo.push(work);
      while (undo.size() > MAX_HISTORY) {
        undo.removeLast();
      }
      redo.clear();
      strokeActive = true;
    }
    work = result;
    workBlocks = work.countNonAir();
    scene.setStructure(result, false);
    resetSelectionState();
  }

  private void doUndo() {
    if (!undo.isEmpty()) {
      redo.push(work);
      work = undo.pop();
      workBlocks = work.countNonAir();
      scene.setStructure(work, false);
      resetSelectionState();
    }
  }

  private void doRedo() {
    if (!redo.isEmpty()) {
      undo.push(work);
      work = redo.pop();
      workBlocks = work.countNonAir();
      scene.setStructure(work, false);
      resetSelectionState();
    }
  }

  // ---- Region selection ----

  private void selectAll() {
    selMin[0] = 0;
    selMin[1] = 0;
    selMin[2] = 0;
    selMax[0] = work.sizeX - 1;
    selMax[1] = work.sizeY - 1;
    selMax[2] = work.sizeZ - 1;
    pushSelection();
  }

  private void openSelection() {
    SelectionScreen.open(selMin.clone(), selMax.clone(), work.sizeX, work.sizeY, work.sizeZ,
        (mn, mx) -> {
          System.arraycopy(mn, 0, selMin, 0, 3);
          System.arraycopy(mx, 0, selMax, 0, 3);
          pushSelection();
        });
  }

  private void pushSelection() {
    selectionShown = true;
    status = Component.empty();
    statusError = false;
    scene.setSelection(selMin[0], selMin[1], selMin[2], selMax[0], selMax[1], selMax[2]);
  }

  /** Callback from the 3D view's box-select tool: store the two picked corner cells. */
  private void onSelectCorners(BlockPos a, BlockPos b) {
    selMin[0] = Math.min(a.getX(), b.getX());
    selMin[1] = Math.min(a.getY(), b.getY());
    selMin[2] = Math.min(a.getZ(), b.getZ());
    selMax[0] = Math.max(a.getX(), b.getX());
    selMax[1] = Math.max(a.getY(), b.getY());
    selMax[2] = Math.max(a.getZ(), b.getZ());
    pushSelection();
  }

  /** Move (or, with Shift, resize the max corner of) the selection by one cell along an axis. */
  private void nudgeSelection(int dx, int dy, int dz, boolean grow) {
    if (!selectionShown) {
      return;
    }
    if (grow) {
      selMax[0] = clampInt(selMax[0] + dx, selMin[0], work.sizeX - 1);
      selMax[1] = clampInt(selMax[1] + dy, selMin[1], work.sizeY - 1);
      selMax[2] = clampInt(selMax[2] + dz, selMin[2], work.sizeZ - 1);
    } else {
      moveAxis(0, dx, work.sizeX);
      moveAxis(1, dy, work.sizeY);
      moveAxis(2, dz, work.sizeZ);
    }
    pushSelection();
  }

  private void moveAxis(int axis, int delta, int size) {
    if (delta == 0) {
      return;
    }
    int span = selMax[axis] - selMin[axis];
    int newMin = clampInt(selMin[axis] + delta, 0, size - 1 - span);
    selMin[axis] = newMin;
    selMax[axis] = newMin + span;
  }

  private static int clampInt(int v, int lo, int hi) {
    return Math.max(lo, Math.min(v, hi));
  }

  /** Clears the current selection (and cancels any in-progress box-select). */
  private void clearSelection() {
    resetSelectionState();
    scene.clearSelection();
    statusError = false;
    status = Component.translatable("buildpack.editor.region_cleared");
  }

  private void regionDelete() {
    if (requireSelection()) {
      apply(StructureTransforms.deleteRegion(work,
          selMin[0], selMin[1], selMin[2], selMax[0], selMax[1], selMax[2]));
    }
  }

  private void regionCrop() {
    if (requireSelection()) {
      apply(StructureTransforms.cropToRegion(work,
          selMin[0], selMin[1], selMin[2], selMax[0], selMax[1], selMax[2]));
    }
  }

  private void regionFill() {
    if (!requireSelection()) {
      return;
    }
    int[] mn = selMin.clone();
    int[] mx = selMax.clone();
    BlockPaletteScreen.open(id -> {
      apply(StructureTransforms.fillRegion(work, mn[0], mn[1], mn[2], mx[0], mx[1], mx[2], id));
      minecraft.setScreen(this);
    });
  }

  /** Apply a same-dimension transform to just the selected region (crop → transform → paste back). */
  private void regionTransform(UnaryOperator<NbtStructure> op) {
    if (!requireSelection()) {
      return;
    }
    NbtStructure clip = StructureTransforms.cropToRegion(work,
        selMin[0], selMin[1], selMin[2], selMax[0], selMax[1], selMax[2]);
    apply(StructureTransforms.pasteRegion(work, op.apply(clip),
        selMin[0], selMin[1], selMin[2]));
  }

  private void regionHollow() {
    regionTransform(StructureTransforms::hollow);
  }

  private void regionFrame() {
    regionTransform(StructureTransforms::frame);
  }

  private void regionReplace() {
    if (!requireSelection()) {
      return;
    }
    int[] mn = selMin.clone();
    int[] mx = selMax.clone();
    BlockPickerScreen.open(work, from ->
        BlockPaletteScreen.open(to -> {
          NbtStructure clip = StructureTransforms.cropToRegion(work,
              mn[0], mn[1], mn[2], mx[0], mx[1], mx[2]);
          apply(StructureTransforms.pasteRegion(work,
              StructureTransforms.replaceBlock(clip, from, to), mn[0], mn[1], mn[2]));
          minecraft.setScreen(this);
        }));
  }

  private boolean requireSelection() {
    if (!selectionShown) {
      status = Component.translatable("buildpack.editor.region_need");
      statusError = true;
      return false;
    }
    return true;
  }

  private void resetSelectionState() {
    selectionShown = false;
    selMin[0] = 0;
    selMin[1] = 0;
    selMin[2] = 0;
    selMax[0] = work.sizeX - 1;
    selMax[1] = work.sizeY - 1;
    selMax[2] = work.sizeZ - 1;
  }

  private void openRemove() {
    BlockPickerScreen.open(work, id -> {
      apply(StructureTransforms.removeBlock(work, id));
      minecraft.setScreen(this);
    });
  }

  private void openReplace() {
    BlockPickerScreen.open(work, from ->
        BlockPaletteScreen.open(to -> {
          apply(StructureTransforms.replaceBlock(work, from, to));
          minecraft.setScreen(this);
        }));
  }

  // ---- Single-block editing ----

  private void setTool(EditTool selected) {
    tool = tool == selected ? EditTool.NONE : selected;
    boolean selecting = tool == EditTool.SELECT;
    scene.setEditMode(tool != EditTool.NONE && !selecting);
    scene.setSelectMode(selecting);
    if (!selecting) {
      if (selectionShown) {
        scene.setSelection(selMin[0], selMin[1], selMin[2], selMax[0], selMax[1], selMax[2]);
      } else {
        scene.clearSelection();
      }
    }
    setEditStatus();
  }

  private void setEditStatus() {
    statusError = false;
    if (tool == EditTool.NONE) {
      status = Component.empty();
    } else if (tool == EditTool.SELECT) {
      status = Component.translatable("buildpack.editor.select_hint");
    } else {
      status = Component.translatable("buildpack.editor.editing", paintBlock, brushRadius * 2 + 1);
    }
  }

  private void openPaint() {
    BlockPaletteScreen.open(id -> {
      paintBlock = id;
      minecraft.setScreen(this);
      setEditStatus();
    });
  }

  private void onEdit(StructureScene.Hit hit) {
    int r = brushRadius;
    BlockPos p = hit.pos();
    switch (tool) {
      case PLACE -> {
        BlockPos at = p.relative(hit.face());
        if (r != 0 || inBounds(at)) {
          cubeFill(at, r, paintBlock);
        }
      }
      case REPLACE -> cubeFill(p, r, paintBlock);
      case BREAK -> cubeBreak(p, r);
      case PICK -> {
        String id = scene.blockStateAt(p);
        if (id != null) {
          paintBlock = id;
          statusError = false;
          status = Component.translatable("buildpack.editor.picked", id);
        }
      }
      case NONE -> { }
    }
  }

  private void cubeFill(BlockPos c, int r, String id) {
    NbtStructure result = StructureTransforms.fillRegion(work,
        c.getX() - r, c.getY() - r, c.getZ() - r, c.getX() + r, c.getY() + r, c.getZ() + r, id);
    BlockPos m = mirror(c);
    if (m != null) {
      result = StructureTransforms.fillRegion(result,
          m.getX() - r, m.getY() - r, m.getZ() - r, m.getX() + r, m.getY() + r, m.getZ() + r, id);
    }
    paintApply(result);
  }

  private void cubeBreak(BlockPos c, int r) {
    NbtStructure result = StructureTransforms.deleteRegion(work,
        c.getX() - r, c.getY() - r, c.getZ() - r, c.getX() + r, c.getY() + r, c.getZ() + r);
    BlockPos m = mirror(c);
    if (m != null) {
      result = StructureTransforms.deleteRegion(result,
          m.getX() - r, m.getY() - r, m.getZ() - r, m.getX() + r, m.getY() + r, m.getZ() + r);
    }
    paintApply(result);
  }

  private BlockPos mirror(BlockPos p) {
    return switch (symmetryAxis) {
      case 1 -> new BlockPos(work.sizeX - 1 - p.getX(), p.getY(), p.getZ());
      case 2 -> new BlockPos(p.getX(), work.sizeY - 1 - p.getY(), p.getZ());
      case 3 -> new BlockPos(p.getX(), p.getY(), work.sizeZ - 1 - p.getZ());
      default -> null;
    };
  }

  private void cycleSymmetry() {
    symmetryAxis = (symmetryAxis + 1) % 4;
    statusError = false;
    String axis = switch (symmetryAxis) {
      case 1 -> "X";
      case 2 -> "Y";
      case 3 -> "Z";
      default -> "";
    };
    status = symmetryAxis == 0
        ? Component.translatable("buildpack.editor.symmetry_off")
        : Component.translatable("buildpack.editor.symmetry_on", axis);
  }

  private void copyRegion() {
    if (requireSelection()) {
      clipboard = StructureTransforms.cropToRegion(work,
          selMin[0], selMin[1], selMin[2], selMax[0], selMax[1], selMax[2]);
      statusError = false;
      status = Component.translatable("buildpack.editor.copied");
    }
  }

  private void cutRegion() {
    if (requireSelection()) {
      clipboard = StructureTransforms.cropToRegion(work,
          selMin[0], selMin[1], selMin[2], selMax[0], selMax[1], selMax[2]);
      apply(StructureTransforms.deleteRegion(work,
          selMin[0], selMin[1], selMin[2], selMax[0], selMax[1], selMax[2]));
      statusError = false;
      status = Component.translatable("buildpack.editor.cut");
    }
  }

  private void pasteClipboard() {
    pasteClipboard(false);
  }

  private void pasteClipboard(boolean merge) {
    if (clipboard == null) {
      status = Component.translatable("buildpack.editor.clipboard_empty");
      statusError = true;
      return;
    }
    int ax = selectionShown ? selMin[0] : 0;
    int ay = selectionShown ? selMin[1] : 0;
    int az = selectionShown ? selMin[2] : 0;
    apply(StructureTransforms.pasteRegion(work, clipboard, ax, ay, az, merge));
    statusError = false;
    status = Component.translatable(merge
        ? "buildpack.editor.pasted_merge" : "buildpack.editor.pasted");
  }

  /** Fit the view to the selection (if any) or the whole structure. */
  private void focusView() {
    if (selectionShown) {
      float cx = (selMin[0] + selMax[0] + 1) / 2.0f;
      float cy = (selMin[1] + selMax[1] + 1) / 2.0f;
      float cz = (selMin[2] + selMax[2] + 1) / 2.0f;
      int span = Math.max(selMax[0] - selMin[0],
          Math.max(selMax[1] - selMin[1], selMax[2] - selMin[2])) + 1;
      scene.focus(cx, cy, cz, span);
    } else {
      scene.fitAll();
    }
  }

  private boolean inBounds(BlockPos p) {
    return p.getX() >= 0 && p.getX() < work.sizeX
        && p.getY() >= 0 && p.getY() < work.sizeY
        && p.getZ() >= 0 && p.getZ() < work.sizeZ;
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

  /** Export the edited structure as a Litematica projection (.litematic) into the import dir. */
  private void exportLitematic() {
    try {
      Path dir = ImportScanner.ensureImportDir();
      Path target = uniqueTarget(dir, sanitize(outName) + ".litematic");
      LitematicWriter.write(work, sanitize(outName), playerName(), target);
      BuildPackScreen.setLastTab(SourceTab.IMPORT);
      BuildPackScreen.open();
    } catch (IOException | RuntimeException e) {
      I18nLog.warn(LOGGER, e, "buildpack.log.export_failed");
      status = Component.translatable("buildpack.editor.save_failed", LocalizedIOException.messageOf(e));
      statusError = true;
    }
  }

  private void openInstall() {
    CategoryPickerScreen.open(this::doInstall);
  }

  /** Write the edited structure to the import dir and install it into the chosen category. */
  private void doInstall(BuildingCategory category) {
    try {
      Path dir = ImportScanner.ensureImportDir();
      Path target = uniqueTarget(dir, sanitize(outName) + ".nbt");
      StructureNbtWriter.write(work, target);
      ImportFile file = new ImportFile(target, StructureFormat.VANILLA_NBT,
          Files.size(target), Files.getLastModifiedTime(target).toInstant());
      BuildingMetadata meta = new BuildingMetadata();
      meta.name = sanitize(outName);
      meta.category = category;
      meta.author = playerName();
      meta.sizeX = work.sizeX;
      meta.sizeY = work.sizeY;
      meta.sizeZ = work.sizeZ;
      var result = BuildingInstaller.install(file, meta, false);
      if (result.ok()) {
        BuildPackScreen.setLastTab(SourceTab.INSTALLED);
        BuildPackScreen.open();
      } else {
        status = result.messages().isEmpty()
            ? Component.translatable("buildpack.editor.save_failed", "")
            : result.messages().get(0);
        statusError = true;
        minecraft.setScreen(this);
      }
    } catch (IOException | RuntimeException e) {
      I18nLog.warn(LOGGER, e, "buildpack.log.export_failed");
      status = Component.translatable("buildpack.editor.save_failed", LocalizedIOException.messageOf(e));
      statusError = true;
      minecraft.setScreen(this);
    }
  }

  private String playerName() {
    return Minecraft.getInstance().getUser().getName();
  }

  private String deltaText() {
    long delta = workBlocks - origBlocks;
    return delta > 0 ? "+" + delta : Long.toString(delta);
  }

  private ItemStack paintStack() {
    try {
      String id = paintBlock;
      int bracket = id.indexOf('[');
      if (bracket > 0) {
        id = id.substring(0, bracket);
      }
      Block block = BuiltInRegistries.BLOCK.get(ResourceLocation.parse(id));
      return new ItemStack(block);
    } catch (RuntimeException e) {
      return ItemStack.EMPTY;
    }
  }

  /**
   * Draw the shortcut legend. Lines starting with {@code #} are section headers; other lines are
   * {@code key=action} and render as two aligned, colour-coded columns.
   */
  private void drawLegend(GuiGraphics g) {
    String[] lines = Component.translatable("buildpack.editor.legend").getString().split("\n");
    int keyW = 0;
    int descW = 0;
    int headerW = 0;
    for (String line : lines) {
      if (line.startsWith("#")) {
        headerW = Math.max(headerW, font.width(line.substring(1)));
        continue;
      }
      int eq = line.indexOf('=');
      keyW = Math.max(keyW, font.width(eq < 0 ? line : line.substring(0, eq)));
      descW = Math.max(descW, font.width(eq < 0 ? "" : line.substring(eq + 1)));
    }
    int gap = 10;
    int lineH = font.lineHeight + 4;
    int pw = Math.max(keyW + gap + descW, headerW) + 24;
    int ph = lines.length * lineH + 16;
    int px = (width - pw) / 2;
    int py = (height - ph) / 2;
    BuildPackTheme.fillPanel(g, px, py, pw, ph, 0xF0101010);
    int ly = py + 9;
    for (String line : lines) {
      if (line.startsWith("#")) {
        g.drawString(font, line.substring(1), px + 12, ly, 0xFFFFCC55, true);
      } else {
        int eq = line.indexOf('=');
        String key = eq < 0 ? line : line.substring(0, eq);
        String desc = eq < 0 ? "" : line.substring(eq + 1);
        g.drawString(font, key, px + 12, ly, 0xFF8AD8FF, true);
        g.drawString(font, desc, px + 12 + keyW + gap, ly, BuildPackTheme.VALUE, true);
      }
      ly += lineH;
    }
  }

  // ---- Rendering ----

  @Override
  public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
    // Override vanilla: no Gaussian-blur background; the dim overlay is drawn by render().
  }

  @Override
  public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
    g.fill(0, 0, width, height, BuildPackTheme.ROOT_BG);
    int leftW = toolX() - GAP - PAD;
    BuildPackTheme.previewPanel(g, PAD, bodyY(), leftW, bodyHeight());
    BuildPackTheme.panel(g, toolX(), bodyY(), TOOL_W, bodyHeight());
    g.drawString(font, Component.translatable("buildpack.editor.title"),
        PAD + 10, PAD, BuildPackTheme.TITLE, true);
    String stats = Component.translatable("buildpack.editor.stats",
        work.sizeX + "×" + work.sizeY + "×" + work.sizeZ, workBlocks, deltaText(),
        undo.size()).getString();
    g.drawString(font, stats, width - PAD - font.width(stats), PAD, BuildPackTheme.LABEL, true);
    for (SectionLabel label : sections) {
      g.drawString(font, label.text(), label.x(), label.y(), BuildPackTheme.TITLE, true);
    }
    super.render(g, mouseX, mouseY, partialTick);
    ItemStack paint = paintStack();
    if (!paint.isEmpty()) {
      g.renderItem(paint, toolX() + 4 + TOOL_INNER - 18, paintRowY - 1);
    }
    String hint = Component.translatable("buildpack.editor.legend_hint").getString();
    g.drawString(font, hint, PAD + leftW - font.width(hint) - 3,
        bodyY() + bodyHeight() - font.lineHeight - 2, BuildPackTheme.HINT, true);
    if (!status.getString().isEmpty()) {
      g.drawString(font, status, PAD, height - PAD - STATUS_H,
          statusError ? BuildPackTheme.MESSAGE_ERROR : BuildPackTheme.MESSAGE_OK, true);
    }
    if (showLegend) {
      drawLegend(g);
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
  public boolean mouseReleased(double mouseX, double mouseY, int button) {
    if (button == 0) {
      strokeActive = false;
    }
    return super.mouseReleased(mouseX, mouseY, button);
  }

  @Override
  public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
    if (nameField != null && nameField.isFocused()) {
      return super.keyPressed(keyCode, scanCode, modifiers);
    }
    boolean ctrl = Screen.hasControlDown();
    boolean shift = Screen.hasShiftDown();
    if (ctrl && keyCode == GLFW.GLFW_KEY_Z && !shift) {
      doUndo();
      return true;
    }
    if (ctrl && (keyCode == GLFW.GLFW_KEY_Y || (shift && keyCode == GLFW.GLFW_KEY_Z))) {
      doRedo();
      return true;
    }
    if (ctrl && keyCode == GLFW.GLFW_KEY_S) {
      save();
      return true;
    }
    if (ctrl && keyCode == GLFW.GLFW_KEY_C) {
      copyRegion();
      return true;
    }
    if (ctrl && keyCode == GLFW.GLFW_KEY_X) {
      cutRegion();
      return true;
    }
    if (ctrl && keyCode == GLFW.GLFW_KEY_V) {
      pasteClipboard(shift);
      return true;
    }
    if (!ctrl && keyCode == GLFW.GLFW_KEY_R) {
      apply(shift ? StructureTransforms.rotateCounterClockwise(work)
          : StructureTransforms.rotateClockwise(work));
      return true;
    }
    if (!ctrl && selectionShown) {
      switch (keyCode) {
        case GLFW.GLFW_KEY_LEFT -> {
          nudgeSelection(-1, 0, 0, shift);
          return true;
        }
        case GLFW.GLFW_KEY_RIGHT -> {
          nudgeSelection(1, 0, 0, shift);
          return true;
        }
        case GLFW.GLFW_KEY_UP -> {
          nudgeSelection(0, 0, -1, shift);
          return true;
        }
        case GLFW.GLFW_KEY_DOWN -> {
          nudgeSelection(0, 0, 1, shift);
          return true;
        }
        case GLFW.GLFW_KEY_PAGE_UP -> {
          nudgeSelection(0, 1, 0, shift);
          return true;
        }
        case GLFW.GLFW_KEY_PAGE_DOWN -> {
          nudgeSelection(0, -1, 0, shift);
          return true;
        }
        default -> {
          // not a nudge key
        }
      }
    }
    if (!ctrl) {
      switch (keyCode) {
        case GLFW.GLFW_KEY_1 -> {
          setTool(EditTool.PLACE);
          return true;
        }
        case GLFW.GLFW_KEY_2 -> {
          setTool(EditTool.BREAK);
          return true;
        }
        case GLFW.GLFW_KEY_3 -> {
          setTool(EditTool.PICK);
          return true;
        }
        case GLFW.GLFW_KEY_4 -> {
          setTool(EditTool.REPLACE);
          return true;
        }
        case GLFW.GLFW_KEY_LEFT_BRACKET -> {
          brushRadius = Math.max(0, brushRadius - 1);
          setEditStatus();
          return true;
        }
        case GLFW.GLFW_KEY_RIGHT_BRACKET -> {
          brushRadius = Math.min(4, brushRadius + 1);
          setEditStatus();
          return true;
        }
        case GLFW.GLFW_KEY_DELETE -> {
          regionDelete();
          return true;
        }
        case GLFW.GLFW_KEY_H -> {
          showLegend = !showLegend;
          return true;
        }
        case GLFW.GLFW_KEY_M -> {
          cycleSymmetry();
          return true;
        }
        case GLFW.GLFW_KEY_BACKSLASH -> {
          scene.cycleSlice();
          return true;
        }
        case GLFW.GLFW_KEY_F -> {
          focusView();
          return true;
        }
        case GLFW.GLFW_KEY_G -> {
          scene.toggleGizmo();
          return true;
        }
        case GLFW.GLFW_KEY_P -> {
          scene.togglePerspective();
          return true;
        }
        case GLFW.GLFW_KEY_KP_2 -> {
          scene.setView(0.0f, 0.0f);
          return true;
        }
        case GLFW.GLFW_KEY_KP_8 -> {
          scene.setView(180.0f, 0.0f);
          return true;
        }
        case GLFW.GLFW_KEY_KP_4 -> {
          scene.setView(90.0f, 0.0f);
          return true;
        }
        case GLFW.GLFW_KEY_KP_6 -> {
          scene.setView(-90.0f, 0.0f);
          return true;
        }
        case GLFW.GLFW_KEY_KP_7 -> {
          scene.setView(0.0f, 89.0f);
          return true;
        }
        case GLFW.GLFW_KEY_KP_1 -> {
          scene.setView(0.0f, -89.0f);
          return true;
        }
        case GLFW.GLFW_KEY_KP_5 -> {
          scene.setView(35.0f, 25.0f);
          return true;
        }
        default -> {
          // fall through to vanilla handling
        }
      }
    }
    return super.keyPressed(keyCode, scanCode, modifiers);
  }

  @Override
  public void removed() {
    scene.close();
  }

  @Override
  public void onClose() {
    if (previous != null) {
      minecraft.setScreen(previous);
    } else {
      BuildPackScreen.open();
    }
  }

  // ---- Utilities ----

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
