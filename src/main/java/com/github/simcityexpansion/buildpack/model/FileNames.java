package com.github.simcityexpansion.buildpack.model;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Shared helpers for deriving safe, conflict-free file names. Centralizes the illegal-character
 * sanitization, extension splitting, and {@code _2/_3...} de-duplication that the import, install,
 * export, and command paths previously each re-implemented (with subtle, sometimes buggy,
 * differences such as not stripping leading/trailing dots).
 */
public final class FileNames {
  private FileNames() {}

  /**
   * Replaces characters illegal in Windows/Unix file names with {@code _}, trims surrounding
   * whitespace, and strips leading/trailing dots (which can otherwise yield hidden or
   * traversal-like names); returns {@code fallback} when nothing usable remains.
   */
  public static String sanitize(String name, String fallback) {
    String cleaned = name == null ? "" : name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
    cleaned = cleaned.replaceAll("^\\.+|\\.+$", "");
    return cleaned.isBlank() ? fallback : cleaned;
  }

  /** Returns the file name without its extension (everything before the last dot). */
  public static String baseName(String fileName) {
    int dot = fileName.lastIndexOf('.');
    return dot > 0 ? fileName.substring(0, dot) : fileName;
  }

  /** Returns the file extension including the leading dot (empty when there is none). */
  public static String extension(String fileName) {
    int dot = fileName.lastIndexOf('.');
    return dot > 0 ? fileName.substring(dot) : "";
  }

  /** Resolves {@code base + ext} under {@code dir}, appending {@code _2/_3...} until the path is free. */
  public static Path unique(Path dir, String base, String ext) {
    Path target = dir.resolve(base + ext);
    int suffix = 2;
    while (Files.exists(target)) {
      target = dir.resolve(base + "_" + suffix++ + ext);
    }
    return target;
  }
}
