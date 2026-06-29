package com.github.simcityexpansion.buildpack.install;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.github.simcityexpansion.buildpack.BuildPack;
import com.github.simcityexpansion.buildpack.LocalizedIOException;
import com.github.simcityexpansion.buildpack.model.InstalledBuilding;
import com.github.simcityexpansion.buildpack.model.PackManifest;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

/**
 * Packages installed buildings into a zip build pack for community distribution; the exported pack
 * can be placed back into the import directory and installed again. Layout:
 *
 * <pre>
 * &lt;name&gt;.zip
 * ├── pack.json                       manifest (pretty-printed)
 * ├── icon.png                        optional pack icon (recorded as "icon" in pack.json)
 * ├── index.json                      file manifest: every packed file with size + SHA-256
 * └── buildings/&lt;category&gt;/...        structure (always) + optional .sk / .json
 * </pre>
 */
public final class PackExporter {
  private PackExporter() {}

  private static final DateTimeFormatter FILE_STAMP =
      DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
  private static final Gson GSON =
      new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

  /** Export directory: {@code <game directory>/simcity_expansion/export/}. */
  public static Path exportDir() {
    return BuildPack.gameDir().resolve("simcity_expansion").resolve("export");
  }

  /** Export options: pack metadata and flags for including .sk / SimuKraft JSON. */
  public record ExportOptions(String name, String version, String author, String description,
      boolean includeSk, boolean includeJson) {}

