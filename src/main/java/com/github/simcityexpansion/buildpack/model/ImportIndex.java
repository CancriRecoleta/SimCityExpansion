package com.github.simcityexpansion.buildpack.model;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.github.simcityexpansion.buildpack.BuildPack;
import com.github.simcityexpansion.buildpack.I18nLog;
import com.github.simcityexpansion.buildpack.convert.LitematicReader;
import com.github.simcityexpansion.buildpack.convert.ParsedStructure;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 导入文件的本地索引（{@code simcity_expansion/.import_index.json}）：按相对路径缓存
 * 解析摘要（名称/作者/尺寸/方块数，供搜索与排序，免去反复解析）、内容哈希（供重复检测），
 * 以及用户元数据（标签 / 备注 / 收藏）。缓存按文件 mtime+size 失效，用户元数据始终保留。
 *
 * <p>线程安全：富集（{@link #ensureEnriched}）可在后台线程调用，读写均加锁。
 */
public final class ImportIndex {
  private ImportIndex() {}

  private static final Logger LOGGER = LoggerFactory.getLogger(ImportIndex.class);
  private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
  private static final Type MAP_TYPE = new TypeToken<Map<String, Meta>>() {}.getType();
  private static final Meta EMPTY = new Meta();

  /** 一条索引记录（可变）。 */
  public static final class Meta {
    long mtime;
    long size;
    String hash = "";
    boolean infoLoaded;
    String name = "";
    String author = "";
    int sizeX;
    int sizeY;
    int sizeZ;
    long totalBlocks;
    long totalVolume;
    List<String> tags = new ArrayList<>();
    String note = "";
    boolean favorite;
  }

  private static final Map<String, Meta> ENTRIES = new HashMap<>();
  private static boolean loaded;

  private static synchronized void ensureLoaded() {
    if (loaded) {
      return;
    }
    loaded = true;
    Path file = indexFile();
    if (!Files.isRegularFile(file)) {
      return;
    }
    try {
      Map<String, Meta> data =
          GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), MAP_TYPE);
      if (data != null) {
        data.forEach((key, meta) -> {
          if (meta.tags == null) {
            meta.tags = new ArrayList<>();
          }
          if (meta.note == null) {
            meta.note = "";
          }
          if (meta.hash == null) {
            meta.hash = "";
          }
          ENTRIES.put(key, meta);
        });
      }
    } catch (IOException | RuntimeException e) {
      I18nLog.warn(LOGGER, e, "buildpack.log.index_read_failed", file);
    }
  }

  /** 持久化到磁盘。 */
  public static synchronized void save() {
    Path file = indexFile();
    try {
      if (file.getParent() != null) {
        Files.createDirectories(file.getParent());
      }
      Files.writeString(file, GSON.toJson(ENTRIES), StandardCharsets.UTF_8);
    } catch (IOException e) {
      I18nLog.warn(LOGGER, e, "buildpack.log.index_write_failed", file);
    }
  }

  /** 确保摘要与内容哈希已缓存（必要时解析）；供搜索/排序/重复检测前的后台富集调用。 */
  public static synchronized void ensureEnriched(Path file, StructureFormat format) {
    Meta meta = meta(file);
    if (meta.hash.isEmpty()) {
      meta.hash = computeHash(file);
    }
    if (!meta.infoLoaded) {
      try {
        StructureInfo info = format == StructureFormat.LITEMATIC
            ? LitematicReader.readInfo(file)
            : ParsedStructure.parse(file, format).info();
        meta.name = info.name() == null ? "" : info.name();
        meta.author = info.author() == null ? "" : info.author();
        meta.sizeX = info.sizeX();
        meta.sizeY = info.sizeY();
        meta.sizeZ = info.sizeZ();
        meta.totalBlocks = info.totalBlocks();
        meta.totalVolume = info.totalVolume();
      } catch (IOException | RuntimeException e) {
        I18nLog.warn(LOGGER, e, "buildpack.log.index_parse_failed", file);
      }
      meta.infoLoaded = true;
    }
  }

  // ---- 搜索/排序只读访问（不重新 stat；调用前应已 ensureEnriched）----

  /** 内嵌名称（无则空串）。 */
  public static String name(Path file) {
    return cached(file).name;
  }

  /** 作者（无则空串）。 */
  public static String author(Path file) {
    return cached(file).author;
  }

  /** 非空气方块数（未富集为 0）。 */
  public static long blocks(Path file) {
    return cached(file).totalBlocks;
  }

  /** 包围体积（未富集为 0）。 */
  public static long volume(Path file) {
    return cached(file).totalVolume;
  }

  /** 内容哈希（未富集为空串）。 */
  public static String hash(Path file) {
    return cached(file).hash;
  }

  // ---- 用户元数据（写入即保存）----

  /** 标签列表（只读副本）。 */
  public static List<String> tags(Path file) {
    return List.copyOf(cached(file).tags);
  }

  /** 设置标签并保存。 */
  public static synchronized void setTags(Path file, List<String> tags) {
    meta(file).tags = new ArrayList<>(tags);
    save();
  }

  /** 是否收藏。 */
  public static boolean favorite(Path file) {
    return cached(file).favorite;
  }

  /** 切换收藏并保存。 */
  public static synchronized void toggleFavorite(Path file) {
    Meta meta = meta(file);
    meta.favorite = !meta.favorite;
    save();
  }

  /** 备注（无则空串）。 */
  public static String note(Path file) {
    return cached(file).note;
  }

  /** 设置备注并保存。 */
  public static synchronized void setNote(Path file, String note) {
    meta(file).note = note == null ? "" : note;
    save();
  }

  // ---- 重复检测 ----

  /** 内容相同（哈希一致）且出现 ≥2 次的全部文件路径。 */
  public static synchronized Set<Path> duplicatePaths(List<ImportFile> files) {
    Set<Path> result = new HashSet<>();
    for (List<Path> group : groupByHash(files).values()) {
      if (group.size() > 1) {
        result.addAll(group);
      }
    }
    return result;
  }

  /** 每组重复中除第一个外的多余文件（用于「清理重复」）。 */
  public static synchronized List<Path> redundantDuplicates(List<ImportFile> files) {
    List<Path> redundant = new ArrayList<>();
    for (List<Path> group : groupByHash(files).values()) {
      for (int i = 1; i < group.size(); i++) {
        redundant.add(group.get(i));
      }
    }
    return redundant;
  }

  private static Map<String, List<Path>> groupByHash(List<ImportFile> files) {
    Map<String, List<Path>> byHash = new LinkedHashMap<>();
    for (ImportFile file : files) {
      String hash = cached(file.path()).hash;
      if (!hash.isEmpty()) {
        byHash.computeIfAbsent(hash, key -> new ArrayList<>()).add(file.path());
      }
    }
    return byHash;
  }

  // ---- 文件移动/删除时维护索引（保留用户元数据）----

  /** 文件被重命名/移动后迁移其索引项。 */
  public static synchronized void move(Path from, Path to) {
    ensureLoaded();
    Meta meta = ENTRIES.remove(keyOf(from));
    if (meta != null) {
      ENTRIES.put(keyOf(to), meta);
      save();
    }
  }

  /** 文件被删除后移除其索引项。 */
  public static synchronized void forget(Path file) {
    ensureLoaded();
    if (ENTRIES.remove(keyOf(file)) != null) {
      save();
    }
  }

  // ---- 内部 ----

  private static synchronized Meta cached(Path file) {
    ensureLoaded();
    return ENTRIES.getOrDefault(keyOf(file), EMPTY);
  }

  private static synchronized Meta meta(Path file) {
    ensureLoaded();
    String key = keyOf(file);
    long mtime = 0L;
    long size = 0L;
    try {
      mtime = Files.getLastModifiedTime(file).toMillis();
      size = Files.size(file);
    } catch (IOException ignored) {
      // 取不到属性时按 0 处理；下次仍会尝试富集。
    }
    Meta meta = ENTRIES.computeIfAbsent(key, k -> new Meta());
    if (meta.mtime != mtime || meta.size != size) {
      meta.mtime = mtime;
      meta.size = size;
      meta.hash = "";
      meta.infoLoaded = false;
    }
    return meta;
  }

  private static String keyOf(Path file) {
    Path root = BuildPack.importDir();
    try {
      return root.relativize(file).toString().replace('\\', '/');
    } catch (IllegalArgumentException e) {
      return file.toString().replace('\\', '/');
    }
  }

  private static Path indexFile() {
    return BuildPack.gameDir().resolve("simcity_expansion").resolve(".import_index.json");
  }

  private static String computeHash(Path file) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] bytes = digest.digest(Files.readAllBytes(file));
      StringBuilder hex = new StringBuilder(bytes.length * 2);
      for (byte b : bytes) {
        hex.append(Character.forDigit((b >> 4) & 0xF, 16));
        hex.append(Character.forDigit(b & 0xF, 16));
      }
      return hex.toString();
    } catch (IOException | NoSuchAlgorithmException e) {
      I18nLog.warn(LOGGER, e, "buildpack.log.index_hash_failed", file);
      return "";
    }
  }
}
