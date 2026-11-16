package com.github.simcityexpansion.buildpack.model;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.github.simcityexpansion.buildpack.BuildPack;
import com.github.simcityexpansion.buildpack.I18nLog;
import com.github.simcityexpansion.buildpack.install.InstallRegistry;
import com.github.simcityexpansion.buildpack.install.PackageFiles;
import com.github.simcityexpansion.buildpack.install.SimukraftZips;
import com.github.simcityexpansion.buildpack.install.SkFileReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Scans the building packages in the SimuKraft building root — {@code simukraftbuilding/*.zip}
 * and extracted package directories such as SimuKraft 2.3's {@code official_building/} (entries
 * {@code buildings/<category>/*.sk}) — and lists installed buildings, the same view SimuKraft
 * itself assembles. SimuKraft's official package, foreign zips and every directory package are
 * shown read-only ({@code managed=false}).
 */
public final class InstalledScanner {
  private InstalledScanner() {}

  private static final Logger LOGGER = LoggerFactory.getLogger(InstalledScanner.class);

  /** Lists all installed buildings across every package, sorted by category then name. */
  public static List<InstalledBuilding> scan(InstallRegistry registry) {
    List<InstalledBuilding> result = new ArrayList<>();
    for (Path zip : SimukraftZips.listPackages()) {
      scanPackage(zip, registry, result);
    }
    result.sort(Comparator
        .comparing((InstalledBuilding b) -> b.category().ordinal())
        .thenComparing(b -> b.name().toLowerCase(Locale.ROOT)));
    return result;
  }

  private static void scanPackage(Path zip, InstallRegistry registry,
      List<InstalledBuilding> result) {
    // Registry records only point at zips this mod wrote; directory packages are always read-only.
    String packId = Files.isDirectory(zip) ? null
        : registry.packOwningZip(zip.getFileName().toString()).orElse(null);
    boolean managed = SimukraftZips.isManaged(zip) || packId != null;
    // base key ("<category ordinal>/<lowercase base>") -> discovered files of one building
    Map<String, Entry> grouped = new LinkedHashMap<>();
    Map<String, byte[]> skBytes = new HashMap<>();
    try (PackageFiles files = PackageFiles.open(zip)) {
      for (String path : files.entries()) {
        SimukraftZips.BuildingEntry parsed = SimukraftZips.parseEntry(path).orElse(null);
        if (parsed == null) {
          continue;
        }
        BuildingCategory category = parsed.category();
        String lower = parsed.fileName().toLowerCase(Locale.ROOT);
        String base = FileNames.baseName(parsed.fileName());
        String key = category.ordinal() + "/" + base.toLowerCase(Locale.ROOT);
        Entry entry = grouped.computeIfAbsent(key, ignored -> new Entry(category, base));
        if (lower.endsWith(".sk")) {
          entry.hasSk = true;
          try {
            files.readOptional(path, BuildPack.MAX_PACK_JSON_BYTES)
                .ifPresent(bytes -> skBytes.put(key, bytes));
          } catch (IOException e) {
            I18nLog.warn(LOGGER, e, "buildpack.log.sk_read_failed", path);
          }
        } else if (lower.endsWith(".nbt")) {
          entry.hasStructure = true;
        } else if (lower.endsWith(".json") && !lower.endsWith(".meta.json")) {
          entry.hasJson = true;
        }
      }
    } catch (IOException e) {
      I18nLog.warn(LOGGER, e, "buildpack.log.installed_scan_failed", zip);
      return;
    }

    for (Map.Entry<String, Entry> mapEntry : grouped.entrySet()) {
      Entry entry = mapEntry.getValue();
      // Like SimuKraft, a building exists only where a .sk metadata entry does.
      if (!entry.hasSk) {
        continue;
      }
      byte[] bytes = skBytes.get(mapEntry.getKey());
      Map<String, String> fields = bytes != null
          ? SkFileReader.parseFields(bytes) : new LinkedHashMap<>();
      List<PoiLine> poiLines = bytes != null ? SkFileReader.parsePoiLines(bytes) : List.of();
      String name = fields.getOrDefault("name", entry.baseName);
      result.add(new InstalledBuilding(entry.category, name.isBlank() ? entry.baseName : name,
          zip, entry.baseName, entry.hasStructure, entry.hasJson, fields, poiLines,
          managed, packId));
    }
  }

  private static final class Entry {
    final BuildingCategory category;
    final String baseName;
    boolean hasSk;
    boolean hasStructure;
    boolean hasJson;

    Entry(BuildingCategory category, String baseName) {
      this.category = category;
      this.baseName = baseName;
    }
  }
}
