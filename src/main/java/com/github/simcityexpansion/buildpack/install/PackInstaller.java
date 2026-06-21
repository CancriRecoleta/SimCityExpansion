package com.github.simcityexpansion.buildpack.install;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import com.github.simcityexpansion.buildpack.BuildPack;
import com.github.simcityexpansion.buildpack.I18nLog;
import com.github.simcityexpansion.buildpack.LocalizedIOException;
import com.github.simcityexpansion.buildpack.convert.LitematicConverter;
import com.github.simcityexpansion.buildpack.convert.LitematicReader;
import com.github.simcityexpansion.buildpack.convert.NbtStructure;
import com.github.simcityexpansion.buildpack.convert.SchemReader;
import com.github.simcityexpansion.buildpack.convert.StructureNbtReader;
import com.github.simcityexpansion.buildpack.convert.StructureNbtWriter;
import com.github.simcityexpansion.buildpack.convert.StructureUpgrader;
import com.github.simcityexpansion.buildpack.install.BuildingInstaller.InstallResult;
import com.github.simcityexpansion.buildpack.model.BuildingMetadata;
import com.github.simcityexpansion.buildpack.model.PackArchive;
import com.github.simcityexpansion.buildpack.model.PackBuildingEntry;
import com.github.simcityexpansion.buildpack.model.StructureFormat;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** zip 拓展包的整包安装与卸载（安装清单记入 {@link InstallRegistry}）。 */
public final class PackInstaller {
  private PackInstaller() {}

  private static final Logger LOGGER = LoggerFactory.getLogger(PackInstaller.class);

  /** 安装整个拓展包：逐建筑转换/复制结构 + 生成或解压 .sk，并登记注册表。 */
  public static InstallResult installPack(PackArchive pack, InstallRegistry registry) {
    List<Component> messages = new ArrayList<>();
    List<String> installedFiles = new ArrayList<>();
    int installed = 0;
    try (ZipFile zip = new ZipFile(pack.zipPath().toFile())) {
      for (PackBuildingEntry building : pack.buildings()) {
        try {
          installBuilding(zip, building, installedFiles, messages);
          installed++;
        } catch (IOException | RuntimeException e) {
          I18nLog.warn(LOGGER, e, "buildpack.log.pack_building_failed", building.structureEntry());
          messages.add(Component.translatable("buildpack.msg.parse_failed",
              Component.literal(building.name() + ": ")
                  .append(LocalizedIOException.messageOf(e))));
        }
      }
    } catch (IOException e) {
      I18nLog.warn(LOGGER, e, "buildpack.log.pack_open_failed", pack.zipPath());
      return InstallResult.failure(Component.translatable(
          "buildpack.msg.invalid_pack", LocalizedIOException.messageOf(e)));
    }

    if (installed == 0) {
      messages.add(0, Component.translatable(
          "buildpack.msg.invalid_pack", pack.manifest().name()));
      return new InstallResult(false, messages, null);
    }

    InstallRegistry.Entry previous = registry.find(pack.manifest().id()).orElse(null);
    int removed = removeOrphans(previous, installedFiles);
    registry.add(new InstallRegistry.Entry(
        pack.manifest().id(), pack.manifest().name(), pack.manifest().version(),
        System.currentTimeMillis(), installedFiles));
    registry.save();
    messages.add(0, previous != null
        ? Component.translatable(
            "buildpack.msg.pack_updated", pack.manifest().name(), installed, removed)
        : Component.translatable(
            "buildpack.msg.pack_installed", pack.manifest().name(), installed));
    return new InstallResult(true, messages, null);
  }

  /** 更新拓展包时删除旧版本残留、且新版本不再包含的文件，返回删除数。 */
  private static int removeOrphans(InstallRegistry.Entry previous, List<String> newFiles) {
    if (previous == null) {
      return 0;
    }
    int removed = 0;
    for (String oldFile : previous.files()) {
      if (!newFiles.contains(oldFile)) {
        try {
          if (Files.deleteIfExists(BuildPack.simukraftDir().resolve(oldFile))) {
            removed++;
          }
        } catch (IOException e) {
          I18nLog.warn(LOGGER, e, "buildpack.log.pack_file_delete_failed", oldFile);
        }
      }
    }
    return removed;
  }

