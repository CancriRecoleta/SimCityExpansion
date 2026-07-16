package com.github.simcityexpansion.buildpack.ui;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Consumer;

import com.github.simcityexpansion.buildpack.convert.NbtStructure;
import com.github.simcityexpansion.buildpack.ui.preview.StructureScene;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/**
 * 3D coordinate picker for definition authoring: shows the building in an interactive
 * {@link StructureScene} where clicking a block toggles its structure-local coordinate in the
 * picked set (highlighted markers, visible through walls). Confirming hands the list back to the
 * definition editor, which writes it into the point/container/step being edited. Rotation, pan,
 * zoom, and slice peeling all work as in the regular preview, so interior chests are reachable.
 */
public final class PositionPickerScreen extends Screen {

  private static final int MARGIN = 10;
  private static final int BUTTON_H = 18;
  private static final int BUTTON_W = 90;

  private final Screen parent;
  private final StructureScene scene;
  private final LinkedHashSet<BlockPos> picked;
  private final Consumer<List<BlockPos>> onConfirm;

  private PositionPickerScreen(Screen parent, StructureScene scene, List<BlockPos> initial,
      Consumer<List<BlockPos>> onConfirm) {
    super(Component.translatable("buildpack.picker.title"));
    this.parent = parent;
    this.scene = scene;
    this.picked = new LinkedHashSet<>(initial);
    this.onConfirm = onConfirm;
  }

  /**
   * Opens the picker over the current screen; returns false (without switching screens) when the
   * structure cannot be rendered at all. {@code onConfirm} only runs on confirm, not on cancel.
   */
  public static boolean open(NbtStructure structure, List<BlockPos> initial,
      Consumer<List<BlockPos>> onConfirm) {
    StructureScene scene = new StructureScene(0, 0, 0, 0, true);
    if (!scene.setStructure(structure)) {
      return false;
    }
    scene.setEditMode(true);
    Minecraft mc = Minecraft.getInstance();
    mc.setScreen(new PositionPickerScreen(mc.screen, scene, initial, onConfirm));
    return true;
  }

  @Override
  protected void init() {
    int buttonsY = height - MARGIN - BUTTON_H;
    scene.setX(MARGIN);
    scene.setY(MARGIN + 14);
    scene.setWidth(width - MARGIN * 2);
    scene.setHeight(buttonsY - 14 - MARGIN * 2);
    scene.ensureBaked();
    scene.setEditCallback(this::togglePicked);
    scene.setMarkers(List.copyOf(picked));
    addRenderableWidget(scene);

    // Slice controls so interior blocks (machine rooms, storage cellars) stay reachable.
    int sliceW = 50;
    addRenderableWidget(new ThemedButton(MARGIN, buttonsY, sliceW, BUTTON_H,
        Component.translatable("buildpack.preview.peel"), scene::peelTop));
    addRenderableWidget(new ThemedButton(MARGIN + sliceW + 4, buttonsY, sliceW, BUTTON_H,
        Component.translatable("buildpack.preview.unpeel"), scene::unpeelTop));
    addRenderableWidget(new ThemedButton(MARGIN + (sliceW + 4) * 2, buttonsY, sliceW, BUTTON_H,
        Component.translatable("buildpack.preview.reset"), scene::resetView));

    int totalW = BUTTON_W * 3 + 8;
    int x = Math.max((width - totalW) / 2, MARGIN + (sliceW + 4) * 3 + 8);
    addRenderableWidget(new ThemedButton(x, buttonsY, BUTTON_W, BUTTON_H,
        Component.translatable("buildpack.prompt.confirm"), this::confirm));
    addRenderableWidget(new ThemedButton(x + BUTTON_W + 4, buttonsY, BUTTON_W, BUTTON_H,
        Component.translatable("buildpack.picker.clear"), () -> {
          picked.clear();
          scene.setMarkers(List.of());
        }));
    addRenderableWidget(new ThemedButton(x + (BUTTON_W + 4) * 2, buttonsY, BUTTON_W, BUTTON_H,
        Component.translatable("buildpack.prompt.cancel"), this::onClose));
  }

  private void togglePicked(StructureScene.Hit hit) {
    BlockPos pos = hit.pos();
    if (!picked.remove(pos)) {
      picked.add(pos);
    }
    scene.setMarkers(List.copyOf(picked));
  }

  private void confirm() {
    onConfirm.accept(List.copyOf(picked));
    minecraft.setScreen(parent);
  }

  /** Forward right-button drags to the scene (vanilla only forwards left-button drags). */
  @Override
  public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX,
      double dragY) {
    if (button == 1 && scene.isMouseOver(mouseX, mouseY)) {
      scene.applyDrag(1, dragX, dragY);
      return true;
    }
    return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
  }

  @Override
  public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
    g.fill(0, 0, width, height, 0xF2101010);
  }

  @Override
  public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
    BuildPackTheme.previewPanel(g, MARGIN, MARGIN + 14, width - MARGIN * 2,
        height - MARGIN - BUTTON_H - 14 - MARGIN * 2);
    super.render(g, mouseX, mouseY, partialTick);
    g.drawString(font, title, MARGIN, MARGIN, BuildPackTheme.TITLE, true);
    Component count = Component.translatable("buildpack.picker.count", picked.size());
    g.drawString(font, count, width - MARGIN - font.width(count), MARGIN,
        BuildPackTheme.VALUE, true);
    Component hint = Component.translatable(scene.supportsPicking()
        ? "buildpack.picker.hint" : "buildpack.picker.too_large");
    g.drawString(font, hint, (width - font.width(hint)) / 2,
        height - MARGIN - BUTTON_H - 12,
        scene.supportsPicking() ? BuildPackTheme.HINT : BuildPackTheme.MESSAGE_WARN, true);
  }

  @Override
  public void removed() {
    scene.close();
  }

  @Override
  public void onClose() {
    minecraft.setScreen(parent);
  }
}
