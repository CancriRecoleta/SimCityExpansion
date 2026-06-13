package com.github.simcityexpansion.buildpack.ui.preview;

import java.util.ArrayList;
import java.util.List;

import com.github.simcityexpansion.buildpack.convert.NbtStructure;
import com.github.simcityexpansion.buildpack.ui.StructurePreviewScreen;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvent;
import com.lowdragmc.lowdraglib2.gui.ui.rendering.GUIContext;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 真实方块模型 3D 预览：用 Minecraft 的 {@link BlockRenderDispatcher} 把结构里每个方块
 * 按真实模型渲染成 3D 场景。
 *
 * <p>两种模式：<b>静态小预览</b>（{@code interactive=false}，固定角度、居中、点击打开精细界面）
 * 与<b>精细界面</b>（{@code interactive=true}：左键拖动旋转、右键拖动平移、滚轮缩放、中键重置）。
 *
 * <p>拖拽走 ldlib 的拖拽源机制：{@code mouseDown} 里调 {@link #startDrag} 开始拖拽，
 * 拖拽源元素随后收到 {@code "dragSourceUpdate"}（事件带起点 {@code dragStartX/Y} 与当前 {@code x/y}）。
 *
 * <p>渲染区域与命中范围都取<b>父容器内容盒</b>——本元素在居中布局里可能被算成 0 尺寸。
 */
public final class StructureScene extends UIElement {

  /** 实时渲染的方块数上限（超过则放弃，调用方回退静态等距图）。 */
  private static final int MAX_BLOCKS = 6000;

  private record PosState(BlockPos pos, BlockState state) {}

  private final boolean interactive;
  private final List<PosState> blocks = new ArrayList<>();
  private NbtStructure structure;
  private float centerX;
  private float centerY;
  private float centerZ;
  private int maxDim = 1;

  private float yaw = 35.0f;
  private float pitch = 25.0f;
  private float zoom = 1.0f;
  private float panX;
  private float panY;
  // 拖拽起点快照。
  private int dragButton;
  private float startYaw;
  private float startPitch;
  private float startPanX;
  private float startPanY;

  public StructureScene(boolean interactive) {
    this.interactive = interactive;
    layout(l -> l.widthStretch().heightStretch());
    if (interactive) {
      addEventListener("mouseDown", this::onMouseDown);
      addEventListener("dragSourceUpdate", this::onDragSource);
      addEventListener("mouseWheel", this::onWheel);
    } else {
      // 静态小预览：点击打开精细界面。
      addEventListener("mouseDown", e -> {
        if (e.button == 0 && structure != null) {
          StructurePreviewScreen.open(structure);
          e.stopPropagation();
        }
      });
    }
  }

  /** 解析结构为方块模型列表并重置相机；返回是否可渲染（空/超限返回 false）。 */
  public boolean setStructure(NbtStructure s) {
    blocks.clear();
    structure = s;
    resetView();
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
      if (blocks.size() > MAX_BLOCKS) {
        blocks.clear();
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
    return true;
  }

  /** 重置视角（朝向/缩放/平移）。 */
  public void resetView() {
    yaw = 35.0f;
    pitch = 25.0f;
    zoom = 1.0f;
    panX = 0.0f;
    panY = 0.0f;
  }

  /** 渲染/命中区域：父容器内容盒（本元素自身尺寸不可靠）。 */
  private float[] box() {
    UIElement p = getParent();
    if (p != null) {
      return new float[] {p.getContentX(), p.getContentY(), p.getContentWidth(), p.getContentHeight()};
    }
    return new float[] {getPositionX(), getPositionY(), getSizeWidth(), getSizeHeight()};
  }

  @Override
  public boolean isIntersectWithPoint(double localX, double localY) {
    float[] b = box();
    return isMouseOverRect(b[0], b[1], b[2], b[3], localX, localY);
  }

  // ---- 渲染 ----

  @Override
  public void drawContents(GUIContext ctx) {
    if (blocks.isEmpty()) {
      return;
    }
    float[] bx = box();
    float x = bx[0];
    float y = bx[1];
    float w = bx[2];
    float h = bx[3];
    if (w <= 0.0f || h <= 0.0f) {
      return;
    }
    GuiGraphics g = ctx.graphics;
    Minecraft mc = ctx.mc;
    float scale = Math.min(w, h) * 0.85f / maxDim * zoom;

    g.flush();
    ctx.enableScissor(x, y, w, h);
    PoseStack pose = g.pose();
    pose.pushPose();
    try {
      pose.translate(x + w / 2.0f + panX, y + h / 2.0f + panY, 400.0f);
      pose.scale(scale, -scale, scale);
      pose.mulPose(Axis.XP.rotationDegrees(pitch));
      pose.mulPose(Axis.YP.rotationDegrees(yaw));
      pose.translate(-centerX, -centerY, -centerZ);

      RenderSystem.enableDepthTest();
      Lighting.setupFor3DItems();
      MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
      BlockRenderDispatcher dispatcher = mc.getBlockRenderer();
      for (PosState ps : blocks) {
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
      ctx.disableScissor();
    }

    if (!interactive) {
      Font font = mc.font;
      String hint = Component.translatable("buildpack.preview.zoom_hint").getString();
      g.drawString(font, hint, Math.round(x + (w - font.width(hint)) / 2.0f),
          Math.round(y + h - font.lineHeight - 1), 0xC0FFFFFF, true);
    }
  }

  // ---- 交互（仅精细界面） ----

  private void onMouseDown(UIEvent e) {
    if (e.button == 0 || e.button == 1) {
      startDrag(this, null);
      dragButton = e.button;
      startYaw = yaw;
      startPitch = pitch;
      startPanX = panX;
      startPanY = panY;
    } else if (e.button == 2) {
      resetView();
    }
  }

  private void onDragSource(UIEvent e) {
    if (dragButton == 1) {
      panX = startPanX + (e.x - e.dragStartX);
      panY = startPanY + (e.y - e.dragStartY);
    } else {
      yaw = startYaw + (e.x - e.dragStartX);
      pitch = Math.max(-89.0f, Math.min(89.0f, startPitch + (e.y - e.dragStartY)));
    }
    e.stopPropagation();
  }

  private void onWheel(UIEvent e) {
    zoom = Math.max(0.25f, Math.min(6.0f, zoom * (e.deltaY > 0.0f ? 1.15f : 0.87f)));
    e.stopPropagation();
  }
}
