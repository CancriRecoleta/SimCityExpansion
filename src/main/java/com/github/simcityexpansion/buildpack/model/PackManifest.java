package com.github.simcityexpansion.buildpack.model;

/**
 * The {@code pack.json} manifest of a zip build pack.
 *
 * @param format pack format version (currently 1)
 * @param id globally unique pack id (e.g. {@code author.pack_name}), used for the install registry
 * @param name display name
 * @param version pack version string
 * @param author author
 * @param description description
 */
public record PackManifest(
    int format, String id, String name, String version, String author, String description) {

  /**
   * Current pack format version. From v2 onward: {@code <name>.meta.json} carries this mod's
   * metadata, and {@code <name>.json} is installed as-is as SimuKraft's profession/trade definition.
   */
  public static final int CURRENT_FORMAT = 2;

  /** Oldest format version that can still be read. */
  public static final int MIN_FORMAT = 1;
}
