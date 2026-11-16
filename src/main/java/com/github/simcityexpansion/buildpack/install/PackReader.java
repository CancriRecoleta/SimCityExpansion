package com.github.simcityexpansion.buildpack.install;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.github.simcityexpansion.buildpack.BuildPack;
import com.github.simcityexpansion.buildpack.LocalizedIOException;
import com.github.simcityexpansion.buildpack.model.BuildingCategory;
import com.github.simcityexpansion.buildpack.model.FileNames;
import com.github.simcityexpansion.buildpack.model.PackArchive;
import com.github.simcityexpansion.buildpack.model.PackBuildingEntry;
import com.github.simcityexpansion.buildpack.model.PackManifest;
import com.github.simcityexpansion.buildpack.model.StructureFormat;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.network.chat.Component;

/**
 * Reader for build packs, either zipped or extracted into a directory of the same layout (read
 * through {@link PackageFiles}). Layout:
 *
 * <pre>
 * mypack.zip  (or mypack/)
 * ├── pack.json                      required: {format,id,name,version,author,description,icon?}
 * ├── icon.png                       optional: pack icon (referenced by pack.json "icon")
 * ├── index.json                     optional: file manifest written on export; ignored on read
 * └── buildings/&lt;category&gt;/&lt;name&gt;.nbt|.litematic   structure (required)
 *     buildings/&lt;category&gt;/&lt;name&gt;.sk               optional: installed as-is when present (takes priority)
 *     buildings/&lt;category&gt;/&lt;name&gt;.json             optional: JSON metadata
 * </pre>
 *
 * <p>Root-level {@code icon.png} and {@code index.json} are not building files and are skipped by
 * the building scan; only {@code pack.json} and {@code buildings/<category>/...} are interpreted.
 */
public final class PackReader {
  private PackReader() {}

  /**
   * Parses a build pack — a zip archive or an extracted directory with the same layout (such as
   * SimuKraft 2.3's bundled official package); throws {@link IOException} if the manifest is
   * missing or invalid.
   */
  public static PackArchive read(Path zipPath) throws IOException {
    try (PackageFiles zip = PackageFiles.open(zipPath)) {
      PackManifest manifest = readManifest(zip);

      Map<String, BuildingFiles> grouped = new HashMap<>();
      for (String path : zip.entries()) {
        String[] parts = path.split("/");
        // buildings/<category>/<file name>
        if (parts.length != 3 || !"buildings".equals(parts[0])) {
          continue;
        }
        BuildingCategory category = BuildingCategory.byDirName(parts[1]).orElse(null);
        if (category == null) {
          continue;
        }
        String fileName = parts[2];
        String lower = fileName.toLowerCase(Locale.ROOT);
        String baseName = lower.endsWith(".meta.json")
            ? fileName.substring(0, fileName.length() - ".meta.json".length())
            : FileNames.baseName(fileName);
        BuildingFiles files = grouped.computeIfAbsent(
            parts[1] + "/" + baseName, key -> new BuildingFiles(category, baseName));
        StructureFormat format = StructureFormat.byFileName(lower).orElse(null);
        if (format != null) {
          files.structureEntry = path;
          files.format = format;
        } else if (lower.endsWith(".sk")) {
          files.skEntry = path;
        } else if (lower.endsWith(".meta.json")) {
          files.metaJsonEntry = path;
        } else if (lower.endsWith(".json")) {
          files.plainJsonEntry = path;
        }
      }

      List<PackBuildingEntry> buildings = new ArrayList<>();
      for (BuildingFiles files : grouped.values()) {
        if (files.structureEntry == null) {
          continue;
        }
        files.classifyPlainJson(zip, manifest.format());
        buildings.add(new PackBuildingEntry(files.category, files.baseName,
            files.structureEntry, files.format, files.skEntry,
            files.metaJsonEntry, files.simukraftJsonEntry));
      }
      if (buildings.isEmpty()) {
        throw new LocalizedIOException(Component.translatable("buildpack.error.pack_empty"));
      }
      buildings.sort(Comparator
          .comparing((PackBuildingEntry entry) -> entry.category().ordinal())
          .thenComparing(entry -> entry.name().toLowerCase(Locale.ROOT)));
      return new PackArchive(zipPath, manifest, List.copyOf(buildings));
    }
  }

