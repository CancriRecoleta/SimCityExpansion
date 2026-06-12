package com.github.simcityexpansion.buildpack.install;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import com.github.simcityexpansion.buildpack.LocalizedIOException;
import com.github.simcityexpansion.buildpack.model.BuildingCategory;
import com.github.simcityexpansion.buildpack.model.PackArchive;
import com.github.simcityexpansion.buildpack.model.PackBuildingEntry;
import com.github.simcityexpansion.buildpack.model.PackManifest;
import com.github.simcityexpansion.buildpack.model.StructureFormat;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.network.chat.Component;

/**
 * zip 拓展包读取器。包格式（format 1）：
 *
 * <pre>
 * mypack.zip
 * ├── pack.json                      必需：{format,id,name,version,author,description}
 * └── buildings/&lt;分类&gt;/&lt;名&gt;.nbt|.litematic   结构（必需）
 *     buildings/&lt;分类&gt;/&lt;名&gt;.sk               可选：存在则原样安装（优先）
 *     buildings/&lt;分类&gt;/&lt;名&gt;.json             可选：JSON 元数据
 * </pre>
 */
public final class PackReader {
  private PackReader() {}

  /** 解析 zip 拓展包；清单缺失/非法时抛出 IOException。 */
  public static PackArchive read(Path zipPath) throws IOException {
    try (ZipFile zip = new ZipFile(zipPath.toFile())) {
      PackManifest manifest = readManifest(zip);

      Map<String, BuildingFiles> grouped = new HashMap<>();
      Enumeration<? extends ZipEntry> entries = zip.entries();
      while (entries.hasMoreElements()) {
        ZipEntry entry = entries.nextElement();
        if (entry.isDirectory()) {
          continue;
        }
        String path = entry.getName().replace('\\', '/');
        String[] parts = path.split("/");
        // buildings/<分类>/<文件名>
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
            : stripExtension(fileName);
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
        files.classifyPlainJson(zip);
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

  private static PackManifest readManifest(ZipFile zip) throws IOException {
    ZipEntry manifestEntry = zip.getEntry("pack.json");
    if (manifestEntry == null) {
      throw new LocalizedIOException(
          Component.translatable("buildpack.error.pack_no_manifest"));
    }
    JsonObject json;
    try (InputStreamReader reader = new InputStreamReader(
        zip.getInputStream(manifestEntry), StandardCharsets.UTF_8)) {
      json = JsonParser.parseReader(reader).getAsJsonObject();
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
    return new PackManifest(format, id,
        getString(json, "name", id),
        getString(json, "version", ""),
        getString(json, "author", ""),
        getString(json, "description", ""));
  }

  /** 读取 zip 内单个条目的全部字节（供界面直接读取包内建筑，无需解压）。 */
  public static byte[] readEntryBytes(Path zipPath, String entryName) throws IOException {
    try (ZipFile zip = new ZipFile(zipPath.toFile())) {
      ZipEntry entry = zip.getEntry(entryName);
      if (entry == null) {
        throw new LocalizedIOException(
            Component.translatable("buildpack.error.zip_entry_missing", entryName));
      }
      try (InputStream stream = zip.getInputStream(entry)) {
        return stream.readAllBytes();
      }
    }
  }

  private static String getString(JsonObject json, String key, String fallback) {
    return json.has(key) ? json.get(key).getAsString() : fallback;
  }

  private static String stripExtension(String fileName) {
    int dot = fileName.lastIndexOf('.');
    return dot > 0 ? fileName.substring(0, dot) : fileName;
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
     * 归类 {@code <名>.json}：v2 包里它就是 SimuKraft 原生定义；
     * v1 旧包把它当本模组元数据用——按内容启发式区分（含 offers/containers/job
     * 等玩法键则视为原生定义），保证旧包不破坏。
     */
    void classifyPlainJson(ZipFile zip) {
      if (plainJsonEntry == null) {
        return;
      }
      if (metaJsonEntry != null) {
        simukraftJsonEntry = plainJsonEntry;
        return;
      }
      try (InputStreamReader reader = new InputStreamReader(
          zip.getInputStream(zip.getEntry(plainJsonEntry)), StandardCharsets.UTF_8)) {
        JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
        if (json.has("offers") || json.has("containers") || json.has("job")) {
          simukraftJsonEntry = plainJsonEntry;
        } else {
          metaJsonEntry = plainJsonEntry;
        }
      } catch (IOException | RuntimeException e) {
        // 内容读不出来就按旧格式元数据处理，由安装链路兜底报错。
        metaJsonEntry = plainJsonEntry;
      }
    }
  }
}
