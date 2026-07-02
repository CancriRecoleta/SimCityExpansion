package com.github.simcityexpansion.buildpack.install;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import com.github.simcityexpansion.buildpack.BuildPack;
import com.github.simcityexpansion.buildpack.I18nLog;
import com.github.simcityexpansion.buildpack.model.BuildingCategory;
import com.github.simcityexpansion.buildpack.model.FileNames;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One-time migration of the pre-2.0 SimuKraft layout. Older SimuKraft (and older versions of this
 * mod) kept loose building files under {@code simukraftbuilding/<category>/}; SimuKraft 2.0 ignores
 * those completely and reads only {@code simukraftbuilding/*.zip}. This migration packs every loose
 * {@code .sk}/{@code .nbt}/{@code .json} into the managed local zip ({@code sce_local.zip}), moves
 * the originals (all of them, including formats a zip package cannot carry) into
 * {@code simukraftbuilding/legacy_backup/<category>/}, and rewrites legacy registry records so
 * pack uninstall keeps working.
 *
 * <p>Idempotent and cheap when there is nothing to migrate; safe to call from server start, the
 * manager GUI, and commands (serialized on the class lock).
 */
public final class LegacyMigration {
  private LegacyMigration() {}

  private static final Logger LOGGER = LoggerFactory.getLogger(LegacyMigration.class);

  /** Whether any legacy loose category directory with files exists. */
  public static boolean hasLegacyFiles() {
    for (BuildingCategory category : BuildingCategory.values()) {
      Path dir = BuildPack.simukraftDir().resolve(category.dirName());
      if (!Files.isDirectory(dir)) {
        continue;
      }
      try (Stream<Path> stream = Files.list(dir)) {
        if (stream.anyMatch(Files::isRegularFile)) {
          return true;
        }
      } catch (IOException e) {
        I18nLog.warn(LOGGER, e, "buildpack.log.installed_scan_failed", dir);
      }
    }
    return false;
  }

  /** Runs the migration when legacy loose files exist; appends result messages to {@code messages}. */
  public static synchronized void migrateIfNeeded(InstallRegistry registry,
      List<Component> messages) {
    if (!hasLegacyFiles()) {
      return;
    }
    int migrated = 0;
    // Old base name ("category/base", lowercase) -> final base name inside the local zip.
    Map<String, String> renames = new HashMap<>();
    Map<String, byte[]> put = new LinkedHashMap<>();
    Map<BuildingCategory, Set<String>> taken = SimukraftZips.baseNamesByCategory(Set.of());

    for (BuildingCategory category : BuildingCategory.values()) {
      Path dir = BuildPack.simukraftDir().resolve(category.dirName());
      if (!Files.isDirectory(dir)) {
        continue;
      }
      // Group the category's loose files by base name; only .sk/.nbt/.json can live in a package.
      Map<String, List<Path>> groups = new LinkedHashMap<>();
      try (Stream<Path> stream = Files.list(dir)) {
        stream.filter(Files::isRegularFile).forEach(path -> {
          String lower = path.getFileName().toString().toLowerCase(Locale.ROOT);
          if (lower.endsWith(".sk") || lower.endsWith(".nbt") || lower.endsWith(".json")) {
            groups.computeIfAbsent(
                FileNames.baseName(path.getFileName().toString()), key -> new ArrayList<>())
                .add(path);
          }
        });
      } catch (IOException e) {
        I18nLog.warn(LOGGER, e, "buildpack.log.installed_scan_failed", dir);
        continue;
      }

      for (Map.Entry<String, List<Path>> group : groups.entrySet()) {
        String finalBase = BuildingInstaller.resolveConflict(taken.get(category), group.getKey());
        renames.put(category.dirName() + "/" + group.getKey().toLowerCase(Locale.ROOT), finalBase);
        for (Path file : group.getValue()) {
          try {
            put.put(SimukraftZips.entryPath(category,
                    finalBase + FileNames.extension(file.getFileName().toString())),
                Files.readAllBytes(file));
          } catch (IOException e) {
            I18nLog.warn(LOGGER, e, "buildpack.log.sk_read_failed", file);
          }
        }
        migrated++;
      }
    }

    if (!put.isEmpty()) {
      try {
        SimukraftZips.updateZip(SimukraftZips.localZip(), put, List.of());
      } catch (IOException e) {
        I18nLog.warn(LOGGER, e, "buildpack.log.install_failed", SimukraftZips.localZip());
        messages.add(Component.translatable("buildpack.msg.migrate_failed"));
        return;
      }
    }

    moveLooseFilesToBackup();
    rewriteLegacyRegistry(registry, renames);
    I18nLog.info(LOGGER, "buildpack.log.migrated", migrated);
    messages.add(Component.translatable("buildpack.msg.migrated", migrated));
  }

  /** Moves every remaining loose file into the backup directory and removes emptied category dirs. */
  private static void moveLooseFilesToBackup() {
    for (BuildingCategory category : BuildingCategory.values()) {
      Path dir = BuildPack.simukraftDir().resolve(category.dirName());
      if (!Files.isDirectory(dir)) {
        continue;
      }
      Path backupDir = BuildPack.legacyBackupDir().resolve(category.dirName());
      try (Stream<Path> stream = Files.list(dir)) {
        for (Path file : stream.filter(Files::isRegularFile).toList()) {
          try {
            Files.createDirectories(backupDir);
            String name = file.getFileName().toString();
            Files.move(file, FileNames.unique(backupDir,
                FileNames.baseName(name), FileNames.extension(name)));
          } catch (IOException e) {
            I18nLog.warn(LOGGER, e, "buildpack.log.move_failed", file);
          }
        }
      } catch (IOException e) {
        I18nLog.warn(LOGGER, e, "buildpack.log.installed_scan_failed", dir);
      }
      try (Stream<Path> stream = Files.list(dir)) {
        if (stream.findAny().isEmpty()) {
          Files.deleteIfExists(dir);
        }
      } catch (IOException e) {
        I18nLog.warn(LOGGER, e, "buildpack.log.delete_failed", dir);
      }
    }
  }

  /**
   * Rewrites registry records that still point at loose files ({@code category/name.ext}) to the
   * zip-internal paths their files were migrated to, so pack uninstall/update keeps working.
   */
  private static void rewriteLegacyRegistry(InstallRegistry registry, Map<String, String> renames) {
    registry.reload();
    boolean changed = false;
    for (InstallRegistry.Entry entry : registry.entries()) {
      if (!entry.zip().isBlank()) {
        continue;
      }
      List<String> newFiles = new ArrayList<>();
      for (String file : entry.files()) {
        String normalized = file.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        if (slash <= 0) {
          continue;
        }
        String categoryName = normalized.substring(0, slash);
        String fileName = normalized.substring(slash + 1);
        String finalBase = renames.get(
            categoryName + "/" + FileNames.baseName(fileName).toLowerCase(Locale.ROOT));
        BuildingCategory category = BuildingCategory.byDirName(categoryName).orElse(null);
        if (finalBase == null || category == null) {
          continue;
        }
        newFiles.add(SimukraftZips.entryPath(category,
            finalBase + FileNames.extension(fileName)));
      }
      if (!newFiles.isEmpty()) {
        registry.add(new InstallRegistry.Entry(entry.id(), entry.name(), entry.version(),
            entry.installedAt(), SimukraftZips.LOCAL_ZIP_NAME, newFiles));
        changed = true;
      }
    }
    if (changed) {
      registry.save();
    }
  }
}
