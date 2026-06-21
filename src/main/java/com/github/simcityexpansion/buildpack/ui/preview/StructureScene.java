package com.github.simcityexpansion.buildpack.ui.preview;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.github.simcityexpansion.buildpack.convert.NbtStructure;
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
 * 真实方块模型 3D 预览。
 *
 * <ul>
 *   <li><b>小结构（≤ {@value #IMMEDIATE_LIMIT} 方块）</b>：每帧逐方块即时渲染（与原行为一致）。</li>
 *   <li><b>大结构</b>：<b>按 Y 层各烘焙一个 {@link VertexBuffer}</b>（邻接剔除内部不可见方块），
 *       分帧增量进行（不卡顿）；切顶只改绘制的层数，<b>无需重新烘焙</b>（#6）。</li>
 * </ul>
 *
 * <p>两种交互模式：静态小预览（点击打开精细界面）与精细界面（左键旋转/右键平移/滚轮缩放/
 * 中键重置/切顶看内部）。
 */
public final class StructureScene extends AbstractWidget {

  /** 可预览的方块数硬上限（超过则放弃，调用方回退静态等距图）。 */
  private static final int MAX_BLOCKS = 200_000;
  /** 不超过此数用逐方块即时渲染；超过则按层烘焙进 VBO。 */
  private static final int IMMEDIATE_LIMIT = 6000;
  /** 每帧增量烘焙处理的方块数（分摊烘焙耗时，避免选中大建筑时卡顿）。 */
  private static final int BAKE_BUDGET = 8000;

  private record PosState(BlockPos pos, BlockState state) {}

  private final boolean interactive;
  private final List<PosState> blocks = new ArrayList<>();
  private final Map<Long, BlockState> grid = new HashMap<>();
  private NbtStructure structure;

  private boolean useVbo;
  private List<List<PosState>> layerBlocks;
  private VertexBuffer[] layerBuffers;
  private boolean baking;
  private int bakeLayer;

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
  /** 只渲染 y < clipMaxY 的层（切顶看内部）。 */
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
    layerBlocks = null;
    structure = s;
    if (s == null) {
      return false;
    }
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
      if (blocks.size() > MAX_BLOCKS) {
        blocks.clear();
        grid.clear();
        return false;
      }
    }
    if (blocks.isEmpty()) {
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

    useVbo = blocks.size() > IMMEDIATE_LIMIT;
    if (useVbo) {
      groupLayers();
      startBake();
    }
    return true;
  }

  /** 重置视角（朝向/缩放/平移/切顶）。 */
  public void resetView() {
    resetCamera();
  }

  /** 切掉一层顶（露出内部）；VBO 模式下仅改绘制层数，无需重烘焙。 */
  public void peelTop() {
    clipMaxY = Math.max(1, Math.min(structHeight, clipMaxY) - 1);
  }

  /** 还原一层顶。 */
  public void unpeelTop() {
    clipMaxY = Math.min(structHeight, clipMaxY + 1);
  }

  /** 释放 GPU 网格缓冲与进行中的烘焙（替换预览/关闭界面时调用）。 */
  public void close() {
    cancelBake();
    closeBuffers();
  }

  /** 若为 VBO 模式但缓冲已被释放（界面切换后返回），重新烘焙。 */
  public void ensureBaked() {
    if (useVbo && layerBuffers == null && !baking && layerBlocks != null) {
      startBake();
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

  // ---- 按层网格烘焙（大结构，分帧增量进行）----

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

  /** 每帧推进若干层的烘焙；全部完成后停止。 */
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

  /** 把一层的可见方块（邻接剔除后）烘焙为一个 VertexBuffer；空层返回 null。 */
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

  /** 六面邻居都是遮挡满方块时该方块不可见，跳过（邻接剔除）。 */
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

  // ---- 渲染 ----

  @Override
  protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
    if (blocks.isEmpty()) {
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
    if (useVbo) {
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

  /** 大结构：画已烘焙好的各 Y 层缓存（只画 y < clipMaxY 的层，切顶即时）。 */
  private void drawBaked(GuiGraphics g, int x, int y, int w, int h, float scale, Minecraft mc) {
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
