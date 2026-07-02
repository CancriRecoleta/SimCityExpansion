package com.github.simcityexpansion.buildpack.model;

import java.util.Optional;

import net.minecraft.network.chat.Component;

/**
 * SimuKraft's five fixed building categories, corresponding to the {@code buildings/<category>/}
 * entry directories inside a zip building package (and, pre-2.0, to loose subdirectories under
 * {@code simukraftbuilding/}).
 *
 * <p>The category names are hard-coded on the SimuKraft side (its BuildingPackageCatalog only
 * scans these five), so entries in this enum must not be added or removed arbitrarily.
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

  /** Category directory name (inside a package: {@code buildings/<dirName>/}). */
  public String dirName() {
    return dirName;
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
