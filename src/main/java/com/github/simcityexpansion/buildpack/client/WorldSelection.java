package com.github.simcityexpansion.buildpack.client;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

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
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
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
  private static final long MAX_VOLUME = WorldCapture.MAX_CAPTURE_VOLUME;

  private static BlockPos cornerA;
  private static BlockPos cornerB;
  private static boolean captureBlockEntities = true;

  private static final int CAPTURE_BUDGET = 100_000;
  private static WorldCapture.Job activeJob;
  private static Object jobLevel;
  private static boolean writing;
  private static String jobBase = "";
  private static String jobAuthor = "";
  private static String jobSize = "";

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

  /** Synchronous capture result: the structure, or null with the failure message. */
  public record StructureResult(@org.jetbrains.annotations.Nullable NbtStructure structure,
      Component message) {}

  /** Returns whether both corner points have been set (used by GUI buttons to determine enabled state). */
  public static boolean hasSelection() {
    return cornerA != null && cornerB != null;
  }

  /**
   * Captures the current selection synchronously into a structure (update-building flow). Unlike
   * {@link #capture()} this blocks the render thread for the duration — callers should expect a
   * short hitch on very large selections.
   */
  public static StructureResult captureStructure() {
    Minecraft mc = Minecraft.getInstance();
    if (mc.level == null || mc.player == null || cornerA == null || cornerB == null) {
      return new StructureResult(null, Component.translatable("buildpack.select.need_corners"));
    }
    if (activeJob != null || writing) {
      return new StructureResult(null, Component.translatable("buildpack.select.busy"));
    }
    BlockPos min = new BlockPos(Math.min(cornerA.getX(), cornerB.getX()),
        Math.min(cornerA.getY(), cornerB.getY()), Math.min(cornerA.getZ(), cornerB.getZ()));
    BlockPos max = new BlockPos(Math.max(cornerA.getX(), cornerB.getX()),
        Math.max(cornerA.getY(), cornerB.getY()), Math.max(cornerA.getZ(), cornerB.getZ()));
    long volume = (long) (max.getX() - min.getX() + 1) * (max.getY() - min.getY() + 1)
        * (max.getZ() - min.getZ() + 1);
    if (volume > MAX_VOLUME) {
      return new StructureResult(null,
          Component.translatable("buildpack.select.too_big", volume, MAX_VOLUME));
    }
    if (!WorldCapture.regionLoaded(mc.level, min, max)) {
      return new StructureResult(null, Component.translatable("buildpack.select.unloaded"));
    }
    NbtStructure structure = WorldCapture.capture(mc.level, min, max, captureBlockEntities);
    return new StructureResult(structure, Component.empty());
  }

  /** Clears both world-selection corners (removes the in-world box). */
  public static void clearSelection() {
    if (cornerA == null && cornerB == null) {
      return;
    }
    cornerA = null;
    cornerB = null;
    actionBar(Component.translatable("buildpack.select.cleared"));
  }

  /** Starts an asynchronous capture of the current selection; progress is shown while it runs. */
  public static CaptureResult capture() {
    Minecraft mc = Minecraft.getInstance();
    if (mc.level == null || mc.player == null || cornerA == null || cornerB == null) {
      return new CaptureResult(Component.translatable("buildpack.select.need_corners"), false);
    }
    if (activeJob != null || writing) {
      return new CaptureResult(Component.translatable("buildpack.select.busy"), false);
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
    if (!WorldCapture.regionLoaded(mc.level, min, max)) {
      return new CaptureResult(Component.translatable("buildpack.select.unloaded"), false);
    }
    activeJob = WorldCapture.job(mc.level, min, max, captureBlockEntities);
    jobLevel = mc.level;
    jobBase = "capture_" + min.getX() + "_" + min.getY() + "_" + min.getZ();
    jobAuthor = mc.player.getGameProfile().getName();
    jobSize = sizeX + " x " + sizeY + " x " + sizeZ;
    return new CaptureResult(Component.translatable("buildpack.select.progress", 0), true);
  }

  /** Advances an in-progress capture by one tick's budget; writes files off-thread when finished. */
  public static void tickCapture() {
    WorldCapture.Job job = activeJob;
    if (job == null) {
      return;
    }
    if (Minecraft.getInstance().level != jobLevel) {
      activeJob = null;
      return;
    }
    if (!job.advance(CAPTURE_BUDGET)) {
      actionBar(Component.translatable("buildpack.select.progress", job.progress()));
      return;
    }
    activeJob = null;
    writing = true;
    NbtStructure structure = job.build();
    String base = jobBase;
    String author = jobAuthor;
    String size = jobSize;
    CompletableFuture.runAsync(() -> {
      try {
        Path dir = ImportScanner.ensureImportDir();
        Path nbt = uniqueTarget(dir, base + ".nbt");
        StructureNbtWriter.write(structure, nbt);
        Path lite = uniqueTarget(dir, base + ".litematic");
        LitematicWriter.write(structure, base, author, lite);
        finishCapture(Component.translatable("buildpack.select.captured", size,
            nbt.getFileName() + ", " + lite.getFileName()));
      } catch (Exception e) {
        I18nLog.warn(LOGGER, e, "buildpack.log.export_failed");
        finishCapture(Component.translatable("buildpack.select.failed",
            LocalizedIOException.messageOf(e)));
      }
    });
  }

  /** Re-enters the main thread to clear the writing flag and report the capture result. */
  private static void finishCapture(Component message) {
    Minecraft mc = Minecraft.getInstance();
    mc.execute(() -> {
      writing = false;
      if (mc.player != null) {
        mc.player.displayClientMessage(message, false);
      }
    });
  }

  /** Toggles whether captures include block-entity contents (chest contents, sign text, etc.). */
  public static void toggleBlockEntities() {
    captureBlockEntities = !captureBlockEntities;
    actionBar(Component.translatable("buildpack.select.contents",
        Component.translatable(captureBlockEntities
            ? "buildpack.select.on" : "buildpack.select.off")));
  }

  /** Draws a HUD readout of the current selection size, bounds, and capture-contents state. */
  public static void renderHud(GuiGraphics g) {
    Minecraft mc = Minecraft.getInstance();
    if (mc.player == null || mc.level == null || mc.options.hideGui) {
      return;
    }
    if (cornerA == null && cornerB == null) {
      return;
    }
    Font font = mc.font;
    int x = 4;
    int y = 4;
    if (activeJob != null) {
      g.drawString(font, Component.translatable("buildpack.select.progress", activeJob.progress()),
          x, y, 0xFFFFCC55, true);
      return;
    }
    if (cornerA != null && cornerB != null) {
      BlockPos min = new BlockPos(Math.min(cornerA.getX(), cornerB.getX()),
          Math.min(cornerA.getY(), cornerB.getY()), Math.min(cornerA.getZ(), cornerB.getZ()));
      BlockPos max = new BlockPos(Math.max(cornerA.getX(), cornerB.getX()),
          Math.max(cornerA.getY(), cornerB.getY()), Math.max(cornerA.getZ(), cornerB.getZ()));
      int sizeX = max.getX() - min.getX() + 1;
      int sizeY = max.getY() - min.getY() + 1;
      int sizeZ = max.getZ() - min.getZ() + 1;
      long volume = (long) sizeX * sizeY * sizeZ;
      int sizeColor = volume > MAX_VOLUME ? 0xFFFF6060 : 0xFF8AD8FF;
      g.drawString(font, Component.translatable("buildpack.select.hud_size",
          sizeX + "×" + sizeY + "×" + sizeZ, volume), x, y, sizeColor, true);
      String bounds = min.getX() + "," + min.getY() + "," + min.getZ()
          + " → " + max.getX() + "," + max.getY() + "," + max.getZ();
      g.drawString(font, bounds, x, y + font.lineHeight + 1, 0xFFB0B0B0, true);
      g.drawString(font, Component.translatable("buildpack.select.contents",
          Component.translatable(captureBlockEntities
              ? "buildpack.select.on" : "buildpack.select.off")),
          x, y + (font.lineHeight + 1) * 2, 0xFFB0B0B0, true);
    } else {
      g.drawString(font, Component.translatable("buildpack.select.hud_one"), x, y, 0xFFFFCC55, true);
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
