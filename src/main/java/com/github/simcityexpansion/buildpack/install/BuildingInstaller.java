package com.github.simcityexpansion.buildpack.install;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.github.simcityexpansion.buildpack.I18nLog;
import com.github.simcityexpansion.buildpack.LocalizedIOException;
import com.github.simcityexpansion.buildpack.convert.LitematicConverter;
import com.github.simcityexpansion.buildpack.convert.NbtStructure;
import com.github.simcityexpansion.buildpack.convert.SchemReader;
import com.github.simcityexpansion.buildpack.convert.StructureNbtReader;
import com.github.simcityexpansion.buildpack.convert.StructureNbtWriter;
import com.github.simcityexpansion.buildpack.convert.StructureUpgrader;
import com.github.simcityexpansion.buildpack.model.BuildingCategory;
import com.github.simcityexpansion.buildpack.model.BuildingMetadata;
import com.github.simcityexpansion.buildpack.model.ImportFile;
import com.github.simcityexpansion.buildpack.model.InstalledBuilding;
import com.github.simcityexpansion.buildpack.model.StructureFormat;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Installation and uninstallation of individual loose-file buildings. */
public final class BuildingInstaller {
  private BuildingInstaller() {}

  private static final Logger LOGGER = LoggerFactory.getLogger(BuildingInstaller.class);

  /**
   * Install result.
   *
   * @param messages informational messages and warnings (success/failure reason, DataVersion warnings, rename notices, etc.)
   */
  public record InstallResult(boolean ok, List<Component> messages, @Nullable Path skPath) {

    static InstallResult failure(Component message) {
      return new InstallResult(false, List.of(message), null);
    }
  }

  /**
   * Installs an imported file into the SimuKraft building directory. .litematic/.schem files
   * are first converted to vanilla .nbt; .nbt is written directly to disk; old-version structures
   * are upgraded to the current DataVersion via DataFixer; a .sk metadata file is then written.
   *
   * @param overwrite if {@code true}, overwrites an existing file with the same name (deletes
   *     the old .sk/.nbt/.json first); otherwise the name is adjusted automatically
   */
  public static InstallResult install(ImportFile file, BuildingMetadata meta, boolean overwrite) {
    List<Component> messages = new ArrayList<>();
    try {
      BuildingCategory category = meta.category;
      Files.createDirectories(category.dir());

      String baseName = sanitizeFileName(meta.name.isBlank() ? file.baseName() : meta.name);
      String finalName;
      if (overwrite) {
        finalName = baseName;
        deleteBuildingFiles(category.dir(), baseName);
      } else {
        finalName = resolveConflict(category.dir(), baseName);
        if (!finalName.equals(baseName)) {
          messages.add(Component.translatable("buildpack.msg.name_conflict", finalName));
        }
      }

      Path nbtTarget = category.dir().resolve(finalName + ".nbt");
      switch (file.format()) {
        case LITEMATIC, SCHEM -> {
          NbtStructure structure = file.format() == StructureFormat.LITEMATIC
              ? LitematicConverter.convert(file.path())
              : SchemReader.read(file.path());
          messages.addAll(LitematicConverter.validate(structure));
          StructureUpgrader.warnMissingBlocks(structure, messages);
          // Refresh dimensions from the actual conversion result to guard against schematics where metadata and block data disagree.
          meta.sizeX = structure.sizeX;
          meta.sizeY = structure.sizeY;
          meta.sizeZ = structure.sizeZ;
          CompoundTag tag = StructureUpgrader.upgradeToCurrent(
              StructureNbtWriter.toTag(structure), structure.dataVersion, messages);
          StructureNbtWriter.writeTag(tag, nbtTarget);
        }
        case VANILLA_NBT -> {
          // Vanilla .nbt is upgraded on the raw tag, preserving fields such as entities that are not modeled here.
          CompoundTag root = NbtIo.readCompressed(file.path(), NbtAccounter.unlimitedHeap());
          StructureUpgrader.warnMissingBlocks(StructureNbtReader.read(root), messages);
          CompoundTag upgraded = StructureUpgrader.upgradeToCurrent(
              root, root.getInt("DataVersion"), messages);
          if (upgraded == root) {
            Files.copy(file.path(), nbtTarget);
          } else {
            StructureNbtWriter.writeTag(upgraded, nbtTarget);
          }
        }
      }

      Path skTarget = category.dir().resolve(finalName + ".sk");
      SkFileWriter.write(skTarget, meta);

      messages.add(0, Component.translatable("buildpack.msg.installed",
          category.dirName() + "/" + finalName));
      return new InstallResult(true, messages, skTarget);
    } catch (IOException | RuntimeException e) {
      I18nLog.warn(LOGGER, e, "buildpack.log.install_failed", file.path());
      return InstallResult.failure(Component.translatable(
          "buildpack.msg.parse_failed", LocalizedIOException.messageOf(e)));
    }
  }

