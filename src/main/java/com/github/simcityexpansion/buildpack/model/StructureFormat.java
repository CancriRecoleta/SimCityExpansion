package com.github.simcityexpansion.buildpack.model;

import java.util.Locale;
import java.util.Optional;

/**
 * Structure file formats supported for import.
 *
 * <p>SimuKraft's build system only parses the vanilla NBT structure format (palette + blocks),
 * so {@link #LITEMATIC} must be converted to {@link #VANILLA_NBT} before being written to its
 * building directory during installation.
 */
public enum StructureFormat {
  /** .nbt template exported by a vanilla structure block. */
  VANILLA_NBT(".nbt"),
  /** Litematica projection .litematic. */
  LITEMATIC(".litematic"),
  /** Sponge / WorldEdit schematic .schem (v1/v2/v3). */
  SCHEM(".schem");

  private final String extension;

  StructureFormat(String extension) {
    this.extension = extension;
  }

  /** File extension (including dot, lowercase). */
  public String extension() {
    return extension;
  }

  /** Identifies the format by file name extension. */
  public static Optional<StructureFormat> byFileName(String fileName) {
    String lower = fileName.toLowerCase(Locale.ROOT);
    for (StructureFormat format : values()) {
      if (lower.endsWith(format.extension)) {
        return Optional.of(format);
      }
    }
    return Optional.empty();
  }
}
