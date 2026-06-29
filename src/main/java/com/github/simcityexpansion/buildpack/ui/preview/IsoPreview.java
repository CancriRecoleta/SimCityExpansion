package com.github.simcityexpansion.buildpack.ui.preview;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.github.simcityexpansion.buildpack.convert.NbtStructure;
import com.github.simcityexpansion.buildpack.convert.StructureAnalysis;
import net.minecraft.client.gui.components.AbstractWidget;
import org.jetbrains.annotations.Nullable;

/**
 * Isometric voxel 3D preview: renders a structure as a grid of isometric cubes using palette map
 * colors (top face brightest, left face mid, right face darkest, each cube outlined in a dark
 * edge), painted back-to-front via the painter's algorithm to produce a volumetric, blocky 3D
 * appearance.
 *
 * <p>Render quality: internally drawn at {@value #SS}x supersampling then downsampled
 * (premultiplied-alpha averaging) for anti-aliasing, yielding smooth, non-jagged edges. Produces
 * an ARGB pixel image reusing the {@link StructurePreview#fromPixels} pipeline. Returns null for
 * structures that are too large or entirely empty; callers should fall back to the top-down view
 * or a placeholder.
 */
public final class IsoPreview {
  private IsoPreview() {}

  /** Volume limit; structures exceeding this are not rendered. */
  private static final long MAX_VOLUME = 110L * 110 * 110;
  /** Pixel area limit for the final downsampled image. */
  private static final int MAX_PIXELS = 200 * 200;
  /** Target longest side (pixels) of the final image. */
  private static final int TARGET = 150;
  /** Maximum cube half-width in final pixels. */
  private static final int MAX_HALF = 11;
  /** Supersampling factor. */
  private static final int SS = 2;

  /** Renders the isometric 3D preview; returns null if not applicable. */
  @Nullable
  public static AbstractWidget create(NbtStructure s) {
    PixelImage image = pixels(s);
    return image == null
        ? null
        : StructurePreview.fromPixels(image.argb(), image.width(), image.height());
  }

  /** Computes the isometric ARGB pixel image; returns null if not applicable. */
  @Nullable
  public static PixelImage pixels(NbtStructure s) {
    int sx = s.sizeX;
    int sy = s.sizeY;
    int sz = s.sizeZ;
    if (sx <= 0 || sy <= 0 || sz <= 0 || s.volume() > MAX_VOLUME) {
      return null;
    }
    int[] colors = StructureAnalysis.paletteMapColors(s);

    // Choose cube size so the final image's longest side is approximately TARGET, then multiply by SS to get the render resolution.
    int half = Math.max(1, Math.min(MAX_HALF, TARGET / Math.max(sx + sz, (sx + sz) / 2 + sy + 1)));
    int rHalf = half * SS;
    int rQuart = Math.max(1, rHalf / 2);
    int rSh = rHalf;

    // Keep only colored (non-air) blocks, sorted in painter order (depth x+z, then y, then x).
    List<NbtStructure.BlockEntry> order = new ArrayList<>();
    for (NbtStructure.BlockEntry b : s.blocks) {
      int si = b.stateIndex();
      if (si >= 0 && si < colors.length && colors[si] != 0) {
        order.add(b);
      }
    }
    if (order.isEmpty()) {
      return null;
    }
    order.sort(Comparator.comparingInt((NbtStructure.BlockEntry b) -> b.x() + b.z())
        .thenComparingInt(NbtStructure.BlockEntry::y)
        .thenComparingInt(NbtStructure.BlockEntry::x));

    // First pass: compute the render bounding box.
    int minX = Integer.MAX_VALUE;
    int minY = Integer.MAX_VALUE;
    int maxX = Integer.MIN_VALUE;
    int maxY = Integer.MIN_VALUE;
    for (NbtStructure.BlockEntry b : order) {
      int cx = (b.x() - b.z()) * rHalf;
      int cy = (b.x() + b.z()) * rQuart - b.y() * rSh;
      minX = Math.min(minX, cx - rHalf);
      maxX = Math.max(maxX, cx + rHalf);
      minY = Math.min(minY, cy);
      maxY = Math.max(maxY, cy + 2 * rQuart + rSh - 1);
    }
    // Round render dimensions up to the nearest multiple of SS for clean block downsampling.
    int rw = ceil(maxX - minX + 1, SS);
    int rh = ceil(maxY - minY + 1, SS);
    int fw = rw / SS;
    int fh = rh / SS;
    if (fw <= 0 || fh <= 0 || (long) fw * fh > MAX_PIXELS) {
      return null;
    }

    int[] px = new int[rw * rh];
    for (NbtStructure.BlockEntry b : order) {
      int cx = (b.x() - b.z()) * rHalf - minX;
      int cy = (b.x() + b.z()) * rQuart - b.y() * rSh - minY;
      drawCube(px, rw, rh, cx, cy, rHalf, rQuart, rSh, colors[b.stateIndex()]);
    }
    return new PixelImage(downsample(px, rw, rh, fw, fh), fw, fh);
  }

