package com.github.simcityexpansion.buildpack.ui.preview;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import com.github.simcityexpansion.buildpack.convert.NbtStructure;
import com.github.simcityexpansion.buildpack.convert.StructureAnalysis;
import com.github.simcityexpansion.buildpack.ui.StructurePreviewScreen;
import com.github.simcityexpansion.buildpack.ui.UiScale;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL11;

/**
 * 3D preview rendering real block models, with three tiers based on structure size:
 * <ul>
 *   <li><b>≤ {@value #IMMEDIATE_LIMIT}</b>: immediate per-block rendering every frame (original behavior).</li>
 *   <li><b>≤ {@value #DETAIL_LIMIT}</b>: real block models baked into VBOs by Y layer (adjacency culling,
 *       incremental per-frame baking, slice changes only update the layer count).</li>
 *   <li><b>Larger (up to {@value #LOD_MAX})</b>: <b>voxel LOD</b> — downsampled into colored cubes
 *       on an N³ grid (each cell takes the map color of its highest block, with inter-cell adjacency
 *       culling), rendered with a {@code POSITION_COLOR} vertex buffer, enabling interactive 3D
 *       preview of million-block structures (at reduced detail).</li>
 * </ul>
 *
 * <p>Blocks drawn by a {@link BlockEntityRenderer} (chests, signs, beds, banners...) have no
 * usable baked model, so the immediate and VBO tiers exclude them from the static geometry and
 * re-render them live each frame in {@link #drawBlockEntities}; the voxel LOD tier keeps its
 * map-color cubes.
 */
public final class StructureScene extends AbstractWidget {

  /** Structures at or below this count use immediate per-block rendering. */
  private static final int IMMEDIATE_LIMIT = 6000;
  /** Structures at or below this count use real-model VBO baking; larger ones use voxel LOD. */
  private static final int DETAIL_LIMIT = 200_000;
  /** Maximum non-air block count handled by voxel LOD (beyond this the preview is skipped). */
  private static final int LOD_MAX = 20_000_000;
  /** LOD target resolution: approximate number of voxel cells along the longest axis. */
  private static final int LOD_TARGET_RES = 48;
  /** Blocks processed per frame during incremental baking (amortizes baking cost). */
  private static final int BAKE_BUDGET = 8000;
  /** Maximum number of block entities rendered live per structure (protects the frame rate). */
  private static final int BLOCK_ENTITY_LIMIT = 2048;

  private record PosState(BlockPos pos, BlockState state) {}

  /** A block entity rendered live each frame at its structure-local position. */
  private record PosEntity(BlockPos pos, BlockEntity entity) {}

  /** A raycast result in the editor: the hit cell and the face the ray entered through. */
  public record Hit(BlockPos pos, Direction face) {}

  private final boolean interactive;
  private final List<PosState> blocks = new ArrayList<>();
  private final List<PosEntity> blockEntities = new ArrayList<>();
  private final Map<Long, BlockState> grid = new HashMap<>();
  private NbtStructure structure;

  // Detailed VBO (per layer).
  private boolean useVbo;
  private List<List<PosState>> layerBlocks;
  private VertexBuffer[] layerBuffers;
  private boolean baking;
  private int bakeLayer;

  // Voxel LOD.
  private boolean useLod;
  private int[] lodColor;
  private int lodN;
  private int lodGx;
  private int lodGy;
  private int lodGz;
  private VertexBuffer lodBuffer;

  // Selection highlight (editor).
  private boolean hasSelection;
  private int selX0;
  private int selY0;
  private int selZ0;
  private int selX1;
  private int selY1;
  private int selZ1;
  private VertexBuffer selectionBuffer;

  // Single-block editing (editor).
  private boolean editMode;
  private Consumer<Hit> onEdit;
  private Hit hover;
  private VertexBuffer hoverBuffer;
  private boolean dragged;

  // Region selection picking (editor): click two corner cells to set the selection.
  private boolean selectMode;
  private BlockPos selAnchor;
  private BlockPos selPreviewEnd;
  private BiConsumer<BlockPos, BlockPos> onSelect;
  private Direction.Axis selPlaneAxis = Direction.Axis.Y;
  private int selPlaneCoord;
  private int dragFace = -1;

  private float centerX;
  private float centerY;
  private float centerZ;
  private int maxDim = 1;
  private int structHeight = 1;
  private int structWidth = 1;
  private int structDepth = 1;

  private float yaw = 35.0f;
  private float pitch = 25.0f;
  private float zoom = 1.0f;
  private float panX;
  private float panY;
  // Smooth-camera targets; drag/zoom set both (instant), presets/fit/focus set only the target.
  private float targetYaw = 35.0f;
  private float targetPitch = 25.0f;
  private float targetZoom = 1.0f;
  private float targetPanX;
  private float targetPanY;
  private float targetCenterX;
  private float targetCenterY;
  private float targetCenterZ;
  /** Six-sided clip box (min inclusive, max exclusive); only blocks inside are rendered (multi-axis slice for interior views). */
  private int clipMinX;
  private int clipMinY;
  private int clipMinZ;
  private int clipMaxX = Integer.MAX_VALUE;
  private int clipMaxY = Integer.MAX_VALUE;
  private int clipMaxZ = Integer.MAX_VALUE;
  /** Current slice target: 0=Y top, 1=Y bottom, 2=X+, 3=X-, 4=Z+, 5=Z-. */
  private int sliceTarget;
  private boolean showGizmo;
  private VertexBuffer decorBuffer;
  private boolean perspective;

  public StructureScene(int x, int y, int width, int height, boolean interactive) {
    super(x, y, width, height, Component.empty());
    this.interactive = interactive;
  }

  /** Parses the structure and resets the camera; returns false if it is empty or exceeds the size limit. */
  public boolean setStructure(NbtStructure s) {
    return setStructure(s, true);
  }

  /** Parses the structure; when {@code resetCamera=false} the current view is preserved (no camera jump after editor transforms). */
  public boolean setStructure(NbtStructure s, boolean resetCamera) {
    blocks.clear();
    blockEntities.clear();
    grid.clear();
    cancelBake();
    closeBuffers();
    closeLod();
    clearSelection();
    hover = null;
    closeHoverBuffer();
    closeDecor();
    layerBlocks = null;
    useVbo = false;
    useLod = false;
    lodColor = null;
    structure = s;
    if (s == null) {
      return false;
    }
    long count = s.countNonAir();
    if (count <= 0L || count > LOD_MAX) {
      return false;
    }
    centerX = s.sizeX / 2.0f;
    centerY = s.sizeY / 2.0f;
    centerZ = s.sizeZ / 2.0f;
    targetCenterX = centerX;
    targetCenterY = centerY;
    targetCenterZ = centerZ;
    maxDim = Math.max(1, Math.max(s.sizeX, Math.max(s.sizeY, s.sizeZ)));
    structHeight = Math.max(1, s.sizeY);
    structWidth = Math.max(1, s.sizeX);
    structDepth = Math.max(1, s.sizeZ);
    if (resetCamera) {
      resetCamera();
    }
    resetClip();

    if (count > DETAIL_LIMIT) {
      useLod = true;
      if (!buildLod(s)) {
        useLod = false;
        return false;
      }
      return true;
    }
    if (!buildDetailed(s)) {
      return false;
    }
    useVbo = blocks.size() > IMMEDIATE_LIMIT;
    if (useVbo) {
      groupLayers();
      startBake();
    }
    return true;
  }

  /** Parses blocks into real block model data (shared by the immediate and detailed VBO paths). */
  private boolean buildDetailed(NbtStructure s) {
    HolderGetter<Block> lookup = BuiltInRegistries.BLOCK.asLookup();
    BlockState[] palette = new BlockState[s.palette.size()];
    for (int i = 0; i < palette.length; i++) {
      NbtStructure.PaletteEntry entry = s.palette.get(i);
      if (entry.isAir()) {
        continue;
      }
      CompoundTag tag = new CompoundTag();
      tag.putString("Name", entry.blockName());
      if (entry.properties() != null) {
        tag.put("Properties", entry.properties());
      }
      try {
        BlockState state = NbtUtils.readBlockState(lookup, tag);
        palette[i] = state.isAir() ? null : state;
      } catch (RuntimeException ignored) {
        palette[i] = null;
      }
    }
    for (NbtStructure.BlockEntry b : s.blocks) {
      int si = b.stateIndex();
      if (si < 0 || si >= palette.length || palette[si] == null) {
        continue;
      }
      BlockState state = palette[si];
      BlockPos pos = new BlockPos(b.x(), b.y(), b.z());
      grid.put(encode(b.x(), b.y(), b.z()), state);
      // ENTITYBLOCK_ANIMATED states must not reach renderSingleBlock: it falls back to the item
      // renderer, which ignores the block state and bakes foreign-atlas UVs into the block-atlas
      // VBO (garbled chests). They render through their block entity below instead.
      if (state.getRenderShape() != RenderShape.ENTITYBLOCK_ANIMATED) {
        blocks.add(new PosState(pos, state));
      }
      if (state.hasBlockEntity()) {
        addBlockEntity(pos, state, b.nbt());
      }
    }
    return !blocks.isEmpty() || !blockEntities.isEmpty();
  }

