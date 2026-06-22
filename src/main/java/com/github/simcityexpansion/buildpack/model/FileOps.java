package com.github.simcityexpansion.buildpack.model;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.github.simcityexpansion.buildpack.I18nLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * File organization operations within the import directory: rename and move to a subdirectory
 * (created automatically). All operations maintain {@link ImportIndex} entry migration and handle
 * illegal file name characters and name conflicts.
 */
public final class FileOps {
  private FileOps() {}

  private static final Logger LOGGER = LoggerFactory.getLogger(FileOps.class);

  /** Renames the file (preserving the extension); appends _2/_3... automatically on name conflict. Returns the new path on success, or {@code null} on failure. */
  public static Path rename(Path file, String newBaseName) {
    String name = file.getFileName().toString();
    Path target = unique(file.getParent(), sanitize(newBaseName), extension(name));
    return moveTo(file, target);
  }

  /** Moves the file to a relative subdirectory under the import root (e.g. {@code city/houses}); the directory is created automatically. Returns the new path on success. */
  public static Path moveToFolder(Path file, String relativeFolder) {
    Path dir = resolveFolder(relativeFolder);
    if (dir == null) {
      return null;
    }
    String name = file.getFileName().toString();
    return moveTo(file, unique(dir, baseName(name), extension(name)));
  }

  /** Creates a subdirectory under the import root; returns the directory path on success. */
  public static Path createFolder(String relativeFolder) {
    return resolveFolder(relativeFolder);
  }

  private static Path resolveFolder(String relativeFolder) {
    Path dir = ImportScanner.ensureImportDir();
    for (String part : relativeFolder.split("[/\\\\]")) {
      String clean = sanitize(part);
      if (!clean.isEmpty()) {
        dir = dir.resolve(clean);
      }
    }
    try {
      Files.createDirectories(dir);
      return dir;
    } catch (IOException e) {
      I18nLog.warn(LOGGER, e, "buildpack.log.folder_failed", dir);
      return null;
    }
  }

  private static Path moveTo(Path from, Path to) {
    try {
      if (to.getParent() != null) {
        Files.createDirectories(to.getParent());
      }
      Files.move(from, to);
      ImportIndex.move(from, to);
      return to;
    } catch (IOException e) {
      I18nLog.warn(LOGGER, e, "buildpack.log.move_failed", from);
      return null;
    }
  }

  private static Path unique(Path dir, String base, String ext) {
    String safeBase = base.isBlank() ? "structure" : base;
    Path target = dir.resolve(safeBase + ext);
    int suffix = 2;
    while (Files.exists(target)) {
      target = dir.resolve(safeBase + "_" + suffix++ + ext);
    }
    return target;
  }

  private static String sanitize(String name) {
    String cleaned = name == null ? "" : name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
    return cleaned.replaceAll("^\\.+|\\.+$", "");
  }

  private static String baseName(String fileName) {
    int dot = fileName.lastIndexOf('.');
    return dot > 0 ? fileName.substring(0, dot) : fileName;
  }

  private static String extension(String fileName) {
    int dot = fileName.lastIndexOf('.');
    return dot > 0 ? fileName.substring(dot) : "";
  }
}
