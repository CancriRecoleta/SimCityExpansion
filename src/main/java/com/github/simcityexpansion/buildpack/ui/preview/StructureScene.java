package com.github.simcityexpansion.buildpack.ui.preview;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.github.simcityexpansion.buildpack.convert.NbtStructure;
import com.github.simcityexpansion.buildpack.convert.StructureAnalysis;
import com.github.simcityexpansion.buildpack.ui.StructurePreviewScreen;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Matrix4f;

/**
 * 真实方块模型 3D 预览，按规模分三档：
 * <ul>
 *   <li><b>≤ {@value #IMMEDIATE_LIMIT}</b>：每帧逐方块即时渲染（原行为）。</li>
 *   <li><b>≤ {@value #DETAIL_LIMIT}</b>：按 Y 层烘焙真实方块模型到 VBO（邻接剔除、分帧增量、
 *       切顶仅改层数）。</li>
 *   <li><b>更大（直到 {@value #LOD_MAX}）</b>：<b>体素 LOD</b>——按 N³ 单元降采样为带调色板色的
 *       彩色立方体（取单元内最高方块的地图色，单元间邻接剔除），用 {@code POSITION_COLOR} 顶点缓冲
 *       渲染，使百万级结构也能交互式 3D 预览（只是块感更粗）。</li>
 * </ul>
 */
public final class StructureScene extends AbstractWidget {

  /** 不超过此数用逐方块即时渲染。 */
  private static final int IMMEDIATE_LIMIT = 6000;
  /** 不超过此数用真实方块模型烘焙 VBO；超过转体素 LOD。 */
  private static final int DETAIL_LIMIT = 200_000;
  /** 体素 LOD 可处理的非空气方块上限（再大放弃，回退等距图）。 */
  private static final int LOD_MAX = 20_000_000;
  /** LOD 目标分辨率：最长边方向的体素单元数约为此值。 */
  private static final int LOD_TARGET_RES = 48;
  /** 每帧增量烘焙处理的方块数（分摊烘焙耗时）。 */
  private static final int BAKE_BUDGET = 8000;

  private record PosState(BlockPos pos, BlockState state) {}

  private final boolean interactive;
  private final List<PosState> blocks = new ArrayList<>();
  private final Map<Long, BlockState> grid = new HashMap<>();
  private NbtStructure structure;

  // 详细 VBO（按层）。
  private boolean useVbo;
  private List<List<PosState>> layerBlocks;
  private VertexBuffer[] layerBuffers;
  private boolean baking;
  private int bakeLayer;

  // 体素 LOD。
  private boolean useLod;
  private int[] lodColor;
  private int lodN;
  private int lodGx;
  private int lodGy;
  private int lodGz;
  private VertexBuffer lodBuffer;

  private float centerX;
  private float centerY;
  private float centerZ;
  private int maxDim = 1;
  private int structHeight = 1;

  private float yaw = 35.0f;
  private float pitch = 25.0f;
  private float zoom = 1.0f;
  private float panX;
  private float panY;
  /** 只渲染 y < clipMaxY 的部分（切顶看内部）。 */
  private int clipMaxY = Integer.MAX_VALUE;

  public StructureScene(int x, int y, int width, int height, boolean interactive) {
    super(x, y, width, height, Component.empty());
    this.interactive = interactive;
  }

  /** 解析结构并重置相机；返回是否可渲染（空/超限返回 false）。 */
  public boolean setStructure(NbtStructure s) {
    return setStructure(s, true);
  }

