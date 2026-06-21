package com.github.simcityexpansion.buildpack.model;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.github.simcityexpansion.buildpack.I18nLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 导入目录内的文件组织操作：重命名、移动到子目录（自动新建）。所有操作维护 {@link ImportIndex}
 * 的索引项迁移，并对非法文件名字符与重名冲突做处理。
 */
public final class FileOps {
  private FileOps() {}

  private static final Logger LOGGER = LoggerFactory.getLogger(FileOps.class);

  /** 重命名（保留扩展名）；目标重名自动追加 _2/_3…。成功返回新路径，失败返回 {@code null}。 */
  public static Path rename(Path file, String newBaseName) {
    String name = file.getFileName().toString();
    Path target = unique(file.getParent(), sanitize(newBaseName), extension(name));
    return moveTo(file, target);
  }

  /** 移动到导入根下的相对子目录（如 {@code city/houses}）；目录自动创建。成功返回新路径。 */
  public static Path moveToFolder(Path file, String relativeFolder) {
    Path dir = resolveFolder(relativeFolder);
    if (dir == null) {
      return null;
    }
    String name = file.getFileName().toString();
    return moveTo(file, unique(dir, baseName(name), extension(name)));
  }

  /** 在导入根下新建子目录；成功返回目录路径。 */
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
