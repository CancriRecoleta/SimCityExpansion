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
 * 把已安装的建筑打包为本模组的 zip 拓展包（pack.json + buildings/&lt;分类&gt;/...），
 * 供社区分发；导出的包可直接放回导入目录再次安装。
 */
public final class PackExporter {
  private PackExporter() {}

  private static final DateTimeFormatter FILE_STAMP =
      DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

  /** 导出目录：{@code <游戏目录>/simcity_expansion/export/}。 */
  public static Path exportDir() {
    return BuildPack.gameDir().resolve("simcity_expansion").resolve("export");
  }

  /**
   * 把给定建筑导出为一个 zip 拓展包，返回生成的文件路径。
   * 元数据（.sk）与结构文件均原样打包。
   */
  /** 导出选项：包元数据 + 是否携带 .sk / SimuKraft JSON。 */
  public record ExportOptions(String name, String version, String author, String description,
      boolean includeSk, boolean includeJson) {}

  /** 把给定建筑按选项导出为一个 zip 拓展包，返回生成的文件路径。结构 .nbt 始终携带。 */
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
          // SimuKraft 原生职业/交易定义随包携带（格式 v2 直通）。
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
