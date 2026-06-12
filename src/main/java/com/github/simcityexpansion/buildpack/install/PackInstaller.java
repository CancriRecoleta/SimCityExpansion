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
import com.github.simcityexpansion.buildpack.LocalizedIOException;
import com.github.simcityexpansion.buildpack.convert.LitematicConverter;
import com.github.simcityexpansion.buildpack.convert.LitematicReader;
import com.github.simcityexpansion.buildpack.convert.NbtStructure;
import com.github.simcityexpansion.buildpack.convert.StructureNbtReader;
import com.github.simcityexpansion.buildpack.convert.StructureNbtWriter;
import com.github.simcityexpansion.buildpack.install.BuildingInstaller.InstallResult;
import com.github.simcityexpansion.buildpack.model.BuildingMetadata;
import com.github.simcityexpansion.buildpack.model.PackArchive;
import com.github.simcityexpansion.buildpack.model.PackBuildingEntry;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
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
          installed += installBuilding(zip, building, installedFiles, messages) ? 1 : 0;
        } catch (IOException | RuntimeException e) {
          LOGGER.warn("BuildPack: 拓展包建筑安装失败 {}", building.structureEntry(), e);
          messages.add(Component.translatable("buildpack.msg.parse_failed",
              Component.literal(building.name() + ": ")
                  .append(LocalizedIOException.messageOf(e))));
        }
      }
    } catch (IOException e) {
      LOGGER.warn("BuildPack: 打开拓展包失败 {}", pack.zipPath(), e);
      return InstallResult.failure(Component.translatable(
          "buildpack.msg.invalid_pack", LocalizedIOException.messageOf(e)));
    }

    if (installed == 0) {
      messages.add(0, Component.translatable(
          "buildpack.msg.invalid_pack", pack.manifest().name()));
      return new InstallResult(false, messages, null);
    }

    registry.add(new InstallRegistry.Entry(
        pack.manifest().id(), pack.manifest().name(), pack.manifest().version(),
        System.currentTimeMillis(), installedFiles));
    registry.save();
    messages.add(0, Component.translatable(
        "buildpack.msg.pack_installed", pack.manifest().name(), installed));
    return new InstallResult(true, messages, null);
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
        LOGGER.warn("BuildPack: 删除拓展包文件失败 {}", file, e);
        ok = false;
      }
    }
    registry.remove(packId);
    registry.save();
    return ok;
  }

  private static boolean installBuilding(ZipFile zip, PackBuildingEntry building,
      List<String> installedFiles, List<Component> messages) throws IOException {
    Files.createDirectories(building.category().dir());

    String baseName = BuildingInstaller.sanitizeFileName(building.name());
    String finalName = BuildingInstaller.resolveConflict(building.category().dir(), baseName);
    if (!finalName.equals(baseName)) {
      messages.add(Component.translatable("buildpack.msg.name_conflict", finalName));
    }
    Path nbtTarget = building.category().dir().resolve(finalName + ".nbt");
    Path skTarget = building.category().dir().resolve(finalName + ".sk");

    // 结构条目一次性读入内存：原版直接落盘，litematic 解析并转换。
    byte[] structureBytes = readEntry(zip, building.structureEntry());
    NbtStructure structure;
    switch (building.format()) {
      case LITEMATIC -> {
        CompoundTag root = NbtIo.readCompressed(
            new ByteArrayInputStream(structureBytes), NbtAccounter.unlimitedHeap());
        structure = LitematicReader.readAndMerge(root);
        messages.addAll(LitematicConverter.validate(structure));
        StructureNbtWriter.write(structure, nbtTarget);
      }
      case VANILLA_NBT -> {
        CompoundTag root = NbtIo.readCompressed(
            new ByteArrayInputStream(structureBytes), NbtAccounter.unlimitedHeap());
        structure = StructureNbtReader.read(root);
        Files.write(nbtTarget, structureBytes);
      }
      default -> throw new LocalizedIOException(
          Component.translatable("buildpack.error.unknown_format"));
    }

    if (building.skEntry() != null) {
      // 包内自带 .sk：原样安装（视为作者已写好的元数据）。
      Files.write(skTarget, readEntry(zip, building.skEntry()));
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

    String prefix = building.category().dirName() + "/";
    installedFiles.add(prefix + finalName + ".nbt");
    installedFiles.add(prefix + finalName + ".sk");
    return true;
  }

  private static BuildingMetadata readJsonMeta(ZipFile zip, String entryName) throws IOException {
    BuildingMetadata meta = new BuildingMetadata();
    ZipEntry entry = zip.getEntry(entryName);
    if (entry == null) {
      return meta;
    }
    try (InputStreamReader reader = new InputStreamReader(
        zip.getInputStream(entry), StandardCharsets.UTF_8)) {
      JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
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
      LOGGER.warn("BuildPack: 拓展包元数据解析失败 {}", entryName, e);
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
