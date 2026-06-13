package com.github.simcityexpansion.buildpack.install;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.github.simcityexpansion.buildpack.BuildPack;
import com.github.simcityexpansion.buildpack.I18nLog;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 已安装 zip 拓展包的本地注册表（{@code simcity_expansion/installed_packs.json}），
 * 记录每个包写出的文件清单，用于一键卸载与「已安装」页签溯源。
 */
public final class InstallRegistry {

  private static final Logger LOGGER = LoggerFactory.getLogger(InstallRegistry.class);
  private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

  /**
   * 一条安装记录。
   *
   * @param files 相对 {@code simukraftbuilding/} 的文件路径（如 {@code residential/x.nbt}）
   */
  public record Entry(String id, String name, String version, long installedAt,
      List<String> files) {}

  private final List<Entry> entries = new ArrayList<>();

  private InstallRegistry() {}

  /** 从磁盘加载注册表；文件缺失或损坏时返回空表。 */
  public static InstallRegistry load() {
    InstallRegistry registry = new InstallRegistry();
    Path file = BuildPack.registryFile();
    if (!Files.isRegularFile(file)) {
      return registry;
    }
    try {
      JsonObject root = JsonParser
          .parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
      for (JsonElement element : root.getAsJsonArray("packs")) {
        JsonObject pack = element.getAsJsonObject();
        List<String> files = new ArrayList<>();
        for (JsonElement path : pack.getAsJsonArray("files")) {
          files.add(path.getAsString());
        }
        registry.entries.add(new Entry(
            pack.get("id").getAsString(),
            pack.has("name") ? pack.get("name").getAsString() : "",
            pack.has("version") ? pack.get("version").getAsString() : "",
            pack.has("installedAt") ? pack.get("installedAt").getAsLong() : 0L,
            files));
      }
    } catch (IOException | RuntimeException e) {
      I18nLog.warn(LOGGER, e, "buildpack.log.registry_read_failed", file);
    }
    return registry;
  }

  /** 持久化到磁盘。 */
  public void save() {
    JsonArray packs = new JsonArray();
    for (Entry entry : entries) {
      JsonObject pack = new JsonObject();
      pack.addProperty("id", entry.id());
      pack.addProperty("name", entry.name());
      pack.addProperty("version", entry.version());
      pack.addProperty("installedAt", entry.installedAt());
      JsonArray files = new JsonArray();
      entry.files().forEach(files::add);
      pack.add("files", files);
      packs.add(pack);
    }
    JsonObject root = new JsonObject();
    root.add("packs", packs);

    Path file = BuildPack.registryFile();
    try {
      if (file.getParent() != null) {
        Files.createDirectories(file.getParent());
      }
      Files.writeString(file, GSON.toJson(root), StandardCharsets.UTF_8);
    } catch (IOException e) {
      I18nLog.warn(LOGGER, e, "buildpack.log.registry_write_failed", file);
    }
  }

  /** 全部安装记录（只读视图）。 */
  public List<Entry> entries() {
    return List.copyOf(entries);
  }

  /** 按包 id 查找。 */
  public Optional<Entry> find(String packId) {
    return entries.stream().filter(entry -> entry.id().equals(packId)).findFirst();
  }

  /** 新增记录（同 id 旧记录先移除）。 */
  public void add(Entry entry) {
    entries.removeIf(existing -> existing.id().equals(entry.id()));
    entries.add(entry);
  }

  /** 移除记录。 */
  public void remove(String packId) {
    entries.removeIf(entry -> entry.id().equals(packId));
  }

  /** 查询某个相对路径文件归属的包 id（路径分隔符统一为 {@code /}）。 */
  public Optional<String> packOwning(String relativeFile) {
    String normalized = relativeFile.replace('\\', '/');
    for (Entry entry : entries) {
      if (entry.files().contains(normalized)) {
        return Optional.of(entry.id());
      }
    }
    return Optional.empty();
  }

  /** 从某条记录的清单中移除一个文件；清单清空时整条记录移除。 */
  public void removeFile(String packId, String relativeFile) {
    String normalized = relativeFile.replace('\\', '/');
    find(packId).ifPresent(entry -> {
      List<String> files = new ArrayList<>(entry.files());
      files.remove(normalized);
      entries.removeIf(existing -> existing.id().equals(packId));
      if (!files.isEmpty()) {
        entries.add(new Entry(
            entry.id(), entry.name(), entry.version(), entry.installedAt(), files));
      }
    });
  }
}
