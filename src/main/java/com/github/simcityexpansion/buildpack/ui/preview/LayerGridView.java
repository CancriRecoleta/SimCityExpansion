package com.github.simcityexpansion.buildpack.ui.preview;

import java.util.function.Consumer;

import com.github.simcityexpansion.buildpack.convert.NbtStructure;
import com.github.simcityexpansion.buildpack.convert.StructureAnalysis;
import com.github.simcityexpansion.buildpack.ui.BuildPackTheme;
import com.github.simcityexpansion.buildpack.ui.UiScale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/**
 * Top-down 2D layer editor view: renders one Y layer of the structure as a map-color grid (north
 * up, east right — the map orientation) and forwards cell clicks/drags to the host editor's
 * current tool. Precise interior work that the 3D view makes awkward: painting rooms, floors, and
 * furniture layouts layer by layer.
 *
 * <p>Controls: left click/drag applies the tool, scroll changes the layer, Ctrl+scroll zooms,
 * right-drag pans, middle-click resets the view.
 */
public final class LayerGridView extends AbstractWidget {

  private NbtStructure structure;
  private int[] paletteColors = new int[0];
  private int[] layerColors = new int[0];
  private int layer;

  private float zoom = 1.0f;
  private float panX;
  private float panY;

  /** Cell the pointer last applied the tool to during a drag (avoids re-applying per frame). */
  private long lastDragCell = Long.MIN_VALUE;
  private int hoverX = -1;
  private int hoverZ = -1;

  private Consumer<BlockPos> onCellAction = pos -> {};

  public LayerGridView(int x, int y, int width, int height) {
    super(x, y, width, height, Component.empty());
  }

  /** Sets the callback invoked with the clicked cell (current layer) for tool application. */
  public void setCellAction(Consumer<BlockPos> callback) {
    this.onCellAction = callback;
  }

  /** Binds a structure (or a new edit result); keeps the current layer when possible. */
  public void setStructure(NbtStructure structure) {
    this.structure = structure;
    if (structure == null) {
      paletteColors = new int[0];
      layerColors = new int[0];
      return;
    }
    paletteColors = StructureAnalysis.paletteMapColors(structure);
    layer = clamp(layer, 0, structure.sizeY - 1);
    rebuildLayer();
  }

  /** The current Y layer. */
  public int layer() {
    return layer;
  }

  /** Moves to another Y layer (clamped). */
  public void setLayer(int newLayer) {
    if (structure == null) {
      return;
    }
    int clamped = clamp(newLayer, 0, structure.sizeY - 1);
    if (clamped != layer) {
      layer = clamped;
      rebuildLayer();
    }
  }

  private void rebuildLayer() {
    layerColors = new int[structure.sizeX * structure.sizeZ];
    for (NbtStructure.BlockEntry block : structure.blocks) {
      if (block.y() != layer || block.x() < 0 || block.x() >= structure.sizeX
          || block.z() < 0 || block.z() >= structure.sizeZ) {
        continue;
      }
      int color = block.stateIndex() >= 0 && block.stateIndex() < paletteColors.length
          ? paletteColors[block.stateIndex()] : 0;
      layerColors[block.z() * structure.sizeX + block.x()] = color;
    }
  }

  /** Cell size in screen pixels at the current zoom (fit-to-widget × zoom). */
  private float cellSize() {
    if (structure == null) {
      return 1.0f;
    }
    float fit = Math.min(
        (getWidth() - 8) / (float) Math.max(1, structure.sizeX),
        (getHeight() - 8) / (float) Math.max(1, structure.sizeZ));
    return Math.max(0.5f, fit * zoom);
  }

  private float originX() {
    return getX() + getWidth() / 2.0f + panX - cellSize() * structure.sizeX / 2.0f;
  }

  private float originY() {
    return getY() + getHeight() / 2.0f + panY - cellSize() * structure.sizeZ / 2.0f;
  }

  @Override
  protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
    if (structure == null) {
      return;
    }
    UiScale.enableScissor(g, getX(), getY(), getX() + getWidth(), getY() + getHeight());
    float cell = cellSize();
    float ox = originX();
    float oy = originY();

    // Layer footprint backdrop + below-layer ghosting is skipped for speed; empty cells stay dark.
    g.fill(Math.round(ox), Math.round(oy),
        Math.round(ox + cell * structure.sizeX), Math.round(oy + cell * structure.sizeZ),
        0x40000000);

