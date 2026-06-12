package com.github.simcityexpansion.buildpack.install;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.github.simcityexpansion.buildpack.BuildPack;
import com.github.simcityexpansion.buildpack.LocalizedIOException;
import com.github.simcityexpansion.buildpack.model.InstalledBuilding;
import com.github.simcityexpansion.buildpack.model.PackManifest;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * 把已安装的建筑打包为本模组的 zip 拓展包（pack.json + buildings/&lt;分类&gt;/...），
 * 供社区分发；导出的包可直接放回导入目录再次安装。
 */
public final class PackExporter {
  private PackExporter() {}

  private static final DateTimeFormatter FILE_STAMP =
      DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

  /** 导出目录：{@code <游戏目录>/simcity_expansion/export/}。 */
  public static Path exportDir() {
    return BuildPack.gameDir().resolve("simcity_expansion").resolve("export");
  }

  /**
   * 把给定建筑导出为一个 zip 拓展包，返回生成的文件路径。
   * 元数据（.sk）与结构文件均原样打包。
   */
  public static Path export(List<InstalledBuilding> buildings) throws IOException {
    if (buildings.isEmpty()) {
      throw new LocalizedIOException(Component.translatable("buildpack.error.export_empty"));
    }
    Files.createDirectories(exportDir());
    String stamp = FILE_STAMP.format(LocalDateTime.now());
    Path target = exportDir().resolve("buildpack_export_" + stamp + ".zip");

    Set<String> writtenEntries = new HashSet<>();
    try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(target))) {
      putEntry(zip, "pack.json",
          manifestJson(stamp, buildings.size()).getBytes(StandardCharsets.UTF_8));
      for (InstalledBuilding building : buildings) {
        String dir = "buildings/" + building.category().dirName() + "/";
        copyFileEntry(zip, building.skPath(), dir, writtenEntries);
        if (building.structurePath() != null) {
          copyFileEntry(zip, building.structurePath(), dir, writtenEntries);
        }
        // SimuKraft 原生职业/交易定义随包携带（格式 v2 直通）。
        copyFileEntry(zip,
            building.skPath().resolveSibling(building.baseName() + ".json"),
            dir, writtenEntries);
      }
    } catch (IOException e) {
      Files.deleteIfExists(target);
      throw e;
    }
    return target;
  }

  private static String manifestJson(String stamp, int buildingCount) {
    JsonObject json = new JsonObject();
    json.addProperty("format", PackManifest.CURRENT_FORMAT);
    json.addProperty("id", "export." + stamp);
    // 包名/描述会显示在导入方的界面上，按导出者的游戏语言生成。
    json.addProperty("name",
        Component.translatable("buildpack.export.pack_name", stamp).getString());
    json.addProperty("version", "1.0.0");
    json.addProperty("author", exporterName());
    json.addProperty("description",
        Component.translatable("buildpack.export.pack_description", buildingCount).getString());
    return json.toString();
  }

  /** 导出者署名：当前玩家名，未进入存档时留空。 */
  private static String exporterName() {
    Minecraft minecraft = Minecraft.getInstance();
    return minecraft.player != null ? minecraft.player.getGameProfile().getName() : "";
  }

  private static void copyFileEntry(
      ZipOutputStream zip, Path file, String dirPrefix, Set<String> writtenEntries)
      throws IOException {
    if (!Files.isRegularFile(file)) {
      return;
    }
    String entryName = dirPrefix + file.getFileName();
    if (!writtenEntries.add(entryName)) {
      return;
    }
    putEntry(zip, entryName, Files.readAllBytes(file));
  }

  private static void putEntry(ZipOutputStream zip, String name, byte[] bytes)
      throws IOException {
    zip.putNextEntry(new ZipEntry(name));
    zip.write(bytes);
    zip.closeEntry();
  }
}
