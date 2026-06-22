package com.github.simcityexpansion.buildpack.ui;

import java.util.Comparator;
import java.util.Locale;

import com.github.simcityexpansion.buildpack.model.ImportFile;
import com.github.simcityexpansion.buildpack.model.ImportIndex;
import net.minecraft.network.chat.Component;

/** Sort modes for the imported file list. */
public enum SortMode {
  /** Sort by file name, ascending. */
  NAME("name"),
  /** Sort by modification time, descending (newest first). */
  MODIFIED("modified"),
  /** Sort by file size, descending (largest first). */
  SIZE("size"),
  /** Sort by non-air block count, descending (requires enriched index). */
  BLOCKS("blocks"),
  /** Sort by bounding volume, descending (requires enriched index). */
  VOLUME("volume");

  private final String key;

  SortMode(String key) {
    this.key = key;
  }

  /** Localized display name. */
  public Component displayName() {
    return Component.translatable("buildpack.sort." + key);
  }

  /** Comparator for imported files corresponding to this sort mode. */
  public Comparator<ImportFile> comparator() {
    return switch (this) {
      case NAME -> Comparator.comparing(file -> file.fileName().toLowerCase(Locale.ROOT));
      case MODIFIED -> Comparator.comparing(ImportFile::modifiedAt).reversed();
      case SIZE -> Comparator.comparingLong(ImportFile::sizeBytes).reversed();
      case BLOCKS -> Comparator.comparingLong(
          (ImportFile file) -> ImportIndex.blocks(file.path())).reversed();
      case VOLUME -> Comparator.comparingLong(
          (ImportFile file) -> ImportIndex.volume(file.path())).reversed();
    };
  }
}
