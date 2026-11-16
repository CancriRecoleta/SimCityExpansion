package com.github.simcityexpansion.buildpack.install;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import com.github.simcityexpansion.buildpack.BuildPack;
import com.github.simcityexpansion.buildpack.LocalizedIOException;
import net.minecraft.network.chat.Component;

/**
 * Read-only view of a building package or build pack that is either a <b>zip archive</b> or an
 * <b>extracted directory</b> with the same internal layout ({@code pack.json},
 * {@code buildings/<category>/<file>}, ...). SimuKraft 2.3+ ships its official package as the
 * directory {@code simukraftbuilding/official_building/} and reads directory packages natively, so
 * every reader in this mod goes through this class instead of opening a {@link ZipFile} directly.
 *
 * <p>Entry names are always {@code /}-separated paths relative to the package root (zip entries
 * written with {@code \} separators are normalized). Only regular files are entries. Directory
 * lookups are confined to the package root: names containing {@code ..}, absolute names, and names
 * resolving outside the root are treated as absent.
 */
public abstract sealed class PackageFiles implements Closeable {

  /** Opens {@code path} as a directory package when it is a directory, otherwise as a zip. */
  public static PackageFiles open(Path path) throws IOException {
    return Files.isDirectory(path) ? new Directory(path) : new Zip(path);
  }

  /** Whether {@code path} exists as a zip file or a directory that can be {@linkplain #open opened}. */
  public static boolean exists(Path path) {
    return Files.isDirectory(path) || Files.isRegularFile(path);
  }

  /** Entry names of a package; empty when the package does not exist. */
  public static List<String> listEntries(Path path) throws IOException {
    if (!exists(path)) {
      return List.of();
    }
    try (PackageFiles files = open(path)) {
      return files.entries();
    }
  }

  /** Reads one entry (bounded by {@link BuildPack#MAX_PACK_ENTRY_BYTES}); empty when absent. */
  public static Optional<byte[]> readEntry(Path path, String entryName) throws IOException {
    if (!exists(path)) {
      return Optional.empty();
    }
    try (PackageFiles files = open(path)) {
      return files.readOptional(entryName);
    }
  }

  /**
   * Cheap change-detection signature: size and modification time for a zip; file count, total size
   * and newest modification time for a directory (editing any file inside changes it).
   */
  public static String signature(Path path) throws IOException {
    if (!Files.isDirectory(path)) {
      return Files.size(path) + ":" + Files.getLastModifiedTime(path).toMillis();
    }
    long count = 0;
    long totalSize = 0;
    long newest = 0;
    try (Stream<Path> walk = Files.walk(path)) {
      for (Path file : walk.filter(Files::isRegularFile).toList()) {
        count++;
        totalSize += Files.size(file);
        newest = Math.max(newest, Files.getLastModifiedTime(file).toMillis());
      }
    }
    return "dir:" + count + ":" + totalSize + ":" + newest;
  }

  /** The package path this view was opened on. */
  public abstract Path path();

  /** Whether this package is an extracted directory (as opposed to a zip archive). */
  public abstract boolean isDirectory();

  /** All regular-file entry names, {@code /}-separated, in archive / walk order. */
  public abstract List<String> entries();

  /** Whether a regular-file entry with this name exists. */
  public abstract boolean contains(String entryName);

  /** Declared size of an entry in bytes, or {@code -1} when unknown or absent. */
  public abstract long size(String entryName) throws IOException;

  /** Opens an entry for streaming; empty when absent. */
  protected abstract Optional<InputStream> openStream(String entryName) throws IOException;

  /**
   * Reads one entry, rejecting oversized entries (zip bombs) by declared size first and then by
   * bounding the actual read; empty when the entry is absent.
   */
  public Optional<byte[]> readOptional(String entryName) throws IOException {
    return readOptional(entryName, BuildPack.MAX_PACK_ENTRY_BYTES);
  }