    int x0 = Math.max(0, (int) Math.floor((getX() - ox) / cell));
    int x1 = Math.min(structure.sizeX - 1, (int) Math.ceil((getX() + getWidth() - ox) / cell));
    int z0 = Math.max(0, (int) Math.floor((getY() - oy) / cell));
    int z1 = Math.min(structure.sizeZ - 1, (int) Math.ceil((getY() + getHeight() - oy) / cell));
    for (int z = z0; z <= z1; z++) {
      for (int x = x0; x <= x1; x++) {
        int color = layerColors[z * structure.sizeX + x];
        if (color == 0) {
          continue;
        }
        int px0 = Math.round(ox + x * cell);
        int py0 = Math.round(oy + z * cell);
        int px1 = Math.max(px0 + 1, Math.round(ox + (x + 1) * cell) - (cell >= 6.0f ? 1 : 0));
        int py1 = Math.max(py0 + 1, Math.round(oy + (z + 1) * cell) - (cell >= 6.0f ? 1 : 0));
        g.fill(px0, py0, px1, py1, color);
      }
    }

    updateHover(mouseX, mouseY);
    if (hoverX >= 0) {
      int px0 = Math.round(ox + hoverX * cell);
      int py0 = Math.round(oy + hoverZ * cell);
      BuildPackTheme.border(g, px0, py0,
          Math.max(2, Math.round(cell)), Math.max(2, Math.round(cell)), 0xFFFFEE55);
    }

    Minecraft mc = Minecraft.getInstance();
    g.drawString(mc.font, Component.translatable("buildpack.grid.layer", layer,
        structure.sizeY - 1), getX() + 4, getY() + 4, 0xFFFFFFFF, true);
    g.drawString(mc.font, Component.translatable("buildpack.grid.north"),
        getX() + getWidth() - mc.font.width(Component.translatable("buildpack.grid.north")) - 4,
        getY() + 4, 0xFFFF5555, true);
    if (hoverX >= 0) {
      String info = hoverX + "," + layer + "," + hoverZ;
      g.drawString(mc.font, info, getX() + 4,
          getY() + getHeight() - mc.font.lineHeight - 3, 0xFFFFD080, true);
    }
    g.disableScissor();
  }

  private void updateHover(double mouseX, double mouseY) {
    hoverX = -1;
    hoverZ = -1;
    if (structure == null || !isMouseOver(mouseX, mouseY)) {
      return;
    }
    float cell = cellSize();
    int cx = (int) Math.floor((mouseX - originX()) / cell);
    int cz = (int) Math.floor((mouseY - originY()) / cell);
    if (cx >= 0 && cx < structure.sizeX && cz >= 0 && cz < structure.sizeZ) {
      hoverX = cx;
      hoverZ = cz;
    }
  }

  @Override
  public boolean mouseClicked(double mouseX, double mouseY, int button) {
    if (!visible || !active || structure == null || !isMouseOver(mouseX, mouseY)) {
      return false;
    }
    if (button == 2) {
      zoom = 1.0f;
      panX = 0.0f;
      panY = 0.0f;
      return true;
    }
    if (button == 0) {
      lastDragCell = Long.MIN_VALUE;
      applyAt(mouseX, mouseY);
    }
    // Consume right-click too so the host forwards subsequent drag events for panning.
    return true;
  }

  @Override
  public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
    if (!visible || !active || structure == null) {
      return false;
    }
    if (button == 0) {
      applyAt(mouseX, mouseY);
      return true;
    }
    return false;
  }

  /** Right-drag pan, forwarded by the host screen (vanilla only forwards left-button drags). */
  public void applyPan(double dragX, double dragY) {
    panX += (float) dragX;
    panY += (float) dragY;
  }

  private void applyAt(double mouseX, double mouseY) {
    updateHover(mouseX, mouseY);
    if (hoverX < 0) {
      return;
    }
    long key = ((long) hoverX << 32) | (hoverZ & 0xFFFFFFFFL);
    if (key == lastDragCell) {
      return;
    }
    lastDragCell = key;
    onCellAction.accept(new BlockPos(hoverX, layer, hoverZ));
  }

  @Override
  public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
    if (!visible || !active || structure == null || !isMouseOver(mouseX, mouseY)) {
      return false;
    }
    if (net.minecraft.client.gui.screens.Screen.hasControlDown()) {
      zoom = Math.max(0.25f, Math.min(16.0f, zoom * (scrollY > 0 ? 1.2f : 0.83f)));
    } else {
      setLayer(layer + (scrollY > 0 ? 1 : -1));
    }
    return true;
  }

  private static int clamp(int v, int lo, int hi) {
    return Math.max(lo, Math.min(v, hi));
  }

  @Override
  protected void updateWidgetNarration(NarrationElementOutput output) {}
}
