package com.github.simcityexpansion.buildpack.ui.preview;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import com.github.simcityexpansion.buildpack.BuildPack;
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
 * 原版 .nbt 无内嵌预览，显示占位文本。
 */
public final class StructurePreview {
  private StructurePreview() {}

  private static final Logger LOGGER = LoggerFactory.getLogger(StructurePreview.class);
  private static final float SIZE = 90.0f;

  /** 已注册动态纹理缓存：像素数据哈希 → 纹理路径，避免重复注册。 */
  private static final Map<Integer, ResourceLocation> TEXTURE_CACHE = new HashMap<>();

  /** 为结构摘要构建预览元素。 */
  public static UIElement create(StructureInfo info) {
    if (info.previewArgb() != null && info.previewSize() > 0) {
      try {
        ResourceLocation texture = registerPreview(info.previewArgb(), info.previewSize());
        UIElement image = new UIElement();
        image.addClass(BuildPack.cls("preview-image"));
        image.layout(layout -> layout.width(SIZE).height(SIZE));
        image.style(style -> style.background(SpriteTexture.of(texture)));
        return image;
      } catch (RuntimeException e) {
        LOGGER.warn("BuildPack: 预览图注册失败", e);
      }
    }
    Label placeholder = new Label();
    placeholder.addClass(BuildPack.cls("preview-none"));
    placeholder.setValue(Component.translatable("buildpack.preview.none"));
    return placeholder;
  }

  private static ResourceLocation registerPreview(int[] argb, int side) {
    int hash = Arrays.hashCode(argb);
    return TEXTURE_CACHE.computeIfAbsent(hash, key -> {
      NativeImage image = new NativeImage(side, side, false);
      for (int y = 0; y < side; y++) {
        for (int x = 0; x < side; x++) {
          int pixel = argb[y * side + x];
          // ARGB → ABGR（NativeImage 像素布局），交换红蓝通道。
          int abgr = (pixel & 0xFF00FF00)
              | ((pixel & 0x00FF0000) >>> 16)
              | ((pixel & 0x000000FF) << 16);
          image.setPixelRGBA(x, y, abgr);
        }
      }
      ResourceLocation location = BuildPack.id("buildpack/preview_" + Integer.toHexString(key));
      Minecraft.getInstance().getTextureManager().register(location, new DynamicTexture(image));
      return location;
    });
  }
}
