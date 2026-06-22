package com.github.simcityexpansion.buildpack.ui.preview;

import com.github.simcityexpansion.buildpack.convert.NbtStructure;
import com.github.simcityexpansion.buildpack.convert.StructureAnalysis;
import net.minecraft.client.gui.components.AbstractWidget;
import org.jetbrains.annotations.Nullable;

/**
 * Top-down preview: samples the highest non-air block in each column using palette map colors,
 * applies height-based shading (brighter at the top, darker at the bottom), for use with .nbt /
 * .schem files that have no embedded thumbnail.
 */
public final class TopDownPreview {
  private TopDownPreview() {}

  /** Horizontal footprint limit; structures exceeding this are not rendered (returns null so callers can fall back to a placeholder). */
  private static final long MAX_FOOTPRINT = 512L * 512L;

  /** Renders the top-down preview widget; returns null if the structure is too large or entirely empty. */
  @Nullable
  public static AbstractWidget create(NbtStructure structure) {
    int sizeX = structure.sizeX;
    int sizeZ = structure.sizeZ;
    if (sizeX <= 0 || sizeZ <= 0 || (long) sizeX * sizeZ > MAX_FOOTPRINT) {
      return null;
    }

    int[] paletteColors = StructureAnalysis.paletteMapColors(structure);
    int[] topColor = new int[sizeX * sizeZ];
    int[] topY = new int[sizeX * sizeZ];
    java.util.Arrays.fill(topY, Integer.MIN_VALUE);
    boolean any = false;
    for (NbtStructure.BlockEntry block : structure.blocks) {
      if (block.stateIndex() < 0 || block.stateIndex() >= paletteColors.length) {
        continue;
      }
      int color = paletteColors[block.stateIndex()];
      if (color == 0) {
        continue;
      }
      int column = block.z() * sizeX + block.x();
      if (block.y() > topY[column]) {
        topY[column] = block.y();
        topColor[column] = color;
        any = true;
      }
    }
    if (!any) {
      return null;
    }

    int maxY = Math.max(structure.sizeY - 1, 1);
    int[] pixels = new int[sizeX * sizeZ];
    for (int i = 0; i < pixels.length; i++) {
      if (topY[i] == Integer.MIN_VALUE) {
        continue;
      }
      pixels[i] = shade(topColor[i], 0.55f + 0.45f * topY[i] / maxY);
    }
    return StructurePreview.fromPixels(pixels, sizeX, sizeZ);
  }

  private static int shade(int argb, float factor) {
    float clamped = Math.min(1.0f, factor);
    int red = (int) (((argb >> 16) & 0xFF) * clamped);
    int green = (int) (((argb >> 8) & 0xFF) * clamped);
    int blue = (int) ((argb & 0xFF) * clamped);
    return (argb & 0xFF000000) | (red << 16) | (green << 8) | blue;
  }
}
