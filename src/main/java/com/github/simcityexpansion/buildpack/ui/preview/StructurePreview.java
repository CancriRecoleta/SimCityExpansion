package com.github.simcityexpansion.buildpack.ui.preview;

import java.io.IOException;
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
 * Structure preview: registers a litematic's embedded PreviewImageData (square ARGB pixels) as a
 * dynamic texture and renders it; arbitrary ARGB pixel arrays (e.g., top-down or isometric views)
 * use the same pipeline. Encoded PNG bytes (pack icons) are decoded through {@link #fromPng}. The
 * returned {@link AbstractWidget} draws the image centered and proportionally scaled within the
 * given area (longest side 120px).
 */
public final class StructurePreview {
  private StructurePreview() {}

  private static final Logger LOGGER = LoggerFactory.getLogger(StructurePreview.class);
  private static final int MAX_SIZE = 120;

  /** Cache of registered dynamic textures: pixel-data + size hash -> texture location, to avoid duplicate registrations. */
  private static final Map<Long, ResourceLocation> TEXTURE_CACHE = new HashMap<>();

  /** Cache of textures decoded from PNG bytes: byte hash -> texture + decoded size. */
  private static final Map<Long, Registered> PNG_CACHE = new HashMap<>();

  /** A registered PNG texture together with its decoded dimensions. */
  private record Registered(ResourceLocation texture, int width, int height) {}

  /** Returns a preview widget from the litematic's embedded thumbnail; returns {@code null} if no embedded image exists (caller decides the fallback). */
  @Nullable
  public static AbstractWidget embedded(StructureInfo info) {
    if (info.previewArgb() == null || info.previewSize() <= 0) {
      return null;
    }
    return fromPixels(info.previewArgb(), info.previewSize(), info.previewSize());
  }

  /** Registers an ARGB pixel array as a dynamic texture and builds the display widget; falls back to a placeholder on registration failure. */
  public static AbstractWidget fromPixels(int[] argb, int width, int height) {
    try {
      ResourceLocation texture = registerTexture(argb, width, height);
      return new PreviewImage(texture, width, height);
    } catch (RuntimeException e) {
      I18nLog.warn(LOGGER, e, "buildpack.log.preview_failed");
      return placeholder();
    }
  }

  /** Decodes PNG bytes (e.g. a pack icon) into a display widget; returns a placeholder if the bytes are empty or cannot be decoded. */
  public static AbstractWidget fromPng(@Nullable byte[] pngBytes) {
    if (pngBytes == null || pngBytes.length == 0) {
      return placeholder();
    }
    try {
      long key = (long) Arrays.hashCode(pngBytes) * 31L + pngBytes.length;
      Registered registered = PNG_CACHE.get(key);
      if (registered == null) {
        NativeImage image = NativeImage.read(pngBytes);
        ResourceLocation location =
            BuildPack.id("buildpack/icon_" + Long.toHexString(key & 0x7FFFFFFFFFFFFFFFL));
        Minecraft.getInstance().getTextureManager().register(location, new DynamicTexture(image));
        registered = new Registered(location, image.getWidth(), image.getHeight());
        PNG_CACHE.put(key, registered);
      }
      return new PreviewImage(registered.texture(), registered.width(), registered.height());
    } catch (IOException | RuntimeException e) {
      I18nLog.warn(LOGGER, e, "buildpack.log.preview_failed");
      return placeholder();
    }
  }

  /** Returns the "no preview available" placeholder widget. */
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
          // ARGB -> ABGR (NativeImage pixel layout): swap red and blue channels.
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

  /** Draws the preview image centered and proportionally scaled within the given area; draws placeholder text when the texture is {@code null}. */
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
