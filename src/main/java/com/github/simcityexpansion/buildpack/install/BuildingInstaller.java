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

/** 单个散文件建筑的安装与卸载。 */
public final class BuildingInstaller {
  private BuildingInstaller() {}

  private static final Logger LOGGER = LoggerFactory.getLogger(BuildingInstaller.class);

  /**
   * 安装结果。
   *
   * @param messages 提示与警告（成功/失败原因、DataVersion 警告、改名提示等）
   */
  public record InstallResult(boolean ok, List<Component> messages, @Nullable Path skPath) {

    static InstallResult failure(Component message) {
      return new InstallResult(false, List.of(message), null);
    }
  }

  /**
   * 把导入文件安装到 SimuKraft 建筑目录：
   * .litematic / .schem 先转换为原版 .nbt，.nbt 直接落盘；旧版本结构经 DataFixer
   * 升级到当前 DataVersion；随后写出 .sk 元数据。
   *
   * @param overwrite 为 true 时同名直接覆盖（先清掉旧的 .sk/.nbt/.json），否则自动改名
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
          // 以实际转换结果为准刷新尺寸（防止元数据与方块数据不一致的投影）。
          meta.sizeX = structure.sizeX;
          meta.sizeY = structure.sizeY;
          meta.sizeZ = structure.sizeZ;
          CompoundTag tag = StructureUpgrader.upgradeToCurrent(
              StructureNbtWriter.toTag(structure), structure.dataVersion, messages);
          StructureNbtWriter.writeTag(tag, nbtTarget);
        }
        case VANILLA_NBT -> {
          // 原版 .nbt 在原始标签上升级，保留 entities 等本模型未建模的字段。
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

  /** 覆盖安装前清掉同基础名的全部建筑文件。 */
  private static void deleteBuildingFiles(Path dir, String baseName) {
    for (String extension : new String[] {".sk", ".nbt", ".litematic", ".schem", ".json"}) {
      deleteQuietly(dir.resolve(baseName + extension));
    }
  }

  /** 卸载一个已安装建筑：删除 .sk、结构文件与同基础名 .json。 */
  public static boolean uninstall(InstalledBuilding building) {
    boolean ok = true;
    ok &= deleteQuietly(building.skPath());
    if (building.structurePath() != null) {
      ok &= deleteQuietly(building.structurePath());
    }
    deleteQuietly(building.skPath().resolveSibling(building.baseName() + ".json"));
    return ok;
  }

  /** 把已安装建筑移动到另一个分类目录（含 .nbt/.sk/.json），并同步注册表路径。 */
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

  /** 替换 Windows/Unix 文件名非法字符，并裁剪首尾空白与点号。 */
  static String sanitizeFileName(String name) {
    String cleaned = name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
    cleaned = cleaned.replaceAll("^\\.+|\\.+$", "");
    return cleaned.isBlank() ? "building" : cleaned;
  }

  /** 同名冲突时追加 _2/_3…（以 .sk 或 .nbt 任一存在视为冲突）。 */
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