  /**
   * Creates the block entity backing a renderer-driven block (chest, sign, bed...) so
   * {@link #drawBlockEntities} can draw it with its real model. Attaching the client level makes
   * vanilla renderers honor the block state (facing, double-chest halves, bed part) instead of
   * using their level-less item fallback.
   */
  private void addBlockEntity(BlockPos pos, BlockState state, @Nullable CompoundTag nbt) {
    if (blockEntities.size() >= BLOCK_ENTITY_LIMIT
        || !(state.getBlock() instanceof EntityBlock entityBlock)) {
      return;
    }
    Minecraft mc = Minecraft.getInstance();
    try {
      BlockEntity entity = entityBlock.newBlockEntity(pos, state);
      if (entity == null || mc.getBlockEntityRenderDispatcher().getRenderer(entity) == null) {
        return;
      }
      if (mc.level != null) {
        if (nbt != null) {
          try {
            entity.loadWithComponents(nbt, mc.level.registryAccess());
          } catch (RuntimeException ignored) {
            // Corrupt or foreign data only costs the extra detail (sign text, banner pattern).
          }
        }
        entity.setLevel(mc.level);
      }
      blockEntities.add(new PosEntity(pos, entity));
    } catch (RuntimeException ignored) {
      // A block entity that cannot be built outside a real level is skipped; the rest of the
      // preview still renders.
    }
  }

  /** Resets the view (rotation/zoom/pan/slice). */
  public void resetView() {
    resetCamera();
    resetClip();
    if (useVbo) {
      startBake();
    }
    if (useLod) {
      bakeLod();
    }
  }

  /** Peel the current slice plane inward by one (reveals interior). */
  public void peelTop() {
    switch (sliceTarget) {
      case 0 -> clipMaxY = Math.max(clipMinY + 1, clipMaxY - 1);
      case 1 -> clipMinY = Math.min(clipMaxY - 1, clipMinY + 1);
      case 2 -> clipMaxX = Math.max(clipMinX + 1, clipMaxX - 1);
      case 3 -> clipMinX = Math.min(clipMaxX - 1, clipMinX + 1);
      case 4 -> clipMaxZ = Math.max(clipMinZ + 1, clipMaxZ - 1);
      default -> clipMinZ = Math.min(clipMaxZ - 1, clipMinZ + 1);
    }
    afterSliceChange();
  }

  /** Restore the current slice plane outward by one. */
  public void unpeelTop() {
    switch (sliceTarget) {
      case 0 -> clipMaxY = Math.min(structHeight, clipMaxY + 1);
      case 1 -> clipMinY = Math.max(0, clipMinY - 1);
      case 2 -> clipMaxX = Math.min(structWidth, clipMaxX + 1);
      case 3 -> clipMinX = Math.max(0, clipMinX - 1);
      case 4 -> clipMaxZ = Math.min(structDepth, clipMaxZ + 1);
      default -> clipMinZ = Math.max(0, clipMinZ - 1);
    }
    afterSliceChange();
  }

  /** Cycle which slice plane peel/unpeel act on (Y top → Y bottom → X± → Z±). */
  public void cycleSlice() {
    sliceTarget = (sliceTarget + 1) % 6;
  }

  /** Snap the camera to a fixed viewing angle (presets); keeps zoom/pan. */
  public void setView(float newYaw, float newPitch) {
    targetYaw = newYaw;
    targetPitch = newPitch;
  }

  /** Reset zoom/pan and recenter on the whole structure. */
  public void fitAll() {
    if (structure != null) {
      targetCenterX = structure.sizeX / 2.0f;
      targetCenterY = structure.sizeY / 2.0f;
      targetCenterZ = structure.sizeZ / 2.0f;
    }
    targetZoom = 1.0f;
    targetPanX = 0.0f;
    targetPanY = 0.0f;
  }

  /** Center on a point and zoom so a region of the given span roughly fills the view. */
  public void focus(float cx, float cy, float cz, int span) {
    targetCenterX = cx;
    targetCenterY = cy;
    targetCenterZ = cz;
    targetZoom = Math.max(0.25f, Math.min(6.0f, (float) maxDim / Math.max(1, span)));
    targetPanX = 0.0f;
    targetPanY = 0.0f;
  }

  /** Releases GPU buffers and any in-progress baking (call when replacing the preview or closing the screen). */
  public void close() {
    cancelBake();
    closeBuffers();
    closeLod();
    closeSelectionBuffer();
    closeHoverBuffer();
    closeDecor();
  }

  /** Sets the highlighted selection region (inclusive bounds, automatically normalized). */
  public void setSelection(int x0, int y0, int z0, int x1, int y1, int z1) {
    selX0 = Math.min(x0, x1);
    selY0 = Math.min(y0, y1);
    selZ0 = Math.min(z0, z1);
    selX1 = Math.max(x0, x1);
    selY1 = Math.max(y0, y1);
    selZ1 = Math.max(z0, z1);
    hasSelection = true;
    buildSelectionBuffer();
  }

  /** Clears the selection highlight and cancels any in-progress box-select. */
  public void clearSelection() {
    hasSelection = false;
    selAnchor = null;
    selPreviewEnd = null;
    dragFace = -1;
    closeSelectionBuffer();
  }

  /** Enable single-block edit mode (a left-click without dragging picks a cell). */
  public void setEditMode(boolean on) {
    editMode = on;
    if (!on) {
      hover = null;
      closeHoverBuffer();
    }
  }

  /** Set the callback invoked with the hit cell on a left-click (no drag) while in edit mode. */
  public void setEditCallback(Consumer<Hit> callback) {
    onEdit = callback;
  }

  /** Enable region-selection mode: click two cells in the 3D view to set the selection corners. */
  public void setSelectMode(boolean on) {
    selectMode = on;
    selAnchor = null;
    selPreviewEnd = null;
    dragFace = -1;
    if (!on) {
      hover = null;
      closeHoverBuffer();
    }
  }

  /** Set the callback invoked with the two corner cells when a box selection is completed. */
  public void setSelectCallback(BiConsumer<BlockPos, BlockPos> callback) {
    onSelect = callback;
  }

  /** Registry id of the block at a cell, or null if empty (eyedropper support). */
  public String blockIdAt(BlockPos pos) {
    BlockState state = grid.get(encode(pos.getX(), pos.getY(), pos.getZ()));
    return state == null ? null : BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
  }

  /** Full block state at a cell as {@code name[k=v,...]} (or null if empty); used by the eyedropper. */
  public String blockStateAt(BlockPos pos) {
    BlockState state = grid.get(encode(pos.getX(), pos.getY(), pos.getZ()));
    if (state == null) {
      return null;
    }
    CompoundTag tag = NbtUtils.writeBlockState(state);
    String name = tag.getString("Name");
    if (!tag.contains("Properties")) {
      return name;
    }
    CompoundTag props = tag.getCompound("Properties");
    if (props.isEmpty()) {
      return name;
    }
    StringBuilder sb = new StringBuilder(name).append('[');
    boolean first = true;
    for (String key : new java.util.TreeSet<>(props.getAllKeys())) {
      if (!first) {
        sb.append(',');
      }
      sb.append(key).append('=').append(props.getString(key));
      first = false;
    }
    return sb.append(']').toString();
  }

  /** Re-bakes if buffers were released (e.g., after returning from a screen switch). */
  public void ensureBaked() {
    if (useVbo && layerBuffers == null && !baking && layerBlocks != null) {
      startBake();
    }
    if (useLod && lodBuffer == null && lodColor != null) {
      bakeLod();
    }
  }

  private void resetCamera() {
    yaw = 35.0f;
    pitch = 25.0f;
    zoom = 1.0f;
    panX = 0.0f;
    panY = 0.0f;
    targetYaw = 35.0f;
    targetPitch = 25.0f;
    targetZoom = 1.0f;
    targetPanX = 0.0f;
    targetPanY = 0.0f;
  }

  /** Ease the live camera toward its target each frame (smooth presets/fit/focus). */
  private void lerpCamera() {
    float f = 0.35f;
    yaw += (targetYaw - yaw) * f;
    pitch += (targetPitch - pitch) * f;
    zoom += (targetZoom - zoom) * f;
    panX += (targetPanX - panX) * f;
    panY += (targetPanY - panY) * f;
    centerX += (targetCenterX - centerX) * f;
    centerY += (targetCenterY - centerY) * f;
    centerZ += (targetCenterZ - centerZ) * f;
  }

  private void resetClip() {
    clipMinX = 0;
    clipMinY = 0;
    clipMinZ = 0;
    clipMaxX = structWidth;
    clipMaxY = structHeight;
    clipMaxZ = structDepth;
    sliceTarget = 0;
  }

