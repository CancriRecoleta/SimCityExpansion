package com.github.simcityexpansion.buildpack.model;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import com.github.simcityexpansion.buildpack.BuildPack;
import com.github.simcityexpansion.buildpack.I18nLog;
import com.github.simcityexpansion.buildpack.install.InstallRegistry;
import com.github.simcityexpansion.buildpack.install.SimukraftZips;
import com.github.simcityexpansion.buildpack.install.SkFileReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Scans the zip building packages in the SimuKraft building root
 * ({@code simukraftbuilding/*.zip}, entries {@code buildings/<category>/*.sk}) and lists installed
 * buildings — the same view SimuKraft 2.0 itself assembles. Includes SimuKraft's built-in
 * {@code official_building.zip} and foreign zips (shown read-only, {@code managed=false}).
 */
public final class InstalledScanner {
  private InstalledScanner() {}

  private static final Logger LOGGER = LoggerFactory.getLogger(InstalledScanner.class);

  /** Lists all installed buildings across every zip package, sorted by category then name. */
  public static List<InstalledBuilding> scan(InstallRegistry registry) {
    List<InstalledBuilding> result = new ArrayList<>();
    for (Path zip : SimukraftZips.listZips()) {
      scanZip(zip, registry, result);
    }
    result.sort(Comparator
        .comparing((InstalledBuilding b) -> b.category().ordinal())
        .thenComparing(b -> b.name().toLowerCase(Locale.ROOT)));
    return result;
  }

  private static void scanZip(Path zip, InstallRegistry registry, List<InstalledBuilding> result) {
    String packId = registry.packOwningZip(zip.getFileName().toString()).orElse(null);
    boolean managed = SimukraftZips.isManaged(zip) || packId != null;
    // base key ("<category ordinal>/<lowercase base>") -> discovered files of one building
    Map<String, Entry> grouped = new LinkedHashMap<>();
    Map<String, byte[]> skBytes = new HashMap<>();
    try (ZipFile zipFile = new ZipFile(zip.toFile())) {
      Enumeration<? extends ZipEntry> entries = zipFile.entries();
      while (entries.hasMoreElements()) {
        ZipEntry zipEntry = entries.nextElement();
        if (zipEntry.isDirectory()) {
          continue;
        }
        String path = zipEntry.getName().replace('\\', '/');
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
          if (zipEntry.getSize() <= BuildPack.MAX_PACK_JSON_BYTES) {
            try (InputStream stream = zipFile.getInputStream(zipEntry)) {
              skBytes.put(key, stream.readAllBytes());
            } catch (IOException e) {
              I18nLog.warn(LOGGER, e, "buildpack.log.sk_read_failed", path);
            }
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
      String name = fields.getOrDefault("name", entry.baseName);
      result.add(new InstalledBuilding(entry.category, name.isBlank() ? entry.baseName : name,
          zip, entry.baseName, entry.hasStructure, entry.hasJson, fields, managed, packId));
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