  /**
   * 单独安装包内的一个建筑（不记注册表；包内自带的 .sk 会补上本模组标记，
   * 使其在「已安装」页签可识别、可卸载）。
   */
  public static InstallResult installSingle(PackArchive pack, PackBuildingEntry building) {
    List<Component> messages = new ArrayList<>();
    try (ZipFile zip = new ZipFile(pack.zipPath().toFile())) {
      String finalName = installBuilding(zip, building, null, messages);
      messages.add(0, Component.translatable("buildpack.msg.installed",
          building.category().dirName() + "/" + finalName));
      return new InstallResult(true, messages, null);
    } catch (IOException | RuntimeException e) {
      I18nLog.warn(LOGGER, e, "buildpack.log.pack_single_failed", building.structureEntry());
      return InstallResult.failure(Component.translatable(
          "buildpack.msg.parse_failed", LocalizedIOException.messageOf(e)));
    }
  }

  /** 按注册表清单卸载整个拓展包。 */
  public static boolean uninstallPack(String packId, InstallRegistry registry) {
    InstallRegistry.Entry entry = registry.find(packId).orElse(null);
    if (entry == null) {
      return false;
    }
    boolean ok = true;
    for (String relative : entry.files()) {
      // 注册表内统一存 '/'，Path.resolve 在各平台都接受。
      Path file = BuildPack.simukraftDir().resolve(relative);
      try {
        Files.deleteIfExists(file);
      } catch (IOException e) {
        I18nLog.warn(LOGGER, e, "buildpack.log.pack_file_delete_failed", file);
        ok = false;
      }
    }
    registry.remove(packId);
    registry.save();
    return ok;
  }