  private boolean inClip(int x, int y, int z) {
    return x >= clipMinX && x < clipMaxX && y >= clipMinY && y < clipMaxY
        && z >= clipMinZ && z < clipMaxZ;
  }

  private void afterSliceChange() {
    if (useLod) {
      bakeLod();
    } else if (useVbo && sliceTarget >= 2) {
      startBake();
    }
  }

  // ---- Detailed: per-layer mesh baking (incremental, spread across frames) ----

  private void groupLayers() {
    layerBlocks = new ArrayList<>(structHeight);
    for (int i = 0; i < structHeight; i++) {
      layerBlocks.add(new ArrayList<>());
    }
    for (PosState ps : blocks) {
      int ly = ps.pos().getY();
      if (ly >= 0 && ly < structHeight) {
        layerBlocks.get(ly).add(ps);
      }
    }
  }

  private void startBake() {
    cancelBake();
    closeBuffers();
    if (layerBlocks == null || layerBlocks.isEmpty()) {
      return;
    }
    layerBuffers = new VertexBuffer[layerBlocks.size()];
    bakeLayer = 0;
    baking = true;
  }

  private void stepBake() {
    int budget = BAKE_BUDGET;
    while (bakeLayer < layerBuffers.length && budget > 0) {
      List<PosState> layer = layerBlocks.get(bakeLayer);
      layerBuffers[bakeLayer] = bakeOneLayer(layer);
      budget -= Math.max(1, layer.size());
      bakeLayer++;
    }
    if (bakeLayer >= layerBuffers.length) {
      baking = false;
    }
  }