  /** As {@link #readOptional(String)} with a custom size limit. */
  public Optional<byte[]> readOptional(String entryName, long maxBytes) throws IOException {
    if (size(entryName) > maxBytes) {
      throw new LocalizedIOException(
          Component.translatable("buildpack.error.entry_too_large", entryName));
    }
    Optional<InputStream> opened = openStream(entryName);
    if (opened.isEmpty()) {
      return Optional.empty();
    }
    try (InputStream stream = opened.get()) {
      byte[] bytes = stream.readNBytes((int) Math.min(Integer.MAX_VALUE - 8, maxBytes + 1));
      if (bytes.length > maxBytes) {
        throw new LocalizedIOException(
            Component.translatable("buildpack.error.entry_too_large", entryName));
      }
      return Optional.of(bytes);
    }
  }

  /** Reads one entry; throws a localized "entry missing" error when absent. */
  public byte[] read(String entryName) throws IOException {
    return readOptional(entryName).orElseThrow(() -> new LocalizedIOException(
        Component.translatable("buildpack.error.zip_entry_missing", entryName)));
  }

  static String normalize(String entryName) {
    return entryName.replace('\\', '/');
  }

  /** A zip archive. */
  private static final class Zip extends PackageFiles {
    private final Path path;
    private final ZipFile zip;
    /** Normalized entry name -> entry (regular files only). */
    private final Map<String, ZipEntry> byName = new LinkedHashMap<>();

    Zip(Path path) throws IOException {
      this.path = path;
      this.zip = new ZipFile(path.toFile());
      Enumeration<? extends ZipEntry> all = zip.entries();
      while (all.hasMoreElements()) {
        ZipEntry entry = all.nextElement();
        if (!entry.isDirectory()) {
          byName.putIfAbsent(normalize(entry.getName()), entry);
        }
      }
    }

    @Override
    public Path path() {
      return path;
    }

    @Override
    public boolean isDirectory() {
      return false;
    }

    @Override
    public List<String> entries() {
      return List.copyOf(byName.keySet());
    }

    @Override
    public boolean contains(String entryName) {
      return byName.containsKey(normalize(entryName));
    }

    @Override
    public long size(String entryName) {
      ZipEntry entry = byName.get(normalize(entryName));
      return entry == null ? -1 : entry.getSize();
    }

    @Override
    protected Optional<InputStream> openStream(String entryName) throws IOException {
      ZipEntry entry = byName.get(normalize(entryName));
      return entry == null ? Optional.empty() : Optional.of(zip.getInputStream(entry));
    }

    @Override
    public void close() throws IOException {
      zip.close();
    }
  }

  /** An extracted package directory. */
  private static final class Directory extends PackageFiles {
    private final Path path;
    private final Path root;
    private List<String> entries;

    Directory(Path path) {
      this.path = path;
      this.root = path.toAbsolutePath().normalize();
    }

    @Override
    public Path path() {
      return path;
    }

    @Override
    public boolean isDirectory() {
      return true;
    }

    @Override
    public List<String> entries() {
      if (entries == null) {
        List<String> names = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
          walk.filter(Files::isRegularFile)
              .forEach(file -> names.add(normalize(root.relativize(file).toString())));
        } catch (IOException | RuntimeException e) {
          // An unreadable subtree yields what was listed so far; callers treat missing files as absent.
        }
        Collections.sort(names);
        entries = List.copyOf(names);
      }
      return entries;
    }

    @Override
    public boolean contains(String entryName) {
      Path file = resolve(entryName);
      return file != null && Files.isRegularFile(file);
    }

    @Override
    public long size(String entryName) throws IOException {
      Path file = resolve(entryName);
      return file != null && Files.isRegularFile(file) ? Files.size(file) : -1;
    }

    @Override
    protected Optional<InputStream> openStream(String entryName) throws IOException {
      Path file = resolve(entryName);
      return file != null && Files.isRegularFile(file)
          ? Optional.of(Files.newInputStream(file)) : Optional.empty();
    }

    /** Resolves an entry name inside the package root; null when it would escape the root. */
    private Path resolve(String entryName) {
      String name = normalize(entryName);
      if (name.isBlank() || name.startsWith("/") || name.contains("..") || name.contains(":")) {
        return null;
      }
      Path file = root.resolve(name).normalize();
      return file.startsWith(root) && !file.equals(root) ? file : null;
    }

    @Override
    public void close() {}
  }
}
