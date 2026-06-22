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
 * Local index of import files ({@code simcity_expansion/.import_index.json}): caches
 * parsed summaries (name/author/size/block count, for search and sort, avoiding repeated parsing),
 * content hashes (for duplicate detection), and user metadata (tags / notes / favorites).
 * Cache entries are invalidated by file mtime+size changes; user metadata is always preserved.
 *
 * <p>Thread safety: index enrichment ({@link #ensureEnriched}) may be called from a background
 * thread; all reads and writes are synchronized.
 */
public final class ImportIndex {
  private ImportIndex() {}

  private static final Logger LOGGER = LoggerFactory.getLogger(ImportIndex.class);
  private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
  private static final Type MAP_TYPE = new TypeToken<Map<String, Meta>>() {}.getType();
  private static final Meta EMPTY = new Meta();

  /** A single (mutable) index record. */
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

  /** Persists the index to disk. */
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

  /** Ensures the summary and content hash are cached (parsing as needed); call from a background index-enrichment pass before search, sort, or duplicate detection. */
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

  // ---- Read-only accessors for search/sort (no re-stat; caller must have called ensureEnriched) ----

  /** Embedded name, or empty string if absent. */
  public static String name(Path file) {
    return cached(file).name;
  }

  /** Author, or empty string if absent. */
  public static String author(Path file) {
    return cached(file).author;
  }

  /** Non-air block count, or 0 if not yet enriched. */
  public static long blocks(Path file) {
    return cached(file).totalBlocks;
  }

  /** Bounding volume, or 0 if not yet enriched. */
  public static long volume(Path file) {
    return cached(file).totalVolume;
  }

  /** Content hash, or empty string if not yet enriched. */
  public static String hash(Path file) {
    return cached(file).hash;
  }

  // ---- User metadata (saved immediately on write) ----

  /** Tag list (read-only copy). */
  public static List<String> tags(Path file) {
    return List.copyOf(cached(file).tags);
  }

  /** Sets tags and saves. */
  public static synchronized void setTags(Path file, List<String> tags) {
    meta(file).tags = new ArrayList<>(tags);
    save();
  }

  /** Returns whether this file is marked as a favorite. */
  public static boolean favorite(Path file) {
    return cached(file).favorite;
  }

  /** Toggles the favorite flag and saves. */
  public static synchronized void toggleFavorite(Path file) {
    Meta meta = meta(file);
    meta.favorite = !meta.favorite;
    save();
  }

  /** Note, or empty string if absent. */
  public static String note(Path file) {
    return cached(file).note;
  }

  /** Sets the note and saves. */
  public static synchronized void setNote(Path file, String note) {
    meta(file).note = note == null ? "" : note;
    save();
  }

  // ---- Duplicate detection ----

  /** All file paths whose content (hash) appears two or more times. */
  public static synchronized Set<Path> duplicatePaths(List<ImportFile> files) {
    Set<Path> result = new HashSet<>();
    for (List<Path> group : groupByHash(files).values()) {
      if (group.size() > 1) {
        result.addAll(group);
      }
    }
    return result;
  }

  /** The surplus duplicates in each group (all but the first), for use in "clean up duplicates". */
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

  // ---- Index maintenance on file move/delete (user metadata is preserved) ----

  /** Migrates the index entry after a file has been renamed or moved. */
  public static synchronized void move(Path from, Path to) {
    ensureLoaded();
    Meta meta = ENTRIES.remove(keyOf(from));
    if (meta != null) {
      ENTRIES.put(keyOf(to), meta);
      save();
    }
  }

  /** Removes the index entry after a file has been deleted. */
  public static synchronized void forget(Path file) {
    ensureLoaded();
    if (ENTRIES.remove(keyOf(file)) != null) {
      save();
    }
  }

  // ---- Internal ----

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
      // Treat as 0 if attributes cannot be read; enrichment will be retried next time.
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