  private VertexBuffer bakeOneLayer(List<PosState> layer) {
    if (layer.isEmpty()) {
      return null;
    }
    BlockRenderDispatcher dispatcher = Minecraft.getInstance().getBlockRenderer();
    try (ByteBufferBuilder bytes = new ByteBufferBuilder(256 * 1024)) {
      BufferBuilder builder =
          new BufferBuilder(bytes, VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK);
      MultiBufferSource source = renderType -> builder;
      PoseStack pose = new PoseStack();
      for (PosState ps : layer) {
        int px = ps.pos().getX();
        int pz = ps.pos().getZ();
        if (occluded(ps.pos()) || px < clipMinX || px >= clipMaxX || pz < clipMinZ || pz >= clipMaxZ) {
          continue;
        }
        pose.pushPose();
        pose.translate(ps.pos().getX(), ps.pos().getY(), ps.pos().getZ());
        try {
          dispatcher.renderSingleBlock(ps.state(), pose, source,
              LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
        } catch (Throwable ignored) {
          // Skip individual block models that throw exceptions.
        }
        pose.popPose();
      }
      MeshData mesh = builder.build();
      if (mesh == null) {
        return null;
      }
      VertexBuffer buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
      try {
        buffer.bind();
        buffer.upload(mesh);
        VertexBuffer.unbind();
        return buffer;
      } catch (Throwable t) {
        buffer.close();
        return null;
      }
    } catch (Throwable t) {
      return null;
    }
  }

  private boolean occluded(BlockPos pos) {
    for (Direction dir : Direction.values()) {
      BlockState neighbor = grid.get(encode(
          pos.getX() + dir.getStepX(), pos.getY() + dir.getStepY(), pos.getZ() + dir.getStepZ()));
      if (neighbor == null || !neighbor.canOcclude()) {
        return false;
      }
    }
    return true;
  }

  private static long encode(int x, int y, int z) {
    return ((long) (y & 0x1FFFFF) << 42) | ((long) (z & 0x1FFFFF) << 21) | (x & 0x1FFFFF);
  }

  private void cancelBake() {
    baking = false;
  }

  private void closeBuffers() {
    if (layerBuffers != null) {
      for (VertexBuffer buffer : layerBuffers) {
        if (buffer != null) {
          buffer.close();
        }
      }
      layerBuffers = null;
    }
  }

  // ---- Voxel LOD (large structures) ----

  /** Downsamples the structure into a colored cube mesh on an N³ grid; returns true if baking succeeded. */
  private boolean buildLod(NbtStructure s) {
    int[] colors = StructureAnalysis.paletteMapColors(s);
    lodN = Math.max(1, (maxDim + LOD_TARGET_RES - 1) / LOD_TARGET_RES);
    lodGx = Math.max(1, (s.sizeX + lodN - 1) / lodN);
    lodGy = Math.max(1, (s.sizeY + lodN - 1) / lodN);
    lodGz = Math.max(1, (s.sizeZ + lodN - 1) / lodN);
    int[] color = new int[lodGx * lodGy * lodGz];
    int[] topY = new int[color.length];
    Arrays.fill(topY, Integer.MIN_VALUE);
    boolean any = false;
    for (NbtStructure.BlockEntry b : s.blocks) {
      int si = b.stateIndex();
      if (si < 0 || si >= colors.length || colors[si] == 0) {
        continue;
      }
      int cx = b.x() / lodN;
      int cy = b.y() / lodN;
      int cz = b.z() / lodN;
      if (cx < 0 || cx >= lodGx || cy < 0 || cy >= lodGy || cz < 0 || cz >= lodGz) {
        continue;
      }
      int idx = (cy * lodGz + cz) * lodGx + cx;
      if (b.y() > topY[idx]) {
        topY[idx] = b.y();
        color[idx] = colors[si];
        any = true;
      }
    }
    if (!any) {
      return false;
    }
    lodColor = color;
    bakeLod();
    return lodBuffer != null;
  }

  /** Bakes the colored cubes (with inter-cell adjacency culling and clipMaxY top slice) into a POSITION_COLOR buffer. */
  private void bakeLod() {
    closeLod();
    if (lodColor == null) {
      return;
    }
    try (ByteBufferBuilder bytes = new ByteBufferBuilder(512 * 1024)) {
      BufferBuilder builder =
          new BufferBuilder(bytes, VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
      for (int cy = 0; cy < lodGy; cy++) {
        if (cy * lodN >= clipMaxY) {
          break;
        }
        for (int cz = 0; cz < lodGz; cz++) {
          for (int cx = 0; cx < lodGx; cx++) {
            int col = lodColor[(cy * lodGz + cz) * lodGx + cx];
            if (col == 0) {
              continue;
            }
            float x0 = cx * lodN;
            float y0 = cy * lodN;
            float z0 = cz * lodN;
            float x1 = Math.min((cx + 1) * lodN, structure.sizeX);
            float y1 = Math.min(Math.min((cy + 1) * lodN, structure.sizeY), clipMaxY);
            float z1 = Math.min((cz + 1) * lodN, structure.sizeZ);
            if (y1 <= y0) {
              continue;
            }
            emitCube(builder, cx, cy, cz, x0, y0, z0, x1, y1, z1, col);
          }
        }
      }
      MeshData mesh = builder.build();
      if (mesh == null) {
        return;
      }
      VertexBuffer buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
      try {
        buffer.bind();
        buffer.upload(mesh);
        VertexBuffer.unbind();
        lodBuffer = buffer;
      } catch (Throwable t) {
        buffer.close();
        lodBuffer = null;
      }
    } catch (Throwable t) {
      lodBuffer = null;
    }
  }

  private void emitCube(BufferBuilder b, int cx, int cy, int cz,
      float x0, float y0, float z0, float x1, float y1, float z1, int col) {
    if ((cy + 1) * lodN >= clipMaxY || cellEmpty(cx, cy + 1, cz)) {
      int c = shade(col, 1.0f);
      vertex(b, x0, y1, z0, c);
      vertex(b, x0, y1, z1, c);
      vertex(b, x1, y1, z1, c);
      vertex(b, x1, y1, z0, c);
    }
    if (cellEmpty(cx, cy - 1, cz)) {
      int c = shade(col, 0.5f);
      vertex(b, x0, y0, z0, c);
      vertex(b, x1, y0, z0, c);
      vertex(b, x1, y0, z1, c);
      vertex(b, x0, y0, z1, c);
    }
    if (cellEmpty(cx, cy, cz - 1)) {
      int c = shade(col, 0.8f);
      vertex(b, x0, y0, z0, c);
      vertex(b, x0, y1, z0, c);
      vertex(b, x1, y1, z0, c);
      vertex(b, x1, y0, z0, c);
    }
    if (cellEmpty(cx, cy, cz + 1)) {
      int c = shade(col, 0.8f);
      vertex(b, x0, y0, z1, c);
      vertex(b, x1, y0, z1, c);
      vertex(b, x1, y1, z1, c);
      vertex(b, x0, y1, z1, c);
    }
    if (cellEmpty(cx - 1, cy, cz)) {
      int c = shade(col, 0.6f);
      vertex(b, x0, y0, z0, c);
      vertex(b, x0, y0, z1, c);
      vertex(b, x0, y1, z1, c);
      vertex(b, x0, y1, z0, c);
    }
    if (cellEmpty(cx + 1, cy, cz)) {
      int c = shade(col, 0.6f);
      vertex(b, x1, y0, z0, c);
      vertex(b, x1, y1, z0, c);
      vertex(b, x1, y1, z1, c);
      vertex(b, x1, y0, z1, c);
    }
  }

  private static void vertex(BufferBuilder b, float x, float y, float z, int color) {
    b.addVertex(x, y, z).setColor(color);
  }

  private boolean cellEmpty(int cx, int cy, int cz) {
    if (cx < 0 || cx >= lodGx || cy < 0 || cy >= lodGy || cz < 0 || cz >= lodGz) {
      return true;
    }
    return lodColor[(cy * lodGz + cz) * lodGx + cx] == 0;
  }

  private static int shade(int argb, float factor) {
    int r = clamp((int) (((argb >> 16) & 0xFF) * factor));
    int g = clamp((int) (((argb >> 8) & 0xFF) * factor));
    int b = clamp((int) ((argb & 0xFF) * factor));
    return 0xFF000000 | (r << 16) | (g << 8) | b;
  }

  private static int clamp(int v) {
    return v < 0 ? 0 : Math.min(v, 255);
  }

  private void closeLod() {
    if (lodBuffer != null) {
      lodBuffer.close();
      lodBuffer = null;
    }
  }

  private void buildSelectionBuffer() {
    closeSelectionBuffer();
    if (!hasSelection) {
      return;
    }
    float x0 = selX0;
    float y0 = selY0;
    float z0 = selZ0;
    float x1 = selX1 + 1;
    float y1 = selY1 + 1;
    float z1 = selZ1 + 1;
    int col = 0x6044AAFF;
    try (ByteBufferBuilder bytes = new ByteBufferBuilder(4096)) {
      BufferBuilder b =
          new BufferBuilder(bytes, VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
      vertex(b, x0, y1, z0, col);
      vertex(b, x0, y1, z1, col);
      vertex(b, x1, y1, z1, col);
      vertex(b, x1, y1, z0, col);
      vertex(b, x0, y0, z0, col);
      vertex(b, x1, y0, z0, col);
      vertex(b, x1, y0, z1, col);
      vertex(b, x0, y0, z1, col);
      vertex(b, x0, y0, z0, col);
      vertex(b, x0, y1, z0, col);
      vertex(b, x1, y1, z0, col);
      vertex(b, x1, y0, z0, col);
      vertex(b, x0, y0, z1, col);
      vertex(b, x1, y0, z1, col);
      vertex(b, x1, y1, z1, col);
      vertex(b, x0, y1, z1, col);
      vertex(b, x0, y0, z0, col);
      vertex(b, x0, y0, z1, col);
      vertex(b, x0, y1, z1, col);
      vertex(b, x0, y1, z0, col);
      vertex(b, x1, y0, z0, col);
      vertex(b, x1, y1, z0, col);
      vertex(b, x1, y1, z1, col);
      vertex(b, x1, y0, z1, col);
      MeshData mesh = b.build();
      if (mesh != null) {
        VertexBuffer buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
        try {
          buffer.bind();
          buffer.upload(mesh);
          VertexBuffer.unbind();
          selectionBuffer = buffer;
        } catch (Throwable t) {
          buffer.close();
          selectionBuffer = null;
        }
      }
    } catch (Throwable t) {
      selectionBuffer = null;
    }
  }

  private void closeSelectionBuffer() {
    if (selectionBuffer != null) {
      selectionBuffer.close();
      selectionBuffer = null;
    }
  }

  /** Draws the selection box (translucent, no depth test, overlaid on the structure). */
  private void drawSelectionBox(GuiGraphics g, int x, int y, int w, int h, float scale, Minecraft mc) {
    Matrix4f modelView = cameraModelView(g, x, y, w, h, scale);
    Matrix4f projection = projectionFor(x, y, w, h);
    g.flush();
    UiScale.enableScissor(g, x, y, x + w, y + h);
    RenderSystem.disableDepthTest();
    RenderSystem.enableBlend();
    RenderSystem.defaultBlendFunc();
    RenderSystem.disableCull();
    RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
    try {
      RenderSystem.setShader(GameRenderer::getPositionColorShader);
      selectionBuffer.bind();
      selectionBuffer.drawWithShader(modelView, projection, RenderSystem.getShader());
      VertexBuffer.unbind();
    } catch (Throwable t) {
      // Discard this frame.
    } finally {
      RenderSystem.disableBlend();
      RenderSystem.enableDepthTest();
      RenderSystem.enableCull();
      g.flush();
      g.disableScissor();
    }
  }

  // ---- Single-block picking (screen-to-voxel ray, editor) ----

  /** Raycast the cursor into the voxel grid; returns the first solid cell hit, or null. */
  public Hit raycast(double mouseX, double mouseY) {
    if (grid.isEmpty()) {
      return null;
    }
    float[] ray = cursorRay(mouseX, mouseY);
    if (ray == null) {
      return null;
    }
    float len = (float) Math.sqrt(ray[3] * ray[3] + ray[4] * ray[4] + ray[5] * ray[5]);
    if (len < 1.0e-6f) {
      return null;
    }
    int steps = Math.min(8192, (int) Math.ceil(len) + maxDim + 8);
    return ddaPick(ray[0], ray[1], ray[2], ray[3] / len, ray[4] / len, ray[5] / len, steps);
  }

  /** Cursor pick ray in block space as {ox, oy, oz, dx, dy, dz} (raw, front-to-back), or null. */
  private float[] cursorRay(double mouseX, double mouseY) {
    int w = getWidth();
    int h = getHeight();
    if (w <= 0 || h <= 0) {
      return null;
    }
    if (perspective) {
      return cursorRayPerspective(mouseX, mouseY, w, h);
    }
    float scale = Math.min(w, h) * 0.85f / maxDim * zoom;
    PoseStack pose = new PoseStack();
    pose.translate(getX() + w / 2.0f + panX, getY() + h / 2.0f + panY, 400.0f);
    pose.scale(scale, -scale, scale);
    pose.mulPose(Axis.XP.rotationDegrees(pitch));
    pose.mulPose(Axis.YP.rotationDegrees(yaw));
    pose.translate(-centerX, -centerY, -centerZ);
    Matrix4f inv = new Matrix4f(pose.last().pose());
    if (inv.determinant() == 0.0f) {
      return null;
    }
    inv.invert();
    // Higher GUI z is toward the viewer, so the ray runs from camera-side (+z) inward.
    Vector4f front = inv.transform(new Vector4f((float) mouseX, (float) mouseY, 1000.0f, 1.0f));
    Vector4f back = inv.transform(new Vector4f((float) mouseX, (float) mouseY, -1000.0f, 1.0f));
    return new float[] {front.x(), front.y(), front.z(),
        back.x() - front.x(), back.y() - front.y(), back.z() - front.z()};
  }

  private float[] cursorRayPerspective(double mouseX, double mouseY, int w, int h) {
    Matrix4f inv = new Matrix4f(projectionFor(getX(), getY(), w, h)).mul(perspectiveModelView());
    if (inv.determinant() == 0.0f) {
      return null;
    }
    inv.invert();
    Minecraft mc = Minecraft.getInstance();
    float guiW = Math.max(1, mc.getWindow().getGuiScaledWidth());
    float guiH = Math.max(1, mc.getWindow().getGuiScaledHeight());
    float ndcX = 2.0f * (float) mouseX / guiW - 1.0f;
    float ndcY = 1.0f - 2.0f * (float) mouseY / guiH;
    Vector4f near = inv.transform(new Vector4f(ndcX, ndcY, -1.0f, 1.0f));
    Vector4f far = inv.transform(new Vector4f(ndcX, ndcY, 1.0f, 1.0f));
    if (near.w() == 0.0f || far.w() == 0.0f) {
      return null;
    }
    float ox = near.x() / near.w();
    float oy = near.y() / near.w();
    float oz = near.z() / near.w();
    float fx = far.x() / far.w();
    float fy = far.y() / far.w();
    float fz = far.z() / far.w();
    return new float[] {ox, oy, oz, fx - ox, fy - oy, fz - oz};
  }

  /** Cell under the cursor: the solid block hit, or a cell on the anchor's plane (for empty space). */
  private BlockPos selectCell(double mouseX, double mouseY) {
    Hit hit = raycast(mouseX, mouseY);
    if (hit != null) {
      return hit.pos();
    }
    return projectToPlane(mouseX, mouseY, selPlaneAxis, selPlaneCoord);
  }

  /** Intersects the cursor ray with the axis-aligned plane {axis = coord}; returns the cell, or null. */
  private BlockPos projectToPlane(double mouseX, double mouseY, Direction.Axis axis, int coord) {
    if (structure == null) {
      return null;
    }
    float[] ray = cursorRay(mouseX, mouseY);
    if (ray == null) {
      return null;
    }
    int ai = axis == Direction.Axis.X ? 0 : axis == Direction.Axis.Y ? 1 : 2;
    float d = ray[3 + ai];
    if (Math.abs(d) < 1.0e-6f) {
      return null;
    }
    float t = (coord + 0.5f - ray[ai]) / d;
    if (t < 0.0f) {
      return null;
    }
    int cx = axis == Direction.Axis.X ? coord
        : clampCell((int) Math.floor(ray[0] + ray[3] * t), structure.sizeX);
    int cy = axis == Direction.Axis.Y ? coord
        : clampCell((int) Math.floor(ray[1] + ray[4] * t), structure.sizeY);
    int cz = axis == Direction.Axis.Z ? coord
        : clampCell((int) Math.floor(ray[2] + ray[5] * t), structure.sizeZ);
    return new BlockPos(cx, cy, cz);
  }

  private static int axisCoord(BlockPos pos, Direction.Axis axis) {
    return axis == Direction.Axis.X ? pos.getX() : axis == Direction.Axis.Y ? pos.getY() : pos.getZ();
  }

  private static int clampCell(int value, int size) {
    return Math.max(0, Math.min(value, size - 1));
  }

  /** Ray-vs-selection-box test; returns the entry face index (axis*2 + maxSide), or -1 on a miss. */
  private int pickBoxFace(double mouseX, double mouseY) {
    if (!hasSelection) {
      return -1;
    }
    float[] ray = cursorRay(mouseX, mouseY);
    if (ray == null) {
      return -1;
    }
    float[] o = {ray[0], ray[1], ray[2]};
    float[] d = {ray[3], ray[4], ray[5]};
    float[] bmin = {selX0, selY0, selZ0};
    float[] bmax = {selX1 + 1, selY1 + 1, selZ1 + 1};
    float tmin = Float.NEGATIVE_INFINITY;
    float tmax = Float.POSITIVE_INFINITY;
    int enterAxis = -1;
    boolean enterMaxSide = false;
    for (int a = 0; a < 3; a++) {
      if (Math.abs(d[a]) < 1.0e-8f) {
        if (o[a] < bmin[a] || o[a] > bmax[a]) {
          return -1;
        }
        continue;
      }
      float inv = 1.0f / d[a];
      float t1 = (bmin[a] - o[a]) * inv;
      float t2 = (bmax[a] - o[a]) * inv;
      float tNear;
      float tFar;
      boolean nearIsMax;
      if (t1 <= t2) {
        tNear = t1;
        tFar = t2;
        nearIsMax = false;
      } else {
        tNear = t2;
        tFar = t1;
        nearIsMax = true;
      }
      if (tNear > tmin) {
        tmin = tNear;
        enterAxis = a;
        enterMaxSide = nearIsMax;
      }
      if (tFar < tmax) {
        tmax = tFar;
      }
      if (tmin > tmax) {
        return -1;
      }
    }
    if (enterAxis < 0 || tmax < 0.0f) {
      return -1;
    }
    return enterAxis * 2 + (enterMaxSide ? 1 : 0);
  }

  /** Moves the grabbed selection face to follow the cursor (projected onto a stable helper plane). */
  private void dragFaceTo(double mouseX, double mouseY) {
    if (dragFace < 0 || structure == null) {
      return;
    }
    float[] ray = cursorRay(mouseX, mouseY);
    if (ray == null) {
      return;
    }
    int axis = dragFace / 2;
    boolean maxSide = (dragFace % 2) == 1;
    int h1 = (axis + 1) % 3;
    int h2 = (axis + 2) % 3;
    int helper = Math.abs(ray[3 + h1]) >= Math.abs(ray[3 + h2]) ? h1 : h2;
    float[] bmin = {selX0, selY0, selZ0};
    float[] bmax = {selX1 + 1, selY1 + 1, selZ1 + 1};
    float planeVal = (bmin[helper] + bmax[helper]) / 2.0f;
    float d = ray[3 + helper];
    if (Math.abs(d) < 1.0e-6f) {
      return;
    }
    float t = (planeVal - ray[helper]) / d;
    if (t < 0.0f) {
      return;
    }
    float p = ray[axis] + ray[3 + axis] * t;
    int size = axis == 0 ? structure.sizeX : axis == 1 ? structure.sizeY : structure.sizeZ;
    int v = clampCell((int) Math.floor(p), size);
    int nx0 = selX0;
    int ny0 = selY0;
    int nz0 = selZ0;
    int nx1 = selX1;
    int ny1 = selY1;
    int nz1 = selZ1;
    switch (axis) {
      case 0 -> {
        if (maxSide) {
          nx1 = v;
        } else {
          nx0 = v;
        }
      }
      case 1 -> {
        if (maxSide) {
          ny1 = v;
        } else {
          ny0 = v;
        }
      }
      default -> {
        if (maxSide) {
          nz1 = v;
        } else {
          nz0 = v;
        }
      }
    }
    setSelection(nx0, ny0, nz0, nx1, ny1, nz1);
  }

  /** Amanatides-Woo voxel traversal: walk cells along the ray until a solid one is found. */
  private Hit ddaPick(float ox, float oy, float oz, float dx, float dy, float dz, int steps) {
    int cx = (int) Math.floor(ox);
    int cy = (int) Math.floor(oy);
    int cz = (int) Math.floor(oz);
    int stepX = dx > 0 ? 1 : -1;
    int stepY = dy > 0 ? 1 : -1;
    int stepZ = dz > 0 ? 1 : -1;
    float tMaxX = boundary(ox, dx, cx, stepX);
    float tMaxY = boundary(oy, dy, cy, stepY);
    float tMaxZ = boundary(oz, dz, cz, stepZ);
    float tDeltaX = dx != 0 ? Math.abs(1.0f / dx) : Float.MAX_VALUE;
    float tDeltaY = dy != 0 ? Math.abs(1.0f / dy) : Float.MAX_VALUE;
    float tDeltaZ = dz != 0 ? Math.abs(1.0f / dz) : Float.MAX_VALUE;
    Direction face = Direction.UP;
    for (int i = 0; i < steps; i++) {
      if (inClip(cx, cy, cz) && grid.get(encode(cx, cy, cz)) != null) {
        return new Hit(new BlockPos(cx, cy, cz), face);
      }
      if (tMaxX < tMaxY && tMaxX < tMaxZ) {
        cx += stepX;
        tMaxX += tDeltaX;
        face = stepX > 0 ? Direction.WEST : Direction.EAST;
      } else if (tMaxY < tMaxZ) {
        cy += stepY;
        tMaxY += tDeltaY;
        face = stepY > 0 ? Direction.DOWN : Direction.UP;
      } else {
        cz += stepZ;
        tMaxZ += tDeltaZ;
        face = stepZ > 0 ? Direction.NORTH : Direction.SOUTH;
      }
    }
    return null;
  }

  private static float boundary(float o, float d, int c, int step) {
    if (d == 0.0f) {
      return Float.MAX_VALUE;
    }
    float next = step > 0 ? c + 1 : c;
    return (next - o) / d;
  }

  private void updateHover(double mouseX, double mouseY) {
    Hit previous = hover;
    hover = raycast(mouseX, mouseY);
    if (hover == null) {
      closeHoverBuffer();
    } else if (previous == null || !previous.pos().equals(hover.pos())) {
      buildHoverBuffer(hover.pos());
    }
  }

  private void buildHoverBuffer(BlockPos pos) {
    closeHoverBuffer();
    float x0 = pos.getX() - 0.02f;
    float y0 = pos.getY() - 0.02f;
    float z0 = pos.getZ() - 0.02f;
    float x1 = pos.getX() + 1.02f;
    float y1 = pos.getY() + 1.02f;
    float z1 = pos.getZ() + 1.02f;
    int col = 0x80FFEE55;
    try (ByteBufferBuilder bytes = new ByteBufferBuilder(4096)) {
      BufferBuilder b =
          new BufferBuilder(bytes, VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
      vertex(b, x0, y1, z0, col);
      vertex(b, x0, y1, z1, col);
      vertex(b, x1, y1, z1, col);
      vertex(b, x1, y1, z0, col);
      vertex(b, x0, y0, z0, col);
      vertex(b, x1, y0, z0, col);
      vertex(b, x1, y0, z1, col);
      vertex(b, x0, y0, z1, col);
      vertex(b, x0, y0, z0, col);
      vertex(b, x0, y1, z0, col);
      vertex(b, x1, y1, z0, col);
      vertex(b, x1, y0, z0, col);
      vertex(b, x0, y0, z1, col);
      vertex(b, x1, y0, z1, col);
      vertex(b, x1, y1, z1, col);
      vertex(b, x0, y1, z1, col);
      vertex(b, x0, y0, z0, col);
      vertex(b, x0, y0, z1, col);
      vertex(b, x0, y1, z1, col);
      vertex(b, x0, y1, z0, col);
      vertex(b, x1, y0, z0, col);
      vertex(b, x1, y1, z0, col);
      vertex(b, x1, y1, z1, col);
      vertex(b, x1, y0, z1, col);
      MeshData mesh = b.build();
      if (mesh != null) {
        VertexBuffer buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
        try {
          buffer.bind();
          buffer.upload(mesh);
          VertexBuffer.unbind();
          hoverBuffer = buffer;
        } catch (Throwable t) {
          buffer.close();
          hoverBuffer = null;
        }
      }
    } catch (Throwable t) {
      hoverBuffer = null;
    }
  }

  private void drawHover(GuiGraphics g, int x, int y, int w, int h, float scale, Minecraft mc) {
    Matrix4f modelView = cameraModelView(g, x, y, w, h, scale);
    Matrix4f projection = projectionFor(x, y, w, h);
    g.flush();
    UiScale.enableScissor(g, x, y, x + w, y + h);
    RenderSystem.enableDepthTest();
    RenderSystem.enableBlend();
    RenderSystem.defaultBlendFunc();
    RenderSystem.disableCull();
    RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
    try {
      RenderSystem.setShader(GameRenderer::getPositionColorShader);
      hoverBuffer.bind();
      hoverBuffer.drawWithShader(modelView, projection, RenderSystem.getShader());
      VertexBuffer.unbind();
    } catch (Throwable t) {
      // Discard this frame.
    } finally {
      RenderSystem.disableBlend();
      RenderSystem.enableCull();
      g.flush();
      g.disableScissor();
    }
  }

  private void closeHoverBuffer() {
    if (hoverBuffer != null) {
      hoverBuffer.close();
      hoverBuffer = null;
    }
  }

  // ---- Reference decorations: axes / bounding box / ground grid (editor) ----

  /** Toggle the axis gizmo, bounding box and ground grid overlay. */
  public void toggleGizmo() {
    showGizmo = !showGizmo;
  }

  /** Toggle perspective projection (default orthographic). Forces baking for small structures. */
  public void togglePerspective() {
    perspective = !perspective;
    if (!useLod && !blocks.isEmpty()) {
      boolean wantVbo = perspective || blocks.size() > IMMEDIATE_LIMIT;
      if (wantVbo && !useVbo) {
        useVbo = true;
        if (layerBlocks == null) {
          groupLayers();
        }
        startBake();
      } else if (!wantVbo && useVbo) {
        useVbo = false;
        cancelBake();
        closeBuffers();
      }
    }
  }

  private void buildDecor() {
    closeDecor();
    if (structure == null) {
      return;
    }
    int sx = structure.sizeX;
    int sy = structure.sizeY;
    int sz = structure.sizeZ;
    float t = Math.max(0.05f, maxDim * 0.012f);
    float tg = Math.max(0.03f, maxDim * 0.006f);
    float axisLen = Math.max(2.0f, maxDim * 0.35f);
    int edgeColor = 0xC0FFFFFF;
    int gridColor = 0x40FFFFFF;
    int step = Math.max(1, maxDim / 8);
    try (ByteBufferBuilder bytes = new ByteBufferBuilder(64 * 1024)) {
      BufferBuilder b =
          new BufferBuilder(bytes, VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
      for (int z = 0; z <= sz; z += step) {
        edge(b, 0, 0, z, sx, 0, z, tg, gridColor);
      }
      for (int x = 0; x <= sx; x += step) {
        edge(b, x, 0, 0, x, 0, sz, tg, gridColor);
      }
      edge(b, 0, 0, 0, sx, 0, 0, t, edgeColor);
      edge(b, 0, 0, sz, sx, 0, sz, t, edgeColor);
      edge(b, 0, 0, 0, 0, 0, sz, t, edgeColor);
      edge(b, sx, 0, 0, sx, 0, sz, t, edgeColor);
      edge(b, 0, sy, 0, sx, sy, 0, t, edgeColor);
      edge(b, 0, sy, sz, sx, sy, sz, t, edgeColor);
      edge(b, 0, sy, 0, 0, sy, sz, t, edgeColor);
      edge(b, sx, sy, 0, sx, sy, sz, t, edgeColor);
      edge(b, 0, 0, 0, 0, sy, 0, t, edgeColor);
      edge(b, sx, 0, 0, sx, sy, 0, t, edgeColor);
      edge(b, 0, 0, sz, 0, sy, sz, t, edgeColor);
      edge(b, sx, 0, sz, sx, sy, sz, t, edgeColor);
      edge(b, 0, 0, 0, axisLen, 0, 0, t * 1.6f, 0xFFFF4040);
      edge(b, 0, 0, 0, 0, axisLen, 0, t * 1.6f, 0xFF40FF40);
      edge(b, 0, 0, 0, 0, 0, axisLen, t * 1.6f, 0xFF4060FF);
      MeshData mesh = b.build();
      if (mesh != null) {
        VertexBuffer buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
        try {
          buffer.bind();
          buffer.upload(mesh);
          VertexBuffer.unbind();
          decorBuffer = buffer;
        } catch (Throwable th) {
          buffer.close();
          decorBuffer = null;
        }
      }
    } catch (Throwable th) {
      decorBuffer = null;
    }
  }

  /** Emit a thin axis-aligned cuboid spanning the segment (used for wireframe lines). */
  private void edge(BufferBuilder b, float ax, float ay, float az,
      float bx, float by, float bz, float t, int color) {
    box(b, Math.min(ax, bx) - t, Math.min(ay, by) - t, Math.min(az, bz) - t,
        Math.max(ax, bx) + t, Math.max(ay, by) + t, Math.max(az, bz) + t, color);
  }

  private static void box(BufferBuilder b,
      float x0, float y0, float z0, float x1, float y1, float z1, int c) {
    vertex(b, x0, y1, z0, c);
    vertex(b, x0, y1, z1, c);
    vertex(b, x1, y1, z1, c);
    vertex(b, x1, y1, z0, c);
    vertex(b, x0, y0, z0, c);
    vertex(b, x1, y0, z0, c);
    vertex(b, x1, y0, z1, c);
    vertex(b, x0, y0, z1, c);
    vertex(b, x0, y0, z0, c);
    vertex(b, x0, y1, z0, c);
    vertex(b, x1, y1, z0, c);
    vertex(b, x1, y0, z0, c);
    vertex(b, x0, y0, z1, c);
    vertex(b, x1, y0, z1, c);
    vertex(b, x1, y1, z1, c);
    vertex(b, x0, y1, z1, c);
    vertex(b, x0, y0, z0, c);
    vertex(b, x0, y0, z1, c);
    vertex(b, x0, y1, z1, c);
    vertex(b, x0, y1, z0, c);
    vertex(b, x1, y0, z0, c);
    vertex(b, x1, y1, z0, c);
    vertex(b, x1, y1, z1, c);
    vertex(b, x1, y0, z1, c);
  }

  private void drawDecor(GuiGraphics g, int x, int y, int w, int h, float scale, Minecraft mc) {
    Matrix4f modelView = cameraModelView(g, x, y, w, h, scale);
    Matrix4f projection = projectionFor(x, y, w, h);
    g.flush();
    UiScale.enableScissor(g, x, y, x + w, y + h);
    RenderSystem.enableDepthTest();
    RenderSystem.enableBlend();
    RenderSystem.defaultBlendFunc();
    RenderSystem.disableCull();
    RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
    try {
      RenderSystem.setShader(GameRenderer::getPositionColorShader);
      decorBuffer.bind();
      decorBuffer.drawWithShader(modelView, projection, RenderSystem.getShader());
      VertexBuffer.unbind();
    } catch (Throwable t) {
      // Discard this frame.
    } finally {
      RenderSystem.disableBlend();
      RenderSystem.enableCull();
      g.flush();
      g.disableScissor();
    }
  }

  private void closeDecor() {
    if (decorBuffer != null) {
      decorBuffer.close();
      decorBuffer = null;
    }
  }

  // ---- Rendering ----

  @Override
  protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
    if (structure == null) {
      return;
    }
    int x = getX();
    int y = getY();
    int w = getWidth();
    int h = getHeight();
    if (w <= 0 || h <= 0) {
      return;
    }
    Minecraft mc = Minecraft.getInstance();
    lerpCamera();
    float scale = Math.min(w, h) * 0.85f / maxDim * zoom;
    if (perspective) {
      g.flush();
      UiScale.enableScissor(g, x, y, x + w, y + h);
      RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, false);
      g.disableScissor();
    }
    if (useLod) {
      if (lodBuffer != null) {
        drawLod(g, x, y, w, h, scale, mc);
      }
    } else if (useVbo) {
      if (baking) {
        stepBake();
      }
      if (layerBuffers != null) {
        drawBaked(g, x, y, w, h, scale, mc);
      }
      drawBlockEntities(g, x, y, w, h, scale, mc);
      if (baking) {
        drawCenteredText(g, x, y, w, h, mc, "buildpack.preview.baking");
      }
    } else {
      drawImmediate(g, x, y, w, h, scale, mc);
      drawBlockEntities(g, x, y, w, h, scale, mc);
    }
    if (showGizmo) {
      if (decorBuffer == null) {
        buildDecor();
      }
      if (decorBuffer != null) {
        drawDecor(g, x, y, w, h, scale, mc);
      }
    }
    if (hasSelection && selectionBuffer != null) {
      drawSelectionBox(g, x, y, w, h, scale, mc);
    }
    if (editMode || selectMode) {
      updateHover(mouseX, mouseY);
      if (selectMode && selAnchor != null) {
        BlockPos end = selectCell(mouseX, mouseY);
        if (end != null && !end.equals(selPreviewEnd)) {
          selPreviewEnd = end;
          setSelection(selAnchor.getX(), selAnchor.getY(), selAnchor.getZ(),
              end.getX(), end.getY(), end.getZ());
        }
      }
      if (hoverBuffer != null) {
        drawHover(g, x, y, w, h, scale, mc);
      }
    }
    drawHints(g, x, y, w, h, mc);
  }

  private static final float PERSPECTIVE_FOV = (float) Math.toRadians(45.0);

  private float perspectiveDist() {
    float zoomClamped = Math.max(0.25f, zoom);
    return maxDim / (0.85f * zoomClamped * 2.0f * (float) Math.tan(PERSPECTIVE_FOV / 2.0))
        + maxDim;
  }

  /** View matrix for perspective mode: structure centered/rotated and pushed away from the camera. */
  private Matrix4f perspectiveModelView() {
    float dist = perspectiveDist();
    float viewPerPixel = 2.0f * dist * (float) Math.tan(PERSPECTIVE_FOV / 2.0)
        / Math.max(1, getHeight());
    Matrix4f mv = new Matrix4f();
    mv.translate(panX * viewPerPixel, -panY * viewPerPixel, -dist);
    mv.rotate(Axis.XP.rotationDegrees(pitch));
    mv.rotate(Axis.YP.rotationDegrees(yaw));
    mv.translate(-centerX, -centerY, -centerZ);
    return mv;
  }

  /** Projection: GUI ortho, or (perspective) a frustum mapped into the widget's screen rect. */
  private Matrix4f projectionFor(int x, int y, int w, int h) {
    if (!perspective) {
      return RenderSystem.getProjectionMatrix();
    }
    Minecraft mc = Minecraft.getInstance();
    float guiW = Math.max(1, mc.getWindow().getGuiScaledWidth());
    float guiH = Math.max(1, mc.getWindow().getGuiScaledHeight());
    float nl = 2.0f * x / guiW - 1.0f;
    float nr = 2.0f * (x + w) / guiW - 1.0f;
    float nt = 1.0f - 2.0f * y / guiH;
    float nb = 1.0f - 2.0f * (y + h) / guiH;
    return new Matrix4f()
        .translate((nl + nr) / 2.0f, (nb + nt) / 2.0f, 0.0f)
        .scale((nr - nl) / 2.0f, (nt - nb) / 2.0f, 1.0f)
        .perspective(PERSPECTIVE_FOV, 1.0f, 0.05f, maxDim * 8.0f + 100.0f);
  }

  private Matrix4f cameraModelView(GuiGraphics g, int x, int y, int w, int h, float scale) {
    if (perspective) {
      return perspectiveModelView();
    }
    PoseStack pose = g.pose();
    pose.pushPose();
    pose.translate(x + w / 2.0f + panX, y + h / 2.0f + panY, 400.0f);
    pose.scale(scale, -scale, scale);
    pose.mulPose(Axis.XP.rotationDegrees(pitch));
    pose.mulPose(Axis.YP.rotationDegrees(yaw));
    pose.translate(-centerX, -centerY, -centerZ);
    // Under GUI rendering, RenderSystem's modelView may not be identity; it must be composed with the
    // screen pose, otherwise the mesh renders off-screen (completely invisible).
    Matrix4f modelView = new Matrix4f(RenderSystem.getModelViewMatrix());
    modelView.mul(pose.last().pose());
    pose.popPose();
    return modelView;
  }

  /** Detailed: draws all baked Y-layer buffers (only layers with y < clipMaxY are drawn; slice is applied immediately). */
  private void drawBaked(GuiGraphics g, int x, int y, int w, int h, float scale, Minecraft mc) {
    Matrix4f modelView = cameraModelView(g, x, y, w, h, scale);
    Matrix4f projection = projectionFor(x, y, w, h);
    g.flush();
    UiScale.enableScissor(g, x, y, x + w, y + h);
    RenderSystem.enableDepthTest();
    RenderSystem.disableCull();
    RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
    Lighting.setupForEntityInInventory();
    mc.gameRenderer.lightTexture().turnOnLightLayer();
    RenderType renderType = RenderType.cutoutMipped();
    renderType.setupRenderState();
    try {
      int top = Math.min(clipMaxY, layerBuffers.length);
      for (int ly = Math.max(0, clipMinY); ly < top; ly++) {
        VertexBuffer buffer = layerBuffers[ly];
        if (buffer != null) {
          buffer.bind();
          buffer.drawWithShader(modelView, projection, RenderSystem.getShader());
        }
      }
      VertexBuffer.unbind();
    } catch (Throwable t) {
      // Render exception: discard this frame.
    } finally {
      renderType.clearRenderState();
      mc.gameRenderer.lightTexture().turnOffLightLayer();
      Lighting.setupForFlatItems();
      RenderSystem.enableCull();
      g.flush();
      g.disableScissor();
    }
  }

  /** Voxel LOD: draws the colored cube buffer (POSITION_COLOR, no textures or lightmap). */
  private void drawLod(GuiGraphics g, int x, int y, int w, int h, float scale, Minecraft mc) {
    Matrix4f modelView = cameraModelView(g, x, y, w, h, scale);
    Matrix4f projection = projectionFor(x, y, w, h);
    g.flush();
    UiScale.enableScissor(g, x, y, x + w, y + h);
    RenderSystem.enableDepthTest();
    RenderSystem.disableCull();
    RenderSystem.disableBlend();
    RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
    try {
      RenderSystem.setShader(GameRenderer::getPositionColorShader);
      lodBuffer.bind();
      lodBuffer.drawWithShader(modelView, projection, RenderSystem.getShader());
      VertexBuffer.unbind();
    } catch (Throwable t) {
      // Render exception: discard this frame.
    } finally {
      RenderSystem.enableCull();
      g.flush();
      g.disableScissor();
    }
  }

  /** Small structures: immediate per-block rendering every frame (matches original behavior). */
  private void drawImmediate(GuiGraphics g, int x, int y, int w, int h, float scale, Minecraft mc) {
    g.flush();
    UiScale.enableScissor(g, x, y, x + w, y + h);
    PoseStack pose = g.pose();
    pose.pushPose();
    try {
      pose.translate(x + w / 2.0f + panX, y + h / 2.0f + panY, 400.0f);
      pose.scale(scale, -scale, scale);
      pose.mulPose(Axis.XP.rotationDegrees(pitch));
      pose.mulPose(Axis.YP.rotationDegrees(yaw));
      pose.translate(-centerX, -centerY, -centerZ);

      RenderSystem.enableDepthTest();
      Lighting.setupForEntityInInventory();
      MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
      BlockRenderDispatcher dispatcher = mc.getBlockRenderer();
      for (PosState ps : blocks) {
        if (!inClip(ps.pos().getX(), ps.pos().getY(), ps.pos().getZ())) {
          continue;
        }
        pose.pushPose();
        pose.translate(ps.pos().getX(), ps.pos().getY(), ps.pos().getZ());
        dispatcher.renderSingleBlock(ps.state(), pose, buffers,
            LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
        pose.popPose();
      }
      buffers.endBatch();
    } catch (Throwable t) {
      // A block model exception must not crash the entire screen: discard this frame's 3D render.
      mc.renderBuffers().bufferSource().endBatch();
    } finally {
      pose.popPose();
      Lighting.setupForFlatItems();
      g.flush();
      g.disableScissor();
    }
  }

  /**
   * Overlay pass for renderer-driven blocks (chests, signs, beds...): their dynamic models live on
   * separate texture atlases and cannot be baked, so they draw through their real
   * {@link BlockEntityRenderer} every frame, after the static geometry so depth testing composes
   * them correctly. Not used by the voxel LOD tier.
   */
  private void drawBlockEntities(GuiGraphics g, int x, int y, int w, int h, float scale,
      Minecraft mc) {
    if (blockEntities.isEmpty()) {
      return;
    }
    g.flush();
    UiScale.enableScissor(g, x, y, x + w, y + h);
    PoseStack pose;
    if (perspective) {
      // The VBO tier draws with explicit matrices in perspective mode; mirror them through the
      // global RenderSystem state so this immediate pass lands in the same clip space.
      RenderSystem.backupProjectionMatrix();
      RenderSystem.setProjectionMatrix(projectionFor(x, y, w, h), VertexSorting.DISTANCE_TO_ORIGIN);
      Matrix4fStack modelViewStack = RenderSystem.getModelViewStack();
      modelViewStack.pushMatrix();
      modelViewStack.identity();
      RenderSystem.applyModelViewMatrix();
      pose = new PoseStack();
      pose.mulPose(perspectiveModelView());
    } else {
      pose = g.pose();
      pose.pushPose();
      pose.translate(x + w / 2.0f + panX, y + h / 2.0f + panY, 400.0f);
      pose.scale(scale, -scale, scale);
      pose.mulPose(Axis.XP.rotationDegrees(pitch));
      pose.mulPose(Axis.YP.rotationDegrees(yaw));
      pose.translate(-centerX, -centerY, -centerZ);
    }
    RenderSystem.enableDepthTest();
    Lighting.setupForEntityInInventory();
    BlockEntityRenderDispatcher dispatcher = mc.getBlockEntityRenderDispatcher();
    MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
    try {
      for (PosEntity pe : blockEntities) {
        BlockPos pos = pe.pos();
        if (!inClip(pos.getX(), pos.getY(), pos.getZ())) {
          continue;
        }
        pose.pushPose();
        pose.translate(pos.getX(), pos.getY(), pos.getZ());
        try {
          BlockEntityRenderer<BlockEntity> renderer = dispatcher.getRenderer(pe.entity());
          if (renderer != null) {
            renderer.render(pe.entity(), 0.0f, pose, buffers,
                LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
          }
        } catch (Throwable ignored) {
          // One broken renderer must not hide the rest of the preview.
        }
        pose.popPose();
      }
      buffers.endBatch();
    } catch (Throwable t) {
      // Render exception: discard this frame's block entity pass.
      mc.renderBuffers().bufferSource().endBatch();
    } finally {
      if (perspective) {
        RenderSystem.getModelViewStack().popMatrix();
        RenderSystem.applyModelViewMatrix();
        RenderSystem.restoreProjectionMatrix();
      } else {
        pose.popPose();
      }
      Lighting.setupForFlatItems();
      g.flush();
      g.disableScissor();
    }
  }

  private void drawCenteredText(GuiGraphics g, int x, int y, int w, int h, Minecraft mc, String key) {
    Font font = mc.font;
    String text = Component.translatable(key).getString();
    g.drawString(font, text, Math.round(x + (w - font.width(text)) / 2.0f),
        y + (h - font.lineHeight) / 2, 0xC0FFFFFF, true);
  }

  private String sliceTargetName() {
    return switch (sliceTarget) {
      case 0 -> "Y+";
      case 1 -> "Y-";
      case 2 -> "X+";
      case 3 -> "X-";
      case 4 -> "Z+";
      default -> "Z-";
    };
  }

  private void drawHints(GuiGraphics g, int x, int y, int w, int h, Minecraft mc) {
    Font font = mc.font;
    if (useLod) {
      String lod = Component.translatable("buildpack.preview.lod").getString();
      g.drawString(font, lod, x + w - font.width(lod) - 3, y + 3, 0xFFFFD080, true);
    }
    if (!interactive) {
      String hint = Component.translatable("buildpack.preview.zoom_hint").getString();
      g.drawString(font, hint, Math.round(x + (w - font.width(hint)) / 2.0f),
          y + h - font.lineHeight - 1, 0xC0FFFFFF, true);
      return;
    }
    if (structure != null) {
      String dims = structure.sizeX + "×" + structure.sizeY + "×" + structure.sizeZ
          + " · " + structure.countNonAir();
      g.drawString(font, dims, x + 3, y + 3, 0xC0FFFFFF, true);
    }
    boolean sliced = clipMinX > 0 || clipMaxX < structWidth || clipMinY > 0
        || clipMaxY < structHeight || clipMinZ > 0 || clipMaxZ < structDepth;
    String slice = Component.translatable("buildpack.preview.slice", sliceTargetName()).getString();
    g.drawString(font, slice, x + 3, y + 3 + font.lineHeight + 1,
        sliced ? 0xFFFFFF55 : 0xC0FFFFFF, true);
    if (hasSelection) {
      int selW = selX1 - selX0 + 1;
      int selH = selY1 - selY0 + 1;
      int selD = selZ1 - selZ0 + 1;
      String sel = Component.translatable("buildpack.preview.sel",
          selW + "×" + selH + "×" + selD, (long) selW * selH * selD).getString();
      g.drawString(font, sel, x + 3, y + 3 + (font.lineHeight + 1) * 2, 0xFF8AD8FF, true);
    }
    if (editMode && hover != null) {
      String info = hover.pos().getX() + "," + hover.pos().getY() + "," + hover.pos().getZ();
      String id = blockIdAt(hover.pos());
      if (id != null) {
        info = info + " · " + id;
      }
      g.drawString(font, info, x + 3, y + h - font.lineHeight - 1, 0xFFFFD080, true);
    }
  }

  // ---- Interaction (interactive mode only) ----

  @Override
  public boolean mouseClicked(double mouseX, double mouseY, int button) {
    if (!active || !visible || !isMouseOver(mouseX, mouseY)) {
      return false;
    }
    if (!interactive) {
      if (button == 0 && structure != null) {
        StructurePreviewScreen.open(structure);
        return true;
      }
      return false;
    }
    if (button == 2) {
      resetView();
    }
    if (button == 0) {
      dragged = false;
      dragFace = (selectMode && hasSelection && selAnchor == null)
          ? pickBoxFace(mouseX, mouseY) : -1;
    }
    // Left/right click: consume the event so the host can forward subsequent drag events.
    return true;
  }

  @Override
  public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
    if (!interactive) {
      return false;
    }
    if (selectMode && dragFace >= 0 && button == 0) {
      dragged = true;
      dragFaceTo(mouseX, mouseY);
      return true;
    }
    if (editMode && button == 0 && Screen.hasControlDown() && onEdit != null) {
      dragged = true;
      Hit hit = raycast(mouseX, mouseY);
      if (hit != null) {
        onEdit.accept(hit);
      }
      return true;
    }
    if (button == 0 && (Math.abs(dragX) > 0.5 || Math.abs(dragY) > 0.5)) {
      dragged = true;
    }
    applyDrag(button, dragX, dragY);
    return true;
  }

  /**
   * Applies a single drag delta: left button rotates, right button pans. Intended for the host
   * screen to forward non-left-button drags (vanilla containers only forward left-button drags to
   * the focused widget by default).
   */
  public void applyDrag(int button, double dragX, double dragY) {
    if (!interactive) {
      return;
    }
    if (button == 1) {
      panX += (float) dragX;
      panY += (float) dragY;
      targetPanX = panX;
      targetPanY = panY;
    } else if (button == 0) {
      yaw += (float) dragX;
      pitch = Math.max(-89.0f, Math.min(89.0f, pitch + (float) dragY));
      targetYaw = yaw;
      targetPitch = pitch;
    }
  }

  @Override
  public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
    if (!interactive || !isMouseOver(mouseX, mouseY)) {
      return false;
    }
    zoom = Math.max(0.25f, Math.min(6.0f, zoom * (scrollY > 0.0 ? 1.15f : 0.87f)));
    targetZoom = zoom;
    return true;
  }

  @Override
  public boolean mouseReleased(double mouseX, double mouseY, int button) {
    if (interactive && editMode && button == 0 && !dragged && onEdit != null
        && isMouseOver(mouseX, mouseY)) {
      Hit hit = raycast(mouseX, mouseY);
      if (hit != null) {
        onEdit.accept(hit);
      }
    }
    if (interactive && selectMode && button == 0) {
      int face = dragFace;
      dragFace = -1;
      if (face >= 0) {
        if (dragged && onSelect != null) {
          onSelect.accept(new BlockPos(selX0, selY0, selZ0), new BlockPos(selX1, selY1, selZ1));
        }
      } else if (!dragged && isMouseOver(mouseX, mouseY)) {
        if (selAnchor == null) {
          Hit hit = raycast(mouseX, mouseY);
          BlockPos cell;
          if (hit != null) {
            cell = hit.pos();
            selPlaneAxis = hit.face().getAxis();
            selPlaneCoord = axisCoord(cell, selPlaneAxis);
          } else {
            selPlaneAxis = Direction.Axis.Y;
            selPlaneCoord = 0;
            cell = projectToPlane(mouseX, mouseY, selPlaneAxis, selPlaneCoord);
          }
          if (cell != null) {
            selAnchor = cell;
            selPreviewEnd = cell;
            setSelection(cell.getX(), cell.getY(), cell.getZ(),
                cell.getX(), cell.getY(), cell.getZ());
          }
        } else {
          BlockPos cell = selectCell(mouseX, mouseY);
          if (cell != null && onSelect != null) {
            onSelect.accept(selAnchor, cell);
          }
          selAnchor = null;
          selPreviewEnd = null;
        }
      }
    }
    dragged = false;
    return super.mouseReleased(mouseX, mouseY, button);
  }

  @Override
  protected void updateWidgetNarration(NarrationElementOutput output) {}
}
