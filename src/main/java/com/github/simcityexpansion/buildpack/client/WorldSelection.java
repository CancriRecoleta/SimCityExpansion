package com.github.simcityexpansion.buildpack.client;

import java.nio.file.Files;
import java.nio.file.Path;

import com.github.simcityexpansion.buildpack.I18nLog;
import com.github.simcityexpansion.buildpack.LocalizedIOException;
import com.github.simcityexpansion.buildpack.convert.LitematicWriter;
import com.github.simcityexpansion.buildpack.convert.NbtStructure;
import com.github.simcityexpansion.buildpack.convert.StructureNbtWriter;
import com.github.simcityexpansion.buildpack.convert.WorldCapture;
import com.github.simcityexpansion.buildpack.model.ImportScanner;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Non-command world structure export: uses key mappings to set two corner points at the
 * crosshair-targeted block (or the player's feet), then exports the selection as .nbt +
 * .litematic into the import directory. Renders a live bounding box in the world. Reads block
 * states from already-loaded chunks on the client.
 */
public final class WorldSelection {
  private WorldSelection() {}

  private static final Logger LOGGER = LoggerFactory.getLogger(WorldSelection.class);
  private static final long MAX_VOLUME = 2_000_000L;

  private static BlockPos cornerA;
  private static BlockPos cornerB;

  /** Sets corner A to the crosshair-targeted block, or the player's foot position if nothing is targeted. */
  public static void setCornerA() {
    BlockPos pos = targetPos();
    if (pos != null) {
      cornerA = pos;
      actionBar(Component.translatable("buildpack.select.corner_a", pos.getX(), pos.getY(), pos.getZ()));
    }
  }

  /** Sets corner B. */
  public static void setCornerB() {
    BlockPos pos = targetPos();
    if (pos != null) {
      cornerB = pos;
      actionBar(Component.translatable("buildpack.select.corner_b", pos.getX(), pos.getY(), pos.getZ()));
    }
  }

  /** Capture result: the display message and a success flag. */
  public record CaptureResult(Component message, boolean ok) {}

  /** Returns whether both corner points have been set (used by GUI buttons to determine enabled state). */
  public static boolean hasSelection() {
    return cornerA != null && cornerB != null;
  }

  /** Captures the current selection and exports it as a blueprint; returns a result message (shared by commands, key mappings, and GUI). */
  public static CaptureResult capture() {
    Minecraft mc = Minecraft.getInstance();
    if (mc.level == null || mc.player == null || cornerA == null || cornerB == null) {
      return new CaptureResult(Component.translatable("buildpack.select.need_corners"), false);
    }
    BlockPos min = new BlockPos(Math.min(cornerA.getX(), cornerB.getX()),
        Math.min(cornerA.getY(), cornerB.getY()), Math.min(cornerA.getZ(), cornerB.getZ()));
    BlockPos max = new BlockPos(Math.max(cornerA.getX(), cornerB.getX()),
        Math.max(cornerA.getY(), cornerB.getY()), Math.max(cornerA.getZ(), cornerB.getZ()));
    int sizeX = max.getX() - min.getX() + 1;
    int sizeY = max.getY() - min.getY() + 1;
    int sizeZ = max.getZ() - min.getZ() + 1;
    long volume = (long) sizeX * sizeY * sizeZ;
    if (volume > MAX_VOLUME) {
      return new CaptureResult(
          Component.translatable("buildpack.select.too_big", volume, MAX_VOLUME), false);
    }
    try {
      NbtStructure structure = WorldCapture.capture(mc.level, min, max);
      String base = "capture_" + min.getX() + "_" + min.getY() + "_" + min.getZ();
      Path dir = ImportScanner.ensureImportDir();
      Path nbt = uniqueTarget(dir, base + ".nbt");
      StructureNbtWriter.write(structure, nbt);
      Path lite = uniqueTarget(dir, base + ".litematic");
      LitematicWriter.write(structure, base, mc.player.getGameProfile().getName(), lite);
      return new CaptureResult(Component.translatable("buildpack.select.captured",
          sizeX + " x " + sizeY + " x " + sizeZ,
          nbt.getFileName() + ", " + lite.getFileName()), true);
    } catch (Exception e) {
      I18nLog.warn(LOGGER, e, "buildpack.log.export_failed");
      return new CaptureResult(
          Component.translatable("buildpack.select.failed", LocalizedIOException.messageOf(e)), false);
    }
  }

  /** Draws the selection box during the world render stage (one corner renders that single block; two corners render the bounding box). */
  public static void onRenderLevelStage(RenderLevelStageEvent event) {
    if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS
        || (cornerA == null && cornerB == null)) {
      return;
    }
    PoseStack pose = event.getPoseStack();
    Vec3 cam = event.getCamera().getPosition();
    MultiBufferSource.BufferSource buffers = Minecraft.getInstance().renderBuffers().bufferSource();
    VertexConsumer lines = buffers.getBuffer(RenderType.lines());
    pose.pushPose();
    pose.translate(-cam.x, -cam.y, -cam.z);
    if (cornerA != null && cornerB != null) {
      LevelRenderer.renderLineBox(pose, lines, box(cornerA, cornerB), 0.25f, 0.9f, 1.0f, 0.9f);
    } else {
      BlockPos single = cornerA != null ? cornerA : cornerB;
      LevelRenderer.renderLineBox(pose, lines, new AABB(single), 1.0f, 0.85f, 0.2f, 0.9f);
    }
    pose.popPose();
    buffers.endBatch(RenderType.lines());
  }

  private static AABB box(BlockPos a, BlockPos b) {
    return new AABB(
        Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()),
        Math.max(a.getX(), b.getX()) + 1.0, Math.max(a.getY(), b.getY()) + 1.0,
        Math.max(a.getZ(), b.getZ()) + 1.0);
  }

  private static BlockPos targetPos() {
    Minecraft mc = Minecraft.getInstance();
    if (mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
      return hit.getBlockPos();
    }
    return mc.player != null ? mc.player.blockPosition() : null;
  }

  private static void actionBar(Component message) {
    if (Minecraft.getInstance().player != null) {
      Minecraft.getInstance().player.displayClientMessage(message, true);
    }
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
