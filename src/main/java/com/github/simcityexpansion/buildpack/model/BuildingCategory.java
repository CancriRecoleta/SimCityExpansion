package com.github.simcityexpansion.buildpack.model;

import java.nio.file.Path;
import java.util.Optional;

import com.github.simcityexpansion.buildpack.BuildPack;
import net.minecraft.network.chat.Component;

/**
 * SimuKraft's five fixed building categories, corresponding to subdirectory names under {@code simukraftbuilding/}.
 *
 * <p>The directory names are hard-coded on the SimuKraft side (its BuildingBuiltinResourceService
 * only copies/scans these five), so entries in this enum must not be added or removed arbitrarily.
 */
public enum BuildingCategory {
  RESIDENTIAL("residential"),
  COMMERCIAL("commercial"),
  INDUSTRY("industry"),
  PUBLIC("public"),
  OTHER("other");

  private final String dirName;

  BuildingCategory(String dirName) {
    this.dirName = dirName;
  }

  /** Subdirectory name under the SimuKraft building root directory. */
  public String dirName() {
    return dirName;
  }

  /** Target installation directory for this category ({@code <game-dir>/simukraftbuilding/<dirName>}). */
  public Path dir() {
    return BuildPack.simukraftDir().resolve(dirName);
  }

  /** Localized display name. */
  public Component displayName() {
    return Component.translatable("buildpack.category." + dirName);
  }

  /** Resolves a category by directory name (case-insensitive). */
  public static Optional<BuildingCategory> byDirName(String name) {
    for (BuildingCategory category : values()) {
      if (category.dirName.equalsIgnoreCase(name)) {
        return Optional.of(category);
      }
    }
    return Optional.empty();
  }
}