  private static PackManifest readManifest(PackageFiles zip) throws IOException {
    byte[] manifestBytes = zip.readOptional("pack.json", BuildPack.MAX_PACK_JSON_BYTES)
        .orElseThrow(() -> new LocalizedIOException(
            Component.translatable("buildpack.error.pack_no_manifest")));
    JsonObject json;
    try {
      json = JsonParser.parseString(stripBom(new String(manifestBytes, StandardCharsets.UTF_8)))
          .getAsJsonObject();
    } catch (RuntimeException e) {
      throw new LocalizedIOException(Component.translatable(
          "buildpack.error.pack_bad_manifest", String.valueOf(e.getMessage())));
    }
    int format = json.has("format") ? json.get("format").getAsInt() : 0;
    if (format < PackManifest.MIN_FORMAT || format > PackManifest.CURRENT_FORMAT) {
      throw new LocalizedIOException(
          Component.translatable("buildpack.error.pack_format", format));
    }
    String id = json.has("id") ? json.get("id").getAsString() : "";
    if (id.isBlank()) {
      throw new LocalizedIOException(Component.translatable("buildpack.error.pack_no_id"));
    }
    String icon = getString(json, "icon", "");
    return new PackManifest(format, id,
        getString(json, "name", id),
        getString(json, "version", ""),
        getString(json, "author", ""),
        getString(json, "description", ""),
        icon.isBlank() ? null : icon);
  }

  /**
   * Reads all bytes of a single entry from a zip or directory pack (used by the UI to read
   * buildings directly without extracting the archive).
   */
  public static byte[] readEntryBytes(Path zipPath, String entryName) throws IOException {
    try (PackageFiles zip = PackageFiles.open(zipPath)) {
      return zip.read(entryName);
    }
  }

  /**
   * Whether {@code path} looks like a build pack: a {@code .zip} file, or a directory with a
   * {@code pack.json} at its root (an extracted pack).
   */
  public static boolean isPackPath(Path path) {
    if (Files.isDirectory(path)) {
      return Files.isRegularFile(path.resolve("pack.json"));
    }
    return Files.isRegularFile(path)
        && path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip");
  }

  private static String getString(JsonObject json, String key, String fallback) {
    return json.has(key) ? json.get(key).getAsString() : fallback;
  }

  /** Drops a leading UTF-8 byte-order mark (hand-edited manifests on Windows often carry one). */
  private static String stripBom(String text) {
    return text.startsWith("﻿") ? text.substring(1) : text;
  }

  private static final class BuildingFiles {
    final BuildingCategory category;
    final String baseName;
    String structureEntry;
    StructureFormat format;
    String skEntry;
    String metaJsonEntry;
    String plainJsonEntry;
    String simukraftJsonEntry;

    BuildingFiles(BuildingCategory category, String baseName) {
      this.category = category;
      this.baseName = baseName;
    }

    /**
     * Classifies {@code <name>.json}. From format v2 on, this mod's metadata always lives in
     * {@code <name>.meta.json}, so a plain {@code <name>.json} is unambiguously a SimuKraft native
     * definition. Only v1 legacy packs are ambiguous; there the two are distinguished heuristically
     * by content (gameplay keys such as offers/containers/job indicate a native definition).
     */
    void classifyPlainJson(PackageFiles zip, int format) {
      if (plainJsonEntry == null) {
        return;
      }
      if (metaJsonEntry != null || format >= 2) {
        simukraftJsonEntry = plainJsonEntry;
        return;
      }
      try {
        byte[] bytes = zip.read(plainJsonEntry);
        JsonObject json = JsonParser.parseString(stripBom(new String(bytes, StandardCharsets.UTF_8)))
            .getAsJsonObject();
        if (json.has("offers") || json.has("containers") || json.has("job")) {
          simukraftJsonEntry = plainJsonEntry;
        } else {
          metaJsonEntry = plainJsonEntry;
        }
      } catch (IOException | RuntimeException e) {
        // If the content cannot be read, treat it as legacy-format metadata and let the install pipeline report the error.
        metaJsonEntry = plainJsonEntry;
      }
    }
  }
}