  /**
   * 安装包内一个建筑，返回落盘用的最终基础名。
   *
   * @param installedFiles 整包模式传入清单收集器；传 {@code null} 表示单独安装
   *     （不记注册表，复制的 .sk 额外带上生成标记）
   */
  private static String installBuilding(ZipFile zip, PackBuildingEntry building,
      @Nullable List<String> installedFiles, List<Component> messages) throws IOException {
    Files.createDirectories(building.category().dir());

    String baseName = BuildingInstaller.sanitizeFileName(building.name());
    String finalName = BuildingInstaller.resolveConflict(building.category().dir(), baseName);
    if (!finalName.equals(baseName)) {
      messages.add(Component.translatable("buildpack.msg.name_conflict", finalName));
    }
    Path nbtTarget = building.category().dir().resolve(finalName + ".nbt");
    Path skTarget = building.category().dir().resolve(finalName + ".sk");

    // 结构条目一次性读入内存：原版直接落盘，litematic / schem 解析并转换；
    // 旧版本结构经 DataFixer 升级到当前 DataVersion。
    byte[] structureBytes = readEntry(zip, building.structureEntry());
    CompoundTag root = NbtIo.readCompressed(
        new ByteArrayInputStream(structureBytes), NbtAccounter.unlimitedHeap());
    NbtStructure structure;
    switch (building.format()) {
      case LITEMATIC, SCHEM -> {
        structure = building.format() == StructureFormat.LITEMATIC
            ? LitematicReader.readAndMerge(root)
            : SchemReader.read(root);
        messages.addAll(LitematicConverter.validate(structure));
        StructureUpgrader.warnMissingBlocks(structure, messages);
        CompoundTag tag = StructureUpgrader.upgradeToCurrent(
            StructureNbtWriter.toTag(structure), structure.dataVersion, messages);
        StructureNbtWriter.writeTag(tag, nbtTarget);
      }
      case VANILLA_NBT -> {
        structure = StructureNbtReader.read(root);
        StructureUpgrader.warnMissingBlocks(structure, messages);
        CompoundTag upgraded = StructureUpgrader.upgradeToCurrent(
            root, root.getInt("DataVersion"), messages);
        if (upgraded == root) {
          Files.write(nbtTarget, structureBytes);
        } else {
          StructureNbtWriter.writeTag(upgraded, nbtTarget);
        }
      }
      default -> throw new LocalizedIOException(
          Component.translatable("buildpack.error.unknown_format"));
    }

    // SimuKraft 原生职业/交易定义原样安装。
    if (building.simukraftJsonEntry() != null) {
      Files.write(building.category().dir().resolve(finalName + ".json"),
          readEntry(zip, building.simukraftJsonEntry()));
    }

    if (building.skEntry() != null) {
      // 包内自带 .sk：原样安装（视为作者已写好的元数据）。
      // 单独安装时补一行生成标记（SimuKraft 跳过 # 注释），便于「已安装」页签识别与卸载。
      byte[] skBytes = readEntry(zip, building.skEntry());
      if (installedFiles == null) {
        byte[] marker = ("# " + BuildPack.GENERATED_MARKER + System.lineSeparator())
            .getBytes(StandardCharsets.UTF_8);
        byte[] combined = new byte[marker.length + skBytes.length];
        System.arraycopy(marker, 0, combined, 0, marker.length);
        System.arraycopy(skBytes, 0, combined, marker.length, skBytes.length);
        skBytes = combined;
      }
      Files.write(skTarget, skBytes);
    } else {
      BuildingMetadata meta = building.metaJsonEntry() != null
          ? readJsonMeta(zip, building.metaJsonEntry())
          : new BuildingMetadata();
      if (meta.name.isBlank()) {
        meta.name = building.name();
      }
      meta.category = building.category();
      meta.sizeX = structure.sizeX;
      meta.sizeY = structure.sizeY;
      meta.sizeZ = structure.sizeZ;
      SkFileWriter.write(skTarget, meta);
    }

    if (installedFiles != null) {
      String prefix = building.category().dirName() + "/";
      installedFiles.add(prefix + finalName + ".nbt");
      installedFiles.add(prefix + finalName + ".sk");
      if (building.simukraftJsonEntry() != null) {
        installedFiles.add(prefix + finalName + ".json");
      }
    }
    return finalName;
  }

  /** 解析本模组元数据 JSON（zip 条目版本）。 */
  public static BuildingMetadata readJsonMeta(ZipFile zip, String entryName) throws IOException {
    ZipEntry entry = zip.getEntry(entryName);
    return entry == null ? new BuildingMetadata() : readJsonMeta(readEntry(zip, entryName));
  }

  /** 解析本模组元数据 JSON（内存字节版本；供界面直接读取 zip 展示共用）。 */
  public static BuildingMetadata readJsonMeta(byte[] bytes) {
    BuildingMetadata meta = new BuildingMetadata();
    try {
      JsonObject json = JsonParser
          .parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
      meta.name = getString(json, "name");
      meta.amount = getString(json, "amount");
      meta.author = getString(json, "author");
      meta.description = getString(json, "description");
      meta.jobType = getString(json, "job_type");
      if (json.has("tags") && json.get("tags").isJsonArray()) {
        JsonArray tags = json.getAsJsonArray("tags");
        List<String> values = new ArrayList<>();
        for (JsonElement tag : tags) {
          values.add(tag.getAsString());
        }
        meta.tags = String.join(",", values);
      } else {
        meta.tags = getString(json, "tags");
      }
    } catch (RuntimeException e) {
      I18nLog.warn(LOGGER, e, "buildpack.log.pack_meta_failed");
    }
    return meta;
  }

  private static String getString(JsonObject json, String key) {
    return json.has(key) && json.get(key).isJsonPrimitive() ? json.get(key).getAsString() : "";
  }

  private static byte[] readEntry(ZipFile zip, String entryName) throws IOException {
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
