package com.github.simcityexpansion.buildpack.model;

import java.nio.file.Path;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

/**
 * A building already present in the SimuKraft buildings directory (discovered by .sk scan).
 *
 * @param category category the building belongs to
 * @param name the {@code name} field from the .sk file (falls back to the file base name if absent)
 * @param skPath path to the .sk metadata file
 * @param structurePath path to the structure file with the same base name (may be null, indicating metadata without a structure)
 * @param skFields all key-value pairs parsed from the .sk file
 * @param managed whether this building was generated/installed by this mod (the .sk carries a generation marker, or it is recorded in the build-pack registry)
 * @param packId pack id if installed by a zip build pack; otherwise null
 */
public record InstalledBuilding(
    BuildingCategory category,
    String name,
    Path skPath,
    @Nullable Path structurePath,
    Map<String, String> skFields,
    boolean managed,
    @Nullable String packId) {

  /** Base name of the .sk file. */
  public String baseName() {
    String fileName = skPath.getFileName().toString();
    int dot = fileName.lastIndexOf('.');
    return dot > 0 ? fileName.substring(0, dot) : fileName;
  }
}
