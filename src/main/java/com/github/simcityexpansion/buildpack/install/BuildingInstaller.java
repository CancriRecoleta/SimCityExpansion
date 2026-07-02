package com.github.simcityexpansion.buildpack.install;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.github.simcityexpansion.buildpack.I18nLog;
import com.github.simcityexpansion.buildpack.LocalizedIOException;
import com.github.simcityexpansion.buildpack.convert.StructureMaterializer;
import com.github.simcityexpansion.buildpack.convert.StructureNbtWriter;
import com.github.simcityexpansion.buildpack.integration.SimukraftBridge;
import com.github.simcityexpansion.buildpack.model.BuildingCategory;
import com.github.simcityexpansion.buildpack.model.BuildingMetadata;
import com.github.simcityexpansion.buildpack.model.FileNames;
import com.github.simcityexpansion.buildpack.model.ImportFile;
import com.github.simcityexpansion.buildpack.model.InstalledBuilding;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Installation and uninstallation of individual buildings. Since SimuKraft 2.0 reads only zip
 * packages, individual installs go into this mod's managed {@code sce_local.zip}
 * ({@link SimukraftZips#localZip()}); uninstall/recategorize edit the zip the building lives in
 * (managed zips only).
 */
public final class BuildingInstaller {
  private BuildingInstaller() {}

  private static final Logger LOGGER = LoggerFactory.getLogger(BuildingInstaller.class);

  /**
   * Install result.
   *
   * @param messages informational messages and warnings (success/failure reason, DataVersion warnings, rename notices, etc.)
   */
  public record InstallResult(boolean ok, List<Component> messages) {

    static InstallResult failure(Component message) {
      return new InstallResult(false, List.of(message));
    }
  }

  /**
   * Installs an imported file into the managed local zip package. .litematic/.schem files are first
   * converted to vanilla .nbt; old-version structures are upgraded to the current DataVersion via
   * DataFixer; a .sk metadata entry is written next to the structure entry.
   *
   * @param overwrite if {@code true}, replaces the same-base-name entries in the local zip;
   *     otherwise the name is adjusted automatically on a conflict with any zip package
   */
  public static InstallResult install(ImportFile file, BuildingMetadata meta, boolean overwrite) {
    List<Component> messages = new ArrayList<>();
    try {
      BuildingCategory category = meta.category;
      byte[] sourceBytes = Files.readAllBytes(file.path());
      StructureMaterializer.Result result =
          StructureMaterializer.toVanilla(sourceBytes, file.format(), messages);
      // Refresh dimensions from the actual conversion result to guard against schematics where metadata and block data disagree.
      meta.sizeX = result.sizeX();
      meta.sizeY = result.sizeY();
      meta.sizeZ = result.sizeZ();

      String baseName = sanitizeFileName(meta.name.isBlank() ? file.baseName() : meta.name);
      String finalName;
      List<String> removals = new ArrayList<>();
      if (overwrite) {
        finalName = baseName;
        for (String extension : new String[] {".sk", ".nbt", ".json"}) {
          removals.add(SimukraftZips.entryPath(category, baseName + extension));
        }
      } else {
        finalName = resolveConflict(
            SimukraftZips.baseNamesByCategory(Set.of()).get(category), baseName);
        if (!finalName.equals(baseName)) {
          messages.add(Component.translatable("buildpack.msg.name_conflict", finalName));
        }
      }

      Map<String, byte[]> put = new LinkedHashMap<>();
      put.put(SimukraftZips.entryPath(category, finalName + ".nbt"),
          StructureNbtWriter.toBytes(result.nbt()));
      put.put(SimukraftZips.entryPath(category, finalName + ".sk"), SkFileWriter.toBytes(meta));
      SimukraftZips.updateZip(SimukraftZips.localZip(), put, removals);
      SimukraftBridge.requestCatalogReload();

      messages.add(0, Component.translatable("buildpack.msg.installed",
          category.dirName() + "/" + finalName));
      return new InstallResult(true, messages);
    } catch (IOException | RuntimeException e) {
      I18nLog.warn(LOGGER, e, "buildpack.log.install_failed", file.path());
      return InstallResult.failure(Component.translatable(
          "buildpack.msg.parse_failed", LocalizedIOException.messageOf(e)));
    }
  }

  /**
   * Uninstalls an installed building by removing its .sk, structure, and same-base .json entries
   * from the zip it lives in. Only managed zips are modified; when the building came from a
   * registered pack, the pack's file manifest is updated as well.
   */
  public static boolean uninstall(InstalledBuilding building, InstallRegistry registry) {
    if (!SimukraftZips.isManaged(building.zipPath())) {
      return false;
    }
    List<String> removals = new ArrayList<>();
    removals.add(building.skEntry());
    removals.add(SimukraftZips.entryPath(building.category(), building.baseName() + ".nbt"));
    removals.add(SimukraftZips.entryPath(building.category(), building.baseName() + ".json"));
    try {
      SimukraftZips.updateZip(building.zipPath(), Map.of(), removals);
    } catch (IOException e) {
      I18nLog.warn(LOGGER, e, "buildpack.log.delete_failed", building.zipPath());
      return false;
    }
    if (building.packId() != null) {
      registry.reload();
      removals.forEach(entry -> registry.removeFile(building.packId(), entry));
      registry.save();
    }
    SimukraftBridge.requestCatalogReload();
    return true;
  }

  /** Moves an installed building to another category (renames its zip entries) and updates the registry paths. */
  public static boolean recategorize(
      InstalledBuilding building, BuildingCategory target, InstallRegistry registry) {
    if (building.category() == target) {
      return true;
    }
    if (!SimukraftZips.isManaged(building.zipPath())) {
      return false;
    }
    try {
      String base = building.baseName();
      String finalName = resolveConflict(
          SimukraftZips.baseNamesByCategory(Set.of()).get(target), base);

      Map<String, byte[]> put = new LinkedHashMap<>();
      List<String> removals = new ArrayList<>();
      List<String[]> movedEntries = new ArrayList<>();
      for (String extension : new String[] {".sk", ".nbt", ".json"}) {
        String from = SimukraftZips.entryPath(building.category(), base + extension);
        byte[] bytes = SimukraftZips.readEntry(building.zipPath(), from).orElse(null);
        if (bytes == null) {
          continue;
        }
        String to = SimukraftZips.entryPath(target, finalName + extension);
        put.put(to, bytes);
        removals.add(from);
        movedEntries.add(new String[] {from, to});
      }
      if (put.isEmpty()) {
        return false;
      }
      SimukraftZips.updateZip(building.zipPath(), put, removals);

      if (building.packId() != null) {
        registry.reload();
        for (String[] moved : movedEntries) {
          registry.replaceFile(building.packId(), moved[0], moved[1]);
        }
        registry.save();
      }
      SimukraftBridge.requestCatalogReload();
      return true;
    } catch (IOException | RuntimeException e) {
      I18nLog.warn(LOGGER, e, "buildpack.log.move_failed", building.zipPath());
      return false;
    }
  }

  /** Replaces illegal Windows/Unix file name characters and trims leading/trailing whitespace and dots. */
  static String sanitizeFileName(String name) {
    return FileNames.sanitize(name, "building");
  }

  /**
   * Appends {@code _2/_3...} until the base name is free in {@code takenLowercase} (lowercase base
   * names across all zip packages — SimuKraft merges packages into one catalog keyed by base name,
   * so names must be unique across all of them). The chosen name is added to the set.
   */
  public static String resolveConflict(Set<String> takenLowercase, String baseName) {
    String candidate = baseName;
    int suffix = 2;
    while (takenLowercase.contains(candidate.toLowerCase(Locale.ROOT))) {
      candidate = baseName + "_" + suffix++;
    }
    takenLowercase.add(candidate.toLowerCase(Locale.ROOT));
    return candidate;
  }
}
