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
import com.github.simcityexpansion.buildpack.convert.StructureNbtReader;
import com.github.simcityexpansion.buildpack.convert.StructureNbtWriter;
import com.github.simcityexpansion.buildpack.convert.StructureTagOps;
import com.github.simcityexpansion.buildpack.integration.SimukraftBridge;
import com.github.simcityexpansion.buildpack.model.BuildingCategory;
import com.github.simcityexpansion.buildpack.model.BuildingMetadata;
import com.github.simcityexpansion.buildpack.model.FileNames;
import com.github.simcityexpansion.buildpack.model.ImportFile;
import com.github.simcityexpansion.buildpack.model.InstalledBuilding;
import com.github.simcityexpansion.buildpack.validate.BuildingDoctor;
import net.minecraft.nbt.CompoundTag;
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
      int strippedVoid = StructureTagOps.stripStructureVoid(result.nbt());
      if (strippedVoid > 0) {
        messages.add(Component.translatable("buildpack.msg.void_stripped", strippedVoid));
      }
      // Refresh dimensions from the actual conversion result to guard against schematics where metadata and block data disagree.
      meta.sizeX = result.sizeX();
      meta.sizeY = result.sizeY();
      meta.sizeZ = result.sizeZ();
      appendDoctorSummary(result.nbt(), meta, messages);

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

  /**
   * Replaces an installed building's structure with a freshly captured one, keeping its .sk
   * metadata (with the {@code size:} line rewritten) and definition .json untouched. This closes
   * the iteration loop "build in world → tweak → recapture → reinstall" into one action.
   */
  public static InstallResult updateStructure(
      InstalledBuilding building, com.github.simcityexpansion.buildpack.convert.NbtStructure structure) {
    if (!SimukraftZips.isManaged(building.zipPath()) || building.structureEntry() == null) {
      return InstallResult.failure(Component.translatable("buildpack.msg.update_not_managed"));
    }
    List<Component> messages = new ArrayList<>();
    try {
      CompoundTag tag = StructureNbtWriter.toTag(structure);
      int strippedVoid = StructureTagOps.stripStructureVoid(tag);
      if (strippedVoid > 0) {
        messages.add(Component.translatable("buildpack.msg.void_stripped", strippedVoid));
      }

      Map<String, byte[]> put = new LinkedHashMap<>();
      put.put(building.structureEntry(), StructureNbtWriter.toBytes(tag));
      byte[] skBytes = SimukraftZips.readEntry(building.zipPath(), building.skEntry()).orElse(null);
      if (skBytes != null) {
        put.put(building.skEntry(), rewriteSkSize(skBytes,
            structure.sizeX + " x " + structure.sizeY + " x " + structure.sizeZ));
      }
      SimukraftZips.updateZip(building.zipPath(), put, List.of());
      SimukraftBridge.requestCatalogReload();

      BuildingMetadata meta = new BuildingMetadata();
      meta.name = building.name();
      meta.amount = building.skFields().getOrDefault("amount", "");
      meta.category = building.category();
      meta.poiLines.addAll(building.poiLines());
      appendDoctorSummary(tag, meta, messages);

      messages.add(0, Component.translatable("buildpack.msg.structure_updated",
          building.category().dirName() + "/" + building.baseName(),
          structure.sizeX + "×" + structure.sizeY + "×" + structure.sizeZ));
      return new InstallResult(true, messages);
    } catch (IOException | RuntimeException e) {
      I18nLog.warn(LOGGER, e, "buildpack.log.install_failed", building.zipPath());
      return InstallResult.failure(Component.translatable(
          "buildpack.msg.parse_failed", LocalizedIOException.messageOf(e)));
    }
  }

  /** Returns the .sk bytes with the {@code size} field set to {@code newSize}, preserving every other line. */
  private static byte[] rewriteSkSize(byte[] skBytes, String newSize) {
    StringBuilder out = new StringBuilder(skBytes.length + 16);
    boolean replaced = false;
    for (String line : new String(skBytes, java.nio.charset.StandardCharsets.UTF_8)
        .split("\\R", -1)) {
      String trimmed = line.trim();
      int colon = trimmed.indexOf(':');
      if (!replaced && !trimmed.startsWith("#") && colon > 0
          && trimmed.substring(0, colon).trim().equalsIgnoreCase("size")) {
        out.append("size:").append(newSize).append('\n');
        replaced = true;
      } else {
        out.append(line).append('\n');
      }
    }
    if (!replaced) {
      out.append("size:").append(newSize).append('\n');
    }
    return out.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
  }

  /** Appends a one-line pack-doctor summary when the converted structure has errors or warnings. */
  private static void appendDoctorSummary(
      CompoundTag tag, BuildingMetadata meta, List<Component> messages) {
    try {
      int[] counts = BuildingDoctor.counts(
          BuildingDoctor.examine(StructureNbtReader.read(tag), meta, null, null));
      if (counts[0] > 0 || counts[1] > 0) {
        messages.add(Component.translatable("buildpack.msg.doctor_summary", counts[0], counts[1]));
      }
    } catch (IOException | RuntimeException ignored) {
      // The doctor is advisory; a parse hiccup must not fail the install.
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
