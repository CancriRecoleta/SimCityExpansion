package com.github.simcityexpansion.buildpack.model;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import com.github.simcityexpansion.buildpack.BuildPack;
import com.github.simcityexpansion.buildpack.I18nLog;
import com.github.simcityexpansion.buildpack.install.InstallRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 扫描 SimuKraft 建筑目录（{@code simukraftbuilding/<分类>/*.sk}），列出已安装建筑。 */
public final class InstalledScanner {
  private InstalledScanner() {}

  private static final Logger LOGGER = LoggerFactory.getLogger(InstalledScanner.class);

  /** 列出全部分类下的已安装建筑，按「分类 → 名称」排序。 */
  public static List<InstalledBuilding> scan(InstallRegistry registry) {
    List<InstalledBuilding> result = new ArrayList<>();
    for (BuildingCategory category : BuildingCategory.values()) {
      Path dir = category.dir();
      if (!Files.isDirectory(dir)) {
        continue;
      }
      try (Stream<Path> stream = Files.list(dir)) {
        stream.filter(Files::isRegularFile)
            .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".sk"))
            .forEach(skPath -> result.add(read(category, skPath, registry)));
      } catch (IOException e) {
        I18nLog.warn(LOGGER, e, "buildpack.log.installed_scan_failed", dir);
      }
    }
    result.sort(Comparator
        .comparing((InstalledBuilding b) -> b.category().ordinal())
        .thenComparing(b -> b.name().toLowerCase(Locale.ROOT)));
    return result;
  }

  private static InstalledBuilding read(
      BuildingCategory category, Path skPath, InstallRegistry registry) {
    Map<String, String> fields = new LinkedHashMap<>();
    boolean marked = false;
    try {
      for (String rawLine : Files.readAllLines(skPath, StandardCharsets.UTF_8)) {
        String line = stripBom(rawLine).trim();
        if (line.isEmpty()) {
          continue;
        }
        if (line.startsWith("#")) {
          if (line.contains(BuildPack.GENERATED_MARKER)) {
            marked = true;
          }
          continue;
        }
        int colon = line.indexOf(':');
        if (colon > 0) {
          fields.put(line.substring(0, colon).trim(), line.substring(colon + 1).trim());
        }
      }
    } catch (IOException e) {
      I18nLog.warn(LOGGER, e, "buildpack.log.sk_read_failed", skPath);
    }

    String baseName = stripExtension(skPath.getFileName().toString());
    String name = fields.getOrDefault("name", baseName);
    Path structurePath = findStructureFile(skPath.getParent(), baseName);
    String relative = category.dirName() + "/" + skPath.getFileName();
    String packId = registry.packOwning(relative).orElse(null);
    return new InstalledBuilding(
        category, name, skPath, structurePath, fields, marked || packId != null, packId);
  }

  private static Path findStructureFile(Path dir, String baseName) {
    // SimuKraft 自带建筑均为 .nbt；同时兼容用户手动放入的其他扩展名。
    for (String extension : new String[] {".nbt", ".litematic", ".schem", ".schematic"}) {
      Path candidate = dir.resolve(baseName + extension);
      if (Files.isRegularFile(candidate)) {
        return candidate;
      }
    }
    return null;
  }

  private static String stripExtension(String fileName) {
    int dot = fileName.lastIndexOf('.');
    return dot > 0 ? fileName.substring(0, dot) : fileName;
  }

  private static String stripBom(String line) {
    return !line.isEmpty() && line.charAt(0) == 0xFEFF ? line.substring(1) : line;
  }
}
