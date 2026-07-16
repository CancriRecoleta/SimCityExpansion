package com.github.simcityexpansion.buildpack.model;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

/**
 * A building installed in one of the zip packages SimuKraft 2.0 reads from
 * {@code simukraftbuilding/*.zip} (discovered by scanning {@code buildings/<category>/*.sk}
 * entries inside each zip).
 *
 * @param category category the building belongs to (from the entry path inside the zip)
 * @param name the {@code name} field from the .sk entry (falls back to the entry base name if absent)
 * @param zipPath the zip package the building lives in
 * @param baseName base name of the .sk entry (without extension)
 * @param hasStructure whether a same-base {@code .nbt} structure entry exists in the zip
 * @param hasJson whether a same-base SimuKraft native {@code .json} definition exists in the zip
 * @param skFields all key-value pairs parsed from the .sk entry
 * @param poiLines all {@code poi:} lines parsed from the .sk entry, in file order (these repeat,
 *     so they cannot live in the key-value map)
 * @param managed whether this mod owns the zip (its file name carries the managed prefix, or the
 *     zip is recorded in the build-pack registry) and may therefore modify or delete the building
 * @param packId pack id if installed by a zip build pack recorded in the registry; otherwise null
 */
public record InstalledBuilding(
    BuildingCategory category,
    String name,
    Path zipPath,
    String baseName,
    boolean hasStructure,
    boolean hasJson,
    Map<String, String> skFields,
    List<PoiLine> poiLines,
    boolean managed,
    @Nullable String packId) {

  /** File name of the zip package this building lives in. */
  public String zipFileName() {
    return zipPath.getFileName().toString();
  }

  /** Zip-internal entry path of the .sk metadata ({@code buildings/<category>/<base>.sk}). */
  public String skEntry() {
    return entry(baseName + ".sk");
  }

  /** Zip-internal entry path of the structure, or null when the zip carries only metadata. */
  @Nullable
  public String structureEntry() {
    return hasStructure ? entry(baseName + ".nbt") : null;
  }

  /** Zip-internal entry path of the SimuKraft native definition, or null when absent. */
  @Nullable
  public String jsonEntry() {
    return hasJson ? entry(baseName + ".json") : null;
  }

  private String entry(String fileName) {
    return "buildings/" + category.dirName() + "/" + fileName;
  }
}