  private static int ceil(int v, int unit) {
    return (v + unit - 1) / unit * unit;
  }

  /** Draws one isometric cube: diamond top face plus left/right side faces, each with distinct shading, and dark edges to distinguish adjacent blocks. */
  private static void drawCube(int[] px, int w, int h, int cx, int cy,
      int half, int quart, int sh, int color) {
    int top = shade(color, 1.0f);
    int left = shade(color, 0.70f);
    int right = shade(color, 0.46f);
    int edge = shade(color, 0.30f);
    // Top diamond face (edges drawn dark).
    for (int dy = 0; dy <= 2 * quart; dy++) {
      int hw = half - half * Math.abs(dy - quart) / quart;
      for (int dx = -hw; dx <= hw; dx++) {
        boolean border = dx == -hw || dx == hw || dy == 0 || dy == 2 * quart;
        set(px, w, h, cx + dx, cy + dy, border ? edge : top);
      }
    }
    // Left face: outer edge and bottom edge outlined.
    for (int dx = 0; dx <= half; dx++) {
      int x = cx - half + dx;
      int yTop = cy + quart + dx * quart / half;
      for (int k = 0; k < sh; k++) {
        boolean border = dx == 0 || k == sh - 1;
        set(px, w, h, x, yTop + k, border ? edge : left);
      }
    }
    // Right face: outer edge and bottom edge outlined.
    for (int dx = 0; dx <= half; dx++) {
      int x = cx + dx;
      int yTop = cy + quart + (half - dx) * quart / half;
      for (int k = 0; k < sh; k++) {
        boolean border = dx == half || k == sh - 1;
        set(px, w, h, x, yTop + k, border ? edge : right);
      }
    }
  }

  /** Downsamples by averaging SS×SS blocks with premultiplied alpha (anti-aliasing; semi-transparent edges blend with the background). */
  private static int[] downsample(int[] src, int sw, int sh, int dw, int dh) {
    int[] out = new int[dw * dh];
    int n = SS * SS;
    for (int dy = 0; dy < dh; dy++) {
      for (int dx = 0; dx < dw; dx++) {
        long ar = 0;
        long ag = 0;
        long ab = 0;
        long aa = 0;
        for (int oy = 0; oy < SS; oy++) {
          for (int ox = 0; ox < SS; ox++) {
            int sx = dx * SS + ox;
            int sy = dy * SS + oy;
            if (sx < sw && sy < sh) {
              int p = src[sy * sw + sx];
              int a = (p >>> 24) & 0xFF;
              ar += ((p >> 16) & 0xFF) * a;
              ag += ((p >> 8) & 0xFF) * a;
              ab += (p & 0xFF) * a;
              aa += a;
            }
          }
        }
        int alpha = (int) (aa / n);
        int r = aa > 0 ? (int) (ar / aa) : 0;
        int g = aa > 0 ? (int) (ag / aa) : 0;
        int b = aa > 0 ? (int) (ab / aa) : 0;
        out[dy * dw + dx] = (alpha << 24) | (r << 16) | (g << 8) | b;
      }
    }
    return out;
  }

  private static void set(int[] px, int w, int h, int x, int y, int color) {
    if (x >= 0 && x < w && y >= 0 && y < h) {
      px[y * w + x] = color;
    }
  }

  private static int shade(int argb, float factor) {
    float f = Math.min(1.0f, factor);
    int r = (int) (((argb >> 16) & 0xFF) * f);
    int g = (int) (((argb >> 8) & 0xFF) * f);
    int b = (int) ((argb & 0xFF) * f);
    return 0xFF000000 | (r << 16) | (g << 8) | b;
  }
}