  /** 解析结构；{@code resetCamera=false} 时保留当前视角（编辑器变换后不跳视角）。 */
  public boolean setStructure(NbtStructure s, boolean resetCamera) {
    blocks.clear();
    grid.clear();
    cancelBake();
    closeBuffers();
    closeLod();
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
    maxDim = Math.max(1, Math.max(s.sizeX, Math.max(s.sizeY, s.sizeZ)));
    structHeight = Math.max(1, s.sizeY);
    if (resetCamera) {
      resetCamera();
    } else {
      clipMaxY = Math.min(clipMaxY, structHeight);
    }

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

  /** 解析为真实方块模型（即时/详细 VBO 路共用）。 */
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
      blocks.add(new PosState(new BlockPos(b.x(), b.y(), b.z()), palette[si]));
      grid.put(encode(b.x(), b.y(), b.z()), palette[si]);
    }
    return !blocks.isEmpty();
  }

  /** 重置视角（朝向/缩放/平移/切顶）。 */
  public void resetView() {
    resetCamera();
    if (useLod) {
      bakeLod();
    }
  }

  /** 切掉一层顶（露出内部）。 */
  public void peelTop() {
    clipMaxY = Math.max(1, Math.min(structHeight, clipMaxY) - 1);
    if (useLod) {
      bakeLod();
    }
  }

  /** 还原一层顶。 */
  public void unpeelTop() {
    clipMaxY = Math.min(structHeight, clipMaxY + 1);
    if (useLod) {
      bakeLod();
    }
  }

  /** 释放 GPU 缓冲与进行中的烘焙（替换预览/关闭界面时调用）。 */
  public void close() {
    cancelBake();
    closeBuffers();
    closeLod();
  }

  /** 若缓冲已被释放（界面切换后返回），重新烘焙。 */
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
    clipMaxY = structHeight;
  }

  // ---- 详细：按层网格烘焙（分帧增量）----

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
        if (occluded(ps.pos())) {
          continue;
        }
        pose.pushPose();
        pose.translate(ps.pos().getX(), ps.pos().getY(), ps.pos().getZ());
        try {
          dispatcher.renderSingleBlock(ps.state(), pose, source,
              LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
        } catch (Throwable ignored) {
          // 个别方块模型异常跳过即可。
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

  // ---- 体素 LOD（超大结构）----

  /** 按 N³ 单元降采样为彩色立方体网格；返回是否成功烘焙。 */
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

  /** 把彩色立方体（单元间邻接剔除、按 clipMaxY 切顶）烘焙为 POSITION_COLOR 缓冲。 */
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

  // ---- 渲染 ----

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
    float scale = Math.min(w, h) * 0.85f / maxDim * zoom;
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
      if (baking) {
        drawCenteredText(g, x, y, w, h, mc, "buildpack.preview.baking");
      }
    } else {
      drawImmediate(g, x, y, w, h, scale, mc);
    }
    drawHints(g, x, y, w, h, mc);
  }

  private Matrix4f cameraModelView(GuiGraphics g, int x, int y, int w, int h, float scale) {
    PoseStack pose = g.pose();
    pose.pushPose();
    pose.translate(x + w / 2.0f + panX, y + h / 2.0f + panY, 400.0f);
    pose.scale(scale, -scale, scale);
    pose.mulPose(Axis.XP.rotationDegrees(pitch));
    pose.mulPose(Axis.YP.rotationDegrees(yaw));
    pose.translate(-centerX, -centerY, -centerZ);
    // GUI 下 RenderSystem 的 modelView 不一定是单位阵，需与界面 pose 复合，否则网格落在屏外（全空）。
    Matrix4f modelView = new Matrix4f(RenderSystem.getModelViewMatrix());
    modelView.mul(pose.last().pose());
    pose.popPose();
    return modelView;
  }

  /** 详细：画已烘焙好的各 Y 层缓存（只画 y < clipMaxY 的层，切顶即时）。 */
  private void drawBaked(GuiGraphics g, int x, int y, int w, int h, float scale, Minecraft mc) {
    Matrix4f modelView = cameraModelView(g, x, y, w, h, scale);
    Matrix4f projection = RenderSystem.getProjectionMatrix();
    g.flush();
    g.enableScissor(x, y, x + w, y + h);
    RenderSystem.enableDepthTest();
    RenderSystem.disableCull();
    RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
    Lighting.setupForEntityInInventory();
    mc.gameRenderer.lightTexture().turnOnLightLayer();
    RenderType renderType = RenderType.cutoutMipped();
    renderType.setupRenderState();
    try {
      int top = Math.min(clipMaxY, layerBuffers.length);
      for (int ly = 0; ly < top; ly++) {
        VertexBuffer buffer = layerBuffers[ly];
        if (buffer != null) {
          buffer.bind();
          buffer.drawWithShader(modelView, projection, RenderSystem.getShader());
        }
      }
      VertexBuffer.unbind();
    } catch (Throwable t) {
      // 渲染异常丢弃本帧。
    } finally {
      renderType.clearRenderState();
      mc.gameRenderer.lightTexture().turnOffLightLayer();
      Lighting.setupForFlatItems();
      RenderSystem.enableCull();
      g.flush();
      g.disableScissor();
    }
  }

  /** 体素 LOD：画彩色立方体缓冲（POSITION_COLOR，无纹理/光照贴图）。 */
  private void drawLod(GuiGraphics g, int x, int y, int w, int h, float scale, Minecraft mc) {
    Matrix4f modelView = cameraModelView(g, x, y, w, h, scale);
    Matrix4f projection = RenderSystem.getProjectionMatrix();
    g.flush();
    g.enableScissor(x, y, x + w, y + h);
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
      // 渲染异常丢弃本帧。
    } finally {
      RenderSystem.enableCull();
      g.flush();
      g.disableScissor();
    }
  }

  /** 小结构：每帧逐方块即时渲染（与原行为一致）。 */
  private void drawImmediate(GuiGraphics g, int x, int y, int w, int h, float scale, Minecraft mc) {
    g.flush();
    g.enableScissor(x, y, x + w, y + h);
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
        if (ps.pos().getY() >= clipMaxY) {
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
      // 个别方块模型异常不应让整个界面崩溃：丢弃本帧 3D 渲染即可。
      mc.renderBuffers().bufferSource().endBatch();
    } finally {
      pose.popPose();
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
    if (clipMaxY < structHeight) {
      String layer =
          Component.translatable("buildpack.preview.layer", clipMaxY, structHeight).getString();
      g.drawString(font, layer, x + 3, y + 3 + font.lineHeight + 1, 0xFFFFFF55, true);
    }
  }

  // ---- 交互（仅精细界面） ----

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
    // 左/右键：消费点击以便宿主转发后续拖拽。
    return true;
  }

  @Override
  public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
    if (!interactive) {
      return false;
    }
    applyDrag(button, dragX, dragY);
    return true;
  }

  /**
   * 应用一次拖拽增量：左键旋转、右键平移。供宿主屏幕转发非左键拖拽
   * （原版容器默认只把左键拖拽转发给获得焦点的控件）。
   */
  public void applyDrag(int button, double dragX, double dragY) {
    if (!interactive) {
      return;
    }
    if (button == 1) {
      panX += (float) dragX;
      panY += (float) dragY;
    } else if (button == 0) {
      yaw += (float) dragX;
      pitch = Math.max(-89.0f, Math.min(89.0f, pitch + (float) dragY));
    }
  }

  @Override
  public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
    if (!interactive || !isMouseOver(mouseX, mouseY)) {
      return false;
    }
    zoom = Math.max(0.25f, Math.min(6.0f, zoom * (scrollY > 0.0 ? 1.15f : 0.87f)));
    return true;
  }

  @Override
  protected void updateWidgetNarration(NarrationElementOutput output) {}
}
