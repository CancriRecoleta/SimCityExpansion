package com.github.simcityexpansion.buildpack.ui.preview;

import java.io.IOException;

import com.github.simcityexpansion.buildpack.I18nLog;
import com.github.simcityexpansion.buildpack.convert.NbtStructure;
import com.mojang.blaze3d.platform.NativeImage;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Renders a representative building structure into PNG bytes for use as an exported pack icon. The
 * isometric view is preferred for a richer thumbnail; the top-down view is used as a fallback. The
 * result feeds {@link com.github.simcityexpansion.buildpack.install.PackExporter}, which bundles it
 * as {@code icon.png}.
 */
public final class PackIcon {
  private PackIcon() {}

  private static final Logger LOGGER = LoggerFactory.getLogger(PackIcon.class);

  /** Renders the structure to PNG bytes; returns {@code null} if it cannot be rendered or encoded. */
  @Nullable
  public static byte[] render(NbtStructure structure) {
    PixelImage image = IsoPreview.pixels(structure);
    if (image == null) {
      image = TopDownPreview.pixels(structure);
    }
    return image == null ? null : encode(image);
  }

  @Nullable
  private static byte[] encode(PixelImage image) {
    NativeImage nativeImage = new NativeImage(image.width(), image.height(), false);
    try {
      int[] argb = image.argb();
      for (int y = 0; y < image.height(); y++) {
        for (int x = 0; x < image.width(); x++) {
          int pixel = argb[y * image.width() + x];
          // ARGB -> ABGR (NativeImage pixel layout): swap red and blue channels.
          int abgr = (pixel & 0xFF00FF00)
              | ((pixel & 0x00FF0000) >>> 16)
              | ((pixel & 0x000000FF) << 16);
          nativeImage.setPixelRGBA(x, y, abgr);
        }
      }
      return nativeImage.asByteArray();
    } catch (IOException e) {
      I18nLog.warn(LOGGER, e, "buildpack.log.icon_failed");
      return null;
    } finally {
      nativeImage.close();
    }
  }
}
