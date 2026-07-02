package com.github.simcityexpansion.buildpack.install;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import com.github.simcityexpansion.buildpack.BuildPack;
import com.github.simcityexpansion.buildpack.I18nLog;
import com.github.simcityexpansion.buildpack.LocalizedIOException;
import com.github.simcityexpansion.buildpack.model.BuildingCategory;
import com.github.simcityexpansion.buildpack.model.FileNames;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Read/write access to the zip building packages SimuKraft 2.0 loads from
 * {@code simukraftbuilding/*.zip} (internal layout {@code buildings/<category>/<file>} with
 * {@code .sk}/{@code .nbt}/{@code .json} files).
 *
 * <p>This mod writes only to its <b>managed</b> zips (file name prefixed {@code sce_}):
 * {@code sce_local.zip} collects individually installed buildings (loose imports, editor saves,
 * captures, single-building pack installs) and {@code sce_pack_<id>.zip} holds one installed build
 * pack each. Other zips (SimuKraft's {@code official_building.zip}, packs dropped in by hand) are
 * read for listing and conflict detection but never modified.
 *
 * <p>All mutation goes through {@link #updateZip}, which rewrites the zip beside the original and
 * atomically moves it into place, serialized by a class-wide lock (the GUI thread, background
 * install executor, and server commands may write concurrently).
 */
public final class SimukraftZips {
  private SimukraftZips() {}

  private static final Logger LOGGER = LoggerFactory.getLogger(SimukraftZips.class);

  /** File-name prefix of every zip this mod owns (and may therefore rewrite or delete). */
  public static final String MANAGED_PREFIX = "sce_";

  /** Managed zip collecting individually installed buildings. */
  public static final String LOCAL_ZIP_NAME = "sce_local.zip";

  /** SimuKraft's own built-in package (never modified). */
  public static final String OFFICIAL_ZIP_NAME = "official_building.zip";

  private static final Object LOCK = new Object();

  /** The managed zip for individually installed buildings ({@code simukraftbuilding/sce_local.zip}). */
  public static Path localZip() {
    return BuildPack.simukraftDir().resolve(LOCAL_ZIP_NAME);
  }

  /** The managed zip a build pack installs into ({@code simukraftbuilding/sce_pack_<id>.zip}). */
  public static Path packZip(String packId) {
    return BuildPack.simukraftDir().resolve(packZipName(packId));
  }

  /** Managed zip file name for a pack id (sanitized and lowercased so ids map to stable names). */
  public static String packZipName(String packId) {
    return "sce_pack_" + FileNames.sanitize(packId, "pack").toLowerCase(Locale.ROOT) + ".zip";
  }

  /** Whether this mod owns (and may rewrite/delete) the given zip. */
  public static boolean isManaged(Path zip) {
    return zip.getFileName().toString().toLowerCase(Locale.ROOT).startsWith(MANAGED_PREFIX);
  }

  /** Whether the zip is SimuKraft's built-in official package. */
  public static boolean isOfficial(Path zip) {
    return zip.getFileName().toString().equalsIgnoreCase(OFFICIAL_ZIP_NAME);
  }

  /** Zip-internal entry path of a building file ({@code buildings/<category>/<fileName>}). */
  public static String entryPath(BuildingCategory category, String fileName) {
    return "buildings/" + category.dirName() + "/" + fileName;
  }

  /** A parsed building entry path: its category and bare file name. */
  public record BuildingEntry(BuildingCategory category, String fileName) {}

  /**
   * Parses a zip entry name against the package layout ({@code buildings/<category>/<fileName>});
   * empty for directories, unknown categories, nested paths, and non-building entries.
   */
  public static Optional<BuildingEntry> parseEntry(String entryName) {
    String[] parts = entryName.replace('\\', '/').split("/");
    if (parts.length != 3 || !"buildings".equals(parts[0]) || parts[2].isBlank()) {
      return Optional.empty();
    }
    return BuildingCategory.byDirName(parts[1])
        .map(category -> new BuildingEntry(category, parts[2]));
  }

  /** All zip packages in the SimuKraft building root, sorted by file name (case-insensitive). */
  public static List<Path> listZips() {
    Path root = BuildPack.simukraftDir();
    if (!Files.isDirectory(root)) {
      return List.of();
    }
    List<Path> zips = new ArrayList<>();
    try (var stream = Files.list(root)) {
      stream.filter(Files::isRegularFile)
          .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip"))
          .forEach(zips::add);
    } catch (IOException e) {
      I18nLog.warn(LOGGER, e, "buildpack.log.installed_scan_failed", root);
    }
    zips.sort(Comparator.comparing(path -> path.getFileName().toString().toLowerCase(Locale.ROOT)));
    return zips;
  }

  /**
   * Lists the entry names of a zip (regular entries only, {@code \} normalized to {@code /});
   * returns an empty list when the zip does not exist.
   */
  public static List<String> listEntries(Path zip) throws IOException {
    if (!Files.isRegularFile(zip)) {
      return List.of();
    }
    List<String> names = new ArrayList<>();
    try (ZipFile zipFile = new ZipFile(zip.toFile())) {
      Enumeration<? extends ZipEntry> entries = zipFile.entries();
      while (entries.hasMoreElements()) {
        ZipEntry entry = entries.nextElement();
        if (!entry.isDirectory()) {
          names.add(entry.getName().replace('\\', '/'));
        }
      }
    }
    return names;
  }

  /** Reads one entry's bytes (bounded by {@link BuildPack#MAX_PACK_ENTRY_BYTES}); empty when absent. */
  public static Optional<byte[]> readEntry(Path zip, String entryName) throws IOException {
    if (!Files.isRegularFile(zip)) {
      return Optional.empty();
    }
    try (ZipFile zipFile = new ZipFile(zip.toFile())) {
      ZipEntry entry = zipFile.getEntry(entryName);
      if (entry == null || entry.isDirectory()) {
        return Optional.empty();
      }
      return Optional.of(readBounded(zipFile, entry));
    }
  }

  /**
   * Rewrites a managed zip: existing entries are carried over unless replaced by {@code put} or
   * listed in {@code remove}; {@code put} entries are then appended. The result is written to a
   * sibling temp file and moved into place atomically. When the resulting zip would be empty the
   * file is deleted instead (SimuKraft logs empty packages on every scan).
   *
   * @return the set of entry names present in the zip after the update
   */
  public static Set<String> updateZip(Path zip, Map<String, byte[]> put,
      Collection<String> remove) throws IOException {
    synchronized (LOCK) {
      Set<String> removed = new HashSet<>(remove);
      Map<String, byte[]> replaced = new LinkedHashMap<>(put);
      Set<String> resulting = new HashSet<>();

      Path parent = zip.getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      Path tmp = zip.resolveSibling(zip.getFileName() + ".tmp");
      boolean any = false;
      try (OutputStream fileOut = Files.newOutputStream(tmp);
           ZipOutputStream out = new ZipOutputStream(fileOut)) {
        if (Files.isRegularFile(zip)) {
          try (ZipFile existing = new ZipFile(zip.toFile())) {
            Enumeration<? extends ZipEntry> entries = existing.entries();
            while (entries.hasMoreElements()) {
              ZipEntry entry = entries.nextElement();
              if (entry.isDirectory()) {
                continue;
              }
              String name = entry.getName().replace('\\', '/');
              if (removed.contains(name) || replaced.containsKey(name)) {
                continue;
              }
              out.putNextEntry(new ZipEntry(name));
              try (InputStream in = existing.getInputStream(entry)) {
                in.transferTo(out);
              }
              out.closeEntry();
              resulting.add(name);
              any = true;
            }
          }
        }
        for (Map.Entry<String, byte[]> addition : replaced.entrySet()) {
          out.putNextEntry(new ZipEntry(addition.getKey()));
          out.write(addition.getValue());
          out.closeEntry();
          resulting.add(addition.getKey());
          any = true;
        }
      } catch (IOException e) {
        Files.deleteIfExists(tmp);
        throw e;
      }

      if (!any) {
        Files.deleteIfExists(tmp);
        Files.deleteIfExists(zip);
        return Set.of();
      }
      moveReplacing(tmp, zip);
      return resulting;
    }
  }

  /**
   * Lowercase base names (per category) of every {@code .sk}/{@code .nbt} entry across all zips in
   * the building root except {@code exclude}. SimuKraft merges packages into one catalog keyed by
   * base name (last package wins), so new names must be unique across <b>all</b> packages, not just
   * the zip being written.
   */
  public static Map<BuildingCategory, Set<String>> baseNamesByCategory(Set<Path> exclude) {
    Map<BuildingCategory, Set<String>> names = new EnumMap<>(BuildingCategory.class);
    for (BuildingCategory category : BuildingCategory.values()) {
      names.put(category, new HashSet<>());
    }
    for (Path zip : listZips()) {
      if (exclude.stream().anyMatch(path -> sameFile(path, zip))) {
        continue;
      }
      try {
        for (String entry : listEntries(zip)) {
          parseEntry(entry).ifPresent(building -> {
            String lower = building.fileName().toLowerCase(Locale.ROOT);
            if (lower.endsWith(".sk") || lower.endsWith(".nbt")) {
              names.get(building.category()).add(FileNames.baseName(lower));
            }
          });
        }
      } catch (IOException e) {
        I18nLog.warn(LOGGER, e, "buildpack.log.pack_open_failed", zip);
      }
    }
    return names;
  }

  private static boolean sameFile(Path a, Path b) {
    return a.toAbsolutePath().normalize().equals(b.toAbsolutePath().normalize());
  }

  private static byte[] readBounded(ZipFile zip, ZipEntry entry) throws IOException {
    if (entry.getSize() > BuildPack.MAX_PACK_ENTRY_BYTES) {
      throw new LocalizedIOException(
          Component.translatable("buildpack.error.entry_too_large", entry.getName()));
    }
    try (InputStream stream = zip.getInputStream(entry)) {
      byte[] bytes = stream.readNBytes((int) BuildPack.MAX_PACK_ENTRY_BYTES + 1);
      if (bytes.length > BuildPack.MAX_PACK_ENTRY_BYTES) {
        throw new LocalizedIOException(
            Component.translatable("buildpack.error.entry_too_large", entry.getName()));
      }
      return bytes;
    }
  }

  /**
   * Replaces {@code target} with {@code source}. SimuKraft may briefly hold the zip open while
   * scanning, which blocks a replace on Windows, so the move is retried a few times.
   */
  private static void moveReplacing(Path source, Path target) throws IOException {
    IOException last = null;
    for (int attempt = 0; attempt < 5; attempt++) {
      try {
        try {
          Files.move(source, target,
              StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
          Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return;
      } catch (IOException e) {
        last = e;
        try {
          Thread.sleep(50L * (attempt + 1));
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          break;
        }
      }
    }
    Files.deleteIfExists(source);
    throw last != null ? last : new IOException("Failed to replace " + target);
  }
}