  /**
   * Exports the given buildings as a zip build pack and returns the generated file path. The
   * structure file is always included; {@code .sk} and SimuKraft {@code .json} follow the options.
   * When {@code iconPng} is non-empty it is bundled as {@code icon.png} and recorded in pack.json.
   * An {@code index.json} file manifest (each packed file with its size and SHA-256) is always
   * written at the zip root.
   */
  public static Path export(List<InstalledBuilding> buildings, ExportOptions options,
      @Nullable byte[] iconPng) throws IOException {
    if (buildings.isEmpty()) {
      throw new LocalizedIOException(Component.translatable("buildpack.error.export_empty"));
    }
    Files.createDirectories(exportDir());
    String stamp = FILE_STAMP.format(LocalDateTime.now());
    String packId = "export." + stamp;
    Path target =
        uniqueZip(options.name().isBlank() ? "buildpack_export_" + stamp : options.name());

    boolean hasIcon = iconPng != null && iconPng.length > 0;
    String iconEntry = hasIcon ? "icon.png" : null;
    String generated = Instant.now().toString();
    List<FileRecord> records = new ArrayList<>();
    Set<String> writtenEntries = new HashSet<>();
    try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(target))) {
      byte[] manifest =
          manifestJson(options, packId, stamp, buildings.size(), iconEntry, generated)
              .getBytes(StandardCharsets.UTF_8);
      putEntry(zip, "pack.json", manifest);
      records.add(fileRecord("pack.json", "manifest", null, manifest));

      if (hasIcon) {
        putEntry(zip, iconEntry, iconPng);
        records.add(fileRecord(iconEntry, "icon", null, iconPng));
      }

      for (InstalledBuilding building : buildings) {
        String category = building.category().dirName();
        String dir = "buildings/" + category + "/";
        if (building.structurePath() != null) {
          copyFileEntry(zip, building.structurePath(), dir, "structure", category,
              writtenEntries, records);
        }
        if (options.includeSk()) {
          copyFileEntry(zip, building.skPath(), dir, "metadata", category,
              writtenEntries, records);
        }
        if (options.includeJson()) {
          // SimuKraft native job/trade definitions are bundled with the pack (format v2 pass-through).
          copyFileEntry(zip, building.skPath().resolveSibling(building.baseName() + ".json"),
              dir, "simukraft", category, writtenEntries, records);
        }
      }

      byte[] index = indexJson(packId, generated, iconEntry, records)
          .getBytes(StandardCharsets.UTF_8);
      putEntry(zip, "index.json", index);
    } catch (IOException e) {
      Files.deleteIfExists(target);
      throw e;
    }
    return target;
  }

  private static String manifestJson(ExportOptions options, String packId, String stamp,
      int buildingCount, @Nullable String iconEntry, String generated) {
    JsonObject json = new JsonObject();
    json.addProperty("format", PackManifest.CURRENT_FORMAT);
    json.addProperty("id", packId);
    json.addProperty("name", options.name().isBlank()
        ? Component.translatable("buildpack.export.pack_name", stamp).getString()
        : options.name());
    json.addProperty("version", options.version().isBlank() ? "1.0.0" : options.version());
    json.addProperty("author", options.author());
    json.addProperty("description", options.description().isBlank()
        ? Component.translatable("buildpack.export.pack_description", buildingCount).getString()
        : options.description());
    if (iconEntry != null) {
      json.addProperty("icon", iconEntry);
    }
    json.addProperty("generated", generated);
    return GSON.toJson(json);
  }

  /** Builds {@code index.json}: pack identity, generation time, and one record per packed file. */
  private static String indexJson(String packId, String generated, @Nullable String iconEntry,
      List<FileRecord> records) {
    JsonObject root = new JsonObject();
    root.addProperty("kind", "simcity_expansion:pack_index");
    root.addProperty("format", PackManifest.CURRENT_FORMAT);
    root.addProperty("packId", packId);
    root.addProperty("generated", generated);
    if (iconEntry != null) {
      root.addProperty("icon", iconEntry);
    }
    root.addProperty("totalFiles", records.size());
    JsonArray files = new JsonArray();
    for (FileRecord entry : records) {
      JsonObject file = new JsonObject();
      file.addProperty("path", entry.path());
      file.addProperty("type", entry.type());
      if (entry.category() != null) {
        file.addProperty("category", entry.category());
      }
      file.addProperty("size", entry.size());
      file.addProperty("sha256", entry.sha256());
      files.add(file);
    }
    root.add("files", files);
    return GSON.toJson(root);
  }

  private static Path uniqueZip(String name) {
    String base = name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
    if (base.isBlank()) {
      base = "buildpack_export";
    }
    Path target = exportDir().resolve(base + ".zip");
    int suffix = 2;
    while (Files.exists(target)) {
      target = exportDir().resolve(base + "_" + suffix++ + ".zip");
    }
    return target;
  }

  private static void copyFileEntry(ZipOutputStream zip, Path file, String dirPrefix, String type,
      String category, Set<String> writtenEntries, List<FileRecord> records) throws IOException {
    if (!Files.isRegularFile(file)) {
      return;
    }
    String entryName = dirPrefix + file.getFileName();
    if (!writtenEntries.add(entryName)) {
      return;
    }
    byte[] bytes = Files.readAllBytes(file);
    putEntry(zip, entryName, bytes);
    records.add(fileRecord(entryName, type, category, bytes));
  }

  private static void putEntry(ZipOutputStream zip, String name, byte[] bytes)
      throws IOException {
    zip.putNextEntry(new ZipEntry(name));
    zip.write(bytes);
    zip.closeEntry();
  }

  private static FileRecord fileRecord(String path, String type, @Nullable String category,
      byte[] bytes) {
    return new FileRecord(path, type, category, bytes.length, sha256(bytes));
  }

  private static String sha256(byte[] bytes) {
    try {
      byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes);
      StringBuilder hex = new StringBuilder(hash.length * 2);
      for (byte b : hash) {
        hex.append(Character.forDigit((b >> 4) & 0xF, 16));
        hex.append(Character.forDigit(b & 0xF, 16));
      }
      return hex.toString();
    } catch (NoSuchAlgorithmException e) {
      // SHA-256 is mandated for every Java platform; this branch is unreachable.
      throw new IllegalStateException(e);
    }
  }

  /** One entry in {@code index.json}: a packed file with its role, category, size, and checksum. */
  private record FileRecord(String path, String type, @Nullable String category, long size,
      String sha256) {}
}
