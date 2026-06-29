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
    Path target = FileNames.unique(
        file.getParent(), FileNames.sanitize(newBaseName, "structure"), FileNames.extension(name));
    return moveTo(file, target);
  }

  /** Moves the file to a relative subdirectory under the import root (e.g. {@code city/houses}); the directory is created automatically. Returns the new path on success. */
  public static Path moveToFolder(Path file, String relativeFolder) {
    Path dir = resolveFolder(relativeFolder);
    if (dir == null) {
      return null;
    }
    String name = file.getFileName().toString();
    return moveTo(file, FileNames.unique(dir, FileNames.baseName(name), FileNames.extension(name)));
  }

  /** Creates a subdirectory under the import root; returns the directory path on success. */
  public static Path createFolder(String relativeFolder) {
    return resolveFolder(relativeFolder);
  }

  private static Path resolveFolder(String relativeFolder) {
    Path dir = ImportScanner.ensureImportDir();
    for (String part : relativeFolder.split("[/\\\\]")) {
      String clean = FileNames.sanitize(part, "");
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

}
