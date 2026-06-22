package com.github.simcityexpansion.buildpack.install;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.github.simcityexpansion.buildpack.BuildPack;
import com.github.simcityexpansion.buildpack.LocalizedIOException;
import com.github.simcityexpansion.buildpack.model.InstalledBuilding;
import com.github.simcityexpansion.buildpack.model.PackManifest;
import com.google.gson.JsonObject;
import net.minecraft.network.chat.Component;

/**
 * Packages installed buildings into a zip build pack (pack.json + buildings/&lt;category&gt;/...)
 * for community distribution; the exported pack can be placed back into the import directory
 * and installed again.
 */
public final class PackExporter {
  private PackExporter() {}

  private static final DateTimeFormatter FILE_STAMP =
      DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

  /** Export directory: {@code <game directory>/simcity_expansion/export/}. */
  public static Path exportDir() {
    return BuildPack.gameDir().resolve("simcity_expansion").resolve("export");
  }

  /**
   * Exports the given buildings as a zip build pack and returns the generated file path.
   * Metadata (.sk) and structure files are packaged as-is.
   */
  /** Export options: pack metadata and flags for including .sk / SimuKraft JSON. */
  public record ExportOptions(String name, String version, String author, String description,
      boolean includeSk, boolean includeJson) {}

  /** Exports the given buildings as a zip build pack according to the options and returns the generated file path. The structure .nbt is always included. */
  public static Path export(List<InstalledBuilding> buildings, ExportOptions options)
      throws IOException {
    if (buildings.isEmpty()) {
      throw new LocalizedIOException(Component.translatable("buildpack.error.export_empty"));
    }
    Files.createDirectories(exportDir());
    String stamp = FILE_STAMP.format(LocalDateTime.now());
    Path target = uniqueZip(options.name().isBlank() ? "buildpack_export_" + stamp : options.name());

    Set<String> writtenEntries = new HashSet<>();
    try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(target))) {
      putEntry(zip, "pack.json",
          manifestJson(options, stamp, buildings.size()).getBytes(StandardCharsets.UTF_8));
      for (InstalledBuilding building : buildings) {
        String dir = "buildings/" + building.category().dirName() + "/";
        if (building.structurePath() != null) {
          copyFileEntry(zip, building.structurePath(), dir, writtenEntries);
        }
        if (options.includeSk()) {
          copyFileEntry(zip, building.skPath(), dir, writtenEntries);
        }
        if (options.includeJson()) {
          // SimuKraft native job/trade definitions are bundled with the pack (format v2 pass-through).
          copyFileEntry(zip,
              building.skPath().resolveSibling(building.baseName() + ".json"),
              dir, writtenEntries);
        }
      }
    } catch (IOException e) {
      Files.deleteIfExists(target);
      throw e;
    }
    return target;
  }

  private static String manifestJson(ExportOptions options, String stamp, int buildingCount) {
    JsonObject json = new JsonObject();
    json.addProperty("format", PackManifest.CURRENT_FORMAT);
    json.addProperty("id", "export." + stamp);
    json.addProperty("name", options.name().isBlank()
        ? Component.translatable("buildpack.export.pack_name", stamp).getString()
        : options.name());
    json.addProperty("version", options.version().isBlank() ? "1.0.0" : options.version());
    json.addProperty("author", options.author());
    json.addProperty("description", options.description().isBlank()
        ? Component.translatable("buildpack.export.pack_description", buildingCount).getString()
        : options.description());
    return json.toString();
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

  private static void copyFileEntry(
      ZipOutputStream zip, Path file, String dirPrefix, Set<String> writtenEntries)
      throws IOException {
    if (!Files.isRegularFile(file)) {
      return;
    }
    String entryName = dirPrefix + file.getFileName();
    if (!writtenEntries.add(entryName)) {
      return;
    }
    putEntry(zip, entryName, Files.readAllBytes(file));
  }

  private static void putEntry(ZipOutputStream zip, String name, byte[] bytes)
      throws IOException {
    zip.putNextEntry(new ZipEntry(name));
    zip.write(bytes);
    zip.closeEntry();
  }
}
