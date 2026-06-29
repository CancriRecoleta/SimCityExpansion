package com.github.simcityexpansion.buildpack.model;

import org.jetbrains.annotations.Nullable;

/**
 * The {@code pack.json} manifest of a zip build pack.
 *
 * @param format pack format version (currently 2)
 * @param id globally unique pack id (e.g. {@code author.pack_name}), used for the install registry
 * @param name display name
 * @param version pack version string
 * @param author author
 * @param description description
 * @param icon entry name of the pack icon inside the zip (e.g. {@code icon.png}); null when absent
 */
public record PackManifest(
    int format, String id, String name, String version, String author, String description,
    @Nullable String icon) {

  /**
   * Current pack format version. From v2 onward: {@code <name>.meta.json} carries this mod's
   * metadata, and {@code <name>.json} is installed as-is as SimuKraft's profession/trade definition.
   * An optional {@code icon.png} and {@code index.json} (file manifest) may sit at the zip root;
   * both are additive and ignored by readers that do not understand them.
   */
  public static final int CURRENT_FORMAT = 2;

  /** Oldest format version that can still be read. */
  public static final int MIN_FORMAT = 1;
}