  /** Deletes all building files with the same base name before an overwrite install. */
  private static void deleteBuildingFiles(Path dir, String baseName) {
    for (String extension : new String[] {".sk", ".nbt", ".litematic", ".schem", ".json"}) {
      deleteQuietly(dir.resolve(baseName + extension));
    }
  }

  /** Uninstalls an installed building by deleting its .sk, structure file, and same-base-name .json. */
  public static boolean uninstall(InstalledBuilding building) {
    boolean ok = true;
    ok &= deleteQuietly(building.skPath());
    if (building.structurePath() != null) {
      ok &= deleteQuietly(building.structurePath());
    }
    deleteQuietly(building.skPath().resolveSibling(building.baseName() + ".json"));
    return ok;
  }

  /** Moves an installed building to another category directory (including .nbt/.sk/.json) and updates the registry paths. */
  public static boolean recategorize(
      InstalledBuilding building, BuildingCategory target, InstallRegistry registry) {
    if (building.category() == target) {
      return true;
    }
    try {
      Files.createDirectories(target.dir());
      String base = building.baseName();
      Path fromDir = building.category().dir();
      Path toDir = target.dir();
      String finalName = resolveConflict(toDir, base);
      moveQuietly(fromDir.resolve(base + ".nbt"), toDir.resolve(finalName + ".nbt"));
      moveQuietly(building.skPath(), toDir.resolve(finalName + ".sk"));
      moveQuietly(fromDir.resolve(base + ".json"), toDir.resolve(finalName + ".json"));
      if (building.packId() != null) {
        String oldPrefix = building.category().dirName() + "/";
        String newPrefix = target.dirName() + "/";
        registry.replaceFile(building.packId(),
            oldPrefix + base + ".nbt", newPrefix + finalName + ".nbt");
        registry.replaceFile(building.packId(),
            oldPrefix + base + ".sk", newPrefix + finalName + ".sk");
        registry.replaceFile(building.packId(),
            oldPrefix + base + ".json", newPrefix + finalName + ".json");
        registry.save();
      }
      return true;
    } catch (IOException | RuntimeException e) {
      I18nLog.warn(LOGGER, e, "buildpack.log.move_failed", building.skPath());
      return false;
    }
  }

  private static void moveQuietly(Path from, Path to) {
    try {
      if (Files.isRegularFile(from)) {
        Files.move(from, to);
      }
    } catch (IOException e) {
      I18nLog.warn(LOGGER, e, "buildpack.log.move_failed", from);
    }
  }

  /** Replaces illegal Windows/Unix file name characters and trims leading/trailing whitespace and dots. */
  static String sanitizeFileName(String name) {
    String cleaned = name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
    cleaned = cleaned.replaceAll("^\\.+|\\.+$", "");
    return cleaned.isBlank() ? "building" : cleaned;
  }

  /** Appends _2/_3... on a name conflict (a conflict is detected when either .sk or .nbt already exists). */
  static String resolveConflict(Path dir, String baseName) {
    String candidate = baseName;
    int suffix = 2;
    while (Files.exists(dir.resolve(candidate + ".sk"))
        || Files.exists(dir.resolve(candidate + ".nbt"))) {
      candidate = baseName + "_" + suffix++;
    }
    return candidate;
  }

  private static boolean deleteQuietly(Path path) {
    try {
      Files.deleteIfExists(path);
      return true;
    } catch (IOException e) {
      I18nLog.warn(LOGGER, e, "buildpack.log.delete_failed", path);
      return false;
    }
  }
}
