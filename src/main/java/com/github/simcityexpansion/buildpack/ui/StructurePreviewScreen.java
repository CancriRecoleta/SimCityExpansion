package com.github.simcityexpansion.buildpack.ui;

import com.github.simcityexpansion.buildpack.convert.NbtStructure;
import com.github.simcityexpansion.buildpack.ui.preview.StructureScene;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Full-screen 3D preview: full-size real block models; left-click to rotate, right-click to pan,
 * scroll to zoom, middle-click/button to reset, peel top to see the interior.
 * "Back" returns to the manager screen that opened this view (preserving the selection).
 */
public final class StructurePreviewScreen extends Screen {

  private static final int PAD = 10;
  private static final int TITLE_H = 12;
  private static final int GAP = 4;
  private static final int BUTTON_H = 20;
  private static final int BUTTON_W = 70;

  private final Screen previous;
  private final NbtStructure structure;
  private final StructureScene scene;

  private StructurePreviewScreen(Screen previous, NbtStructure structure, StructureScene scene) {
    super(Component.translatable("buildpack.preview.screen_title"));
    this.previous = previous;
    this.structure = structure;
    this.scene = scene;
  }

  /** Opens the full-screen 3D preview (does nothing if the structure exceeds limits or cannot be rendered). */
  public static void open(NbtStructure structure) {
    StructureScene scene = new StructureScene(0, 0, 0, 0, true);
    if (!scene.setStructure(structure)) {
      return;
    }
    Minecraft mc = Minecraft.getInstance();
    mc.setScreen(new StructurePreviewScreen(mc.screen, structure, scene));
  }

  private int boxY() {
    return PAD + TITLE_H + GAP;
  }

  private int boxHeight() {
    return height - PAD - BUTTON_H - GAP - boxY();
  }

  @Override
  protected void init() {
    scene.setX(PAD + 1);
    scene.setY(boxY() + 1);
    scene.setWidth(width - PAD * 2 - 2);
    scene.setHeight(boxHeight() - 2);
    addRenderableWidget(scene);
    scene.ensureBaked();

    int by = height - PAD - BUTTON_H;
    int bx = PAD;
    addRenderableWidget(new ThemedButton(bx, by, BUTTON_W, BUTTON_H,
        Component.translatable("buildpack.action.back"), this::onClose));
    bx += BUTTON_W + GAP;
    addRenderableWidget(new ThemedButton(bx, by, BUTTON_W, BUTTON_H,
        Component.translatable("buildpack.preview.reset"), scene::resetView));
    bx += BUTTON_W + GAP;
    addRenderableWidget(new ThemedButton(bx, by, BUTTON_W, BUTTON_H,
        Component.translatable("buildpack.preview.peel"), scene::peelTop));
    bx += BUTTON_W + GAP;
    addRenderableWidget(new ThemedButton(bx, by, BUTTON_W, BUTTON_H,
        Component.translatable("buildpack.preview.unpeel"), scene::unpeelTop));
  }

  @Override
  public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
    // Override vanilla: no Gaussian-blur background.
  }

  @Override
  public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
    g.fill(0, 0, width, height, BuildPackTheme.ROOT_BG);
    BuildPackTheme.previewPanel(g, PAD, boxY(), width - PAD * 2, boxHeight());
    g.drawString(font, Component.translatable("buildpack.preview.screen_heading",
        structure.sizeX, structure.sizeY, structure.sizeZ, structure.countNonAir()),
        PAD + 10, PAD, BuildPackTheme.TITLE, true);
    int hintX = PAD + (BUTTON_W + GAP) * 4 + 4;
    g.drawString(font, Component.translatable("buildpack.preview.controls"),
        hintX, height - PAD - BUTTON_H + (BUTTON_H - font.lineHeight) / 2,
        BuildPackTheme.HINT, true);
    super.render(g, mouseX, mouseY, partialTick);
  }

  @Override
  public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
    // The vanilla container forwards only left-button drags to the focused widget;
    // right-button pan must be forwarded to the 3D scene manually.
    if (button != 0 && scene.isMouseOver(mouseX, mouseY)) {
      scene.applyDrag(button, dragX, dragY);
      return true;
    }
    return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
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
}
