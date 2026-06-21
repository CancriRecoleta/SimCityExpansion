package com.github.simcityexpansion.buildpack.ui.preview;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import com.github.simcityexpansion.buildpack.BuildPack;
import com.github.simcityexpansion.buildpack.I18nLog;
import com.github.simcityexpansion.buildpack.model.StructureInfo;
import com.github.simcityexpansion.buildpack.ui.BuildPackTheme;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 结构预览：litematic 内嵌的 PreviewImageData（方形 ARGB 像素）注册为动态纹理后渲染；
 * 任意 ARGB 像素阵列（如俯视图/等距图）也走同一管线。返回的 {@link AbstractWidget}
 * 会在给定区域内按比例居中绘制（最长边 120px）。
 */
public final class StructurePreview {
  private StructurePreview() {}

  private static final Logger LOGGER = LoggerFactory.getLogger(StructurePreview.class);
  private static final int MAX_SIZE = 120;

  /** 已注册动态纹理缓存：像素数据+尺寸哈希 → 纹理路径，避免重复注册。 */
  private static final Map<Long, ResourceLocation> TEXTURE_CACHE = new HashMap<>();

  /** litematic 内嵌缩略图预览；无内嵌图时返回 {@code null}（调用方决定回退）。 */
  @Nullable
  public static AbstractWidget embedded(StructureInfo info) {
    if (info.previewArgb() == null || info.previewSize() <= 0) {
      return null;
    }
    return fromPixels(info.previewArgb(), info.previewSize(), info.previewSize());
  }

  /** 把 ARGB 像素阵列注册为动态纹理并构建展示控件；注册失败时回退占位。 */
  public static AbstractWidget fromPixels(int[] argb, int width, int height) {
    try {
      ResourceLocation texture = registerTexture(argb, width, height);
      return new PreviewImage(texture, width, height);
    } catch (RuntimeException e) {
      I18nLog.warn(LOGGER, e, "buildpack.log.preview_failed");
      return placeholder();
    }
  }

  /** 「暂无预览」占位控件。 */
  public static AbstractWidget placeholder() {
    return new PreviewImage(null, 0, 0);
  }

  private static ResourceLocation registerTexture(int[] argb, int width, int height) {
    long key = (long) Arrays.hashCode(argb) * 31L + (long) width * 31L + height;
    return TEXTURE_CACHE.computeIfAbsent(key, unused -> {
      NativeImage image = new NativeImage(width, height, false);
      for (int y = 0; y < height; y++) {
        for (int x = 0; x < width; x++) {
          int pixel = argb[y * width + x];
          // ARGB → ABGR（NativeImage 像素布局），交换红蓝通道。
          int abgr = (pixel & 0xFF00FF00)
              | ((pixel & 0x00FF0000) >>> 16)
              | ((pixel & 0x000000FF) << 16);
          image.setPixelRGBA(x, y, abgr);
        }
      }
      ResourceLocation location =
          BuildPack.id("buildpack/preview_" + Long.toHexString(key & 0x7FFFFFFFFFFFFFFFL));
      Minecraft.getInstance().getTextureManager().register(location, new DynamicTexture(image));
      return location;
    });
  }

  /** 在给定区域内居中按比例绘制预览图；纹理为 {@code null} 时绘制占位文本。 */
  private static final class PreviewImage extends AbstractWidget {
    @Nullable
    private final ResourceLocation texture;
    private final int imgW;
    private final int imgH;

    PreviewImage(@Nullable ResourceLocation texture, int imgW, int imgH) {
      super(0, 0, 0, 0, Component.empty());
      this.texture = texture;
      this.imgW = imgW;
      this.imgH = imgH;
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
      int w = getWidth();
      int h = getHeight();
      if (texture == null || imgW <= 0 || imgH <= 0) {
        Font font = Minecraft.getInstance().font;
        String none = Component.translatable("buildpack.preview.none").getString();
        g.drawString(font, none, getX() + (w - font.width(none)) / 2,
            getY() + (h - font.lineHeight) / 2, BuildPackTheme.NONE, true);
        return;
      }
      float scale = Math.min((float) MAX_SIZE / Math.max(imgW, imgH),
          Math.min((float) w / imgW, (float) h / imgH));
      int dw = Math.max(1, Math.round(imgW * scale));
      int dh = Math.max(1, Math.round(imgH * scale));
      int dx = getX() + (w - dw) / 2;
      int dy = getY() + (h - dh) / 2;
      g.blit(texture, dx, dy, dw, dh, 0.0F, 0.0F, imgW, imgH, imgW, imgH);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {}
  }
}
