package com.github.simcityexpansion.buildpack.ui.preview;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.github.simcityexpansion.buildpack.convert.NbtStructure;
import com.github.simcityexpansion.buildpack.convert.StructureAnalysis;
import net.minecraft.client.gui.components.AbstractWidget;
import org.jetbrains.annotations.Nullable;

/**
 * 等距体素 3D 预览：用调色板地图色把结构画成一堆等距小立方体（顶面最亮、左面次之、右面最暗，
 * 每块带深色描边），按 painter 算法从后往前逐个覆盖，得到带体积感与块感的 3D 形象。
 *
 * <p>渲染精细度：内部以 {@value #SS}× 超采样绘制再降采样（预乘 alpha 平均）做抗锯齿，
 * 边缘平滑、不锯齿。产出 ARGB 像素图，复用 {@link StructurePreview#fromPixels} 管线。
 * 结构过大或全空时返回 null，由调用方回退俯视图/占位。
 */
public final class IsoPreview {
  private IsoPreview() {}

  /** 体积上限，超过不渲染。 */
  private static final long MAX_VOLUME = 110L * 110 * 110;
  /** 降采样后（最终图）面积上限。 */
  private static final int MAX_PIXELS = 200 * 200;
  /** 最终图目标最长边。 */
  private static final int TARGET = 150;
  /** 立方体最大半宽（最终像素）。 */
  private static final int MAX_HALF = 11;
  /** 超采样倍率。 */
  private static final int SS = 2;

  /** 渲染等距 3D 预览；不适用时返回 null。 */
  @Nullable
  public static AbstractWidget create(NbtStructure s) {
    int sx = s.sizeX;
    int sy = s.sizeY;
    int sz = s.sizeZ;
    if (sx <= 0 || sy <= 0 || sz <= 0 || s.volume() > MAX_VOLUME) {
      return null;
    }
    int[] colors = StructureAnalysis.paletteMapColors(s);

    // 选立方体尺寸让最终图最长边约 TARGET，再乘 SS 得到渲染分辨率。
    int half = Math.max(1, Math.min(MAX_HALF, TARGET / Math.max(sx + sz, (sx + sz) / 2 + sy + 1)));
    int rHalf = half * SS;
    int rQuart = Math.max(1, rHalf / 2);
    int rSh = rHalf;

    // 只保留有颜色（非空气）方块，按 painter 顺序（深度 x+z、再 y、x）排序。
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

    // 第一遍求渲染包围盒。
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
    // 渲染尺寸补到 SS 的整数倍，便于整块降采样。
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
    return StructurePreview.fromPixels(downsample(px, rw, rh, fw, fh), fw, fh);
  }

  private static int ceil(int v, int unit) {
    return (v + unit - 1) / unit * unit;
  }

  /** 画一个等距立方体：顶面菱形 + 左/右侧面，三面分别明暗，并描深色边以区分相邻方块。 */
  private static void drawCube(int[] px, int w, int h, int cx, int cy,
      int half, int quart, int sh, int color) {
    int top = shade(color, 1.0f);
    int left = shade(color, 0.70f);
    int right = shade(color, 0.46f);
    int edge = shade(color, 0.30f);
    // 顶面菱形（边描深色）。
    for (int dy = 0; dy <= 2 * quart; dy++) {
      int hw = half - half * Math.abs(dy - quart) / quart;
      for (int dx = -hw; dx <= hw; dx++) {
        boolean border = dx == -hw || dx == hw || dy == 0 || dy == 2 * quart;
        set(px, w, h, cx + dx, cy + dy, border ? edge : top);
      }
    }
    // 左侧面：外缘与底边描边。
    for (int dx = 0; dx <= half; dx++) {
      int x = cx - half + dx;
      int yTop = cy + quart + dx * quart / half;
      for (int k = 0; k < sh; k++) {
        boolean border = dx == 0 || k == sh - 1;
        set(px, w, h, x, yTop + k, border ? edge : left);
      }
    }
    // 右侧面：外缘与底边描边。
    for (int dx = 0; dx <= half; dx++) {
      int x = cx + dx;
      int yTop = cy + quart + (half - dx) * quart / half;
      for (int k = 0; k < sh; k++) {
        boolean border = dx == half || k == sh - 1;
        set(px, w, h, x, yTop + k, border ? edge : right);
      }
    }
  }

  /** SS×SS 块预乘 alpha 平均降采样（抗锯齿，边缘半透明与背景融合）。 */
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
