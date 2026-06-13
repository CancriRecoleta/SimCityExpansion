package com.github.simcityexpansion.buildpack.ui.preview;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import com.github.simcityexpansion.buildpack.BuildPack;
import com.github.simcityexpansion.buildpack.I18nLog;
import com.github.simcityexpansion.buildpack.model.StructureInfo;
import com.lowdragmc.lowdraglib2.gui.texture.SpriteTexture;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 结构预览：litematic 内嵌的 PreviewImageData（方形 ARGB 像素）注册为动态纹理后渲染；
 * 任意 ARGB 像素阵列（如俯视图）也走同一管线。
 */
public final class StructurePreview {
  private StructurePreview() {}

  private static final Logger LOGGER = LoggerFactory.getLogger(StructurePreview.class);
  private static final float MAX_SIZE = 90.0f;

  /** 已注册动态纹理缓存：像素数据+尺寸哈希 → 纹理路径，避免重复注册。 */
  private static final Map<Long, ResourceLocation> TEXTURE_CACHE = new HashMap<>();

  /** litematic 内嵌缩略图预览；无内嵌图时返回 null（调用方决定回退）。 */
  public static UIElement embedded(StructureInfo info) {
    if (info.previewArgb() == null || info.previewSize() <= 0) {
      return null;
    }
    return fromPixels(info.previewArgb(), info.previewSize(), info.previewSize());
  }

  /**
   * 把 ARGB 像素阵列注册为动态纹理并构建展示元素（最长边 {@value MAX_SIZE}px，保持纵横比）；
   * 注册失败时回退占位文本。
   */
  public static UIElement fromPixels(int[] argb, int width, int height) {
    try {
      ResourceLocation texture = registerTexture(argb, width, height);
      float scale = MAX_SIZE / Math.max(width, height);
      float drawWidth = Math.max(1.0f, width * scale);
      float drawHeight = Math.max(1.0f, height * scale);
      UIElement image = new UIElement();
      image.addClass(BuildPack.cls("preview-image"));
      image.layout(layout -> layout.width(drawWidth).height(drawHeight));
      image.style(style -> style.background(SpriteTexture.of(texture)));
      return image;
    } catch (RuntimeException e) {
      I18nLog.warn(LOGGER, e, "buildpack.log.preview_failed");
      return placeholder();
    }
  }

  /** 「暂无预览」占位。 */
  public static UIElement placeholder() {
    Label placeholder = new Label();
    placeholder.addClass(BuildPack.cls("preview-none"));
    placeholder.setValue(Component.translatable("buildpack.preview.none"));
    return placeholder;
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
}
