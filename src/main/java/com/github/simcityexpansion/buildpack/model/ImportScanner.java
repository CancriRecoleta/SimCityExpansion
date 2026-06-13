package com.github.simcityexpansion.buildpack.model;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import com.github.simcityexpansion.buildpack.BuildPack;
import com.github.simcityexpansion.buildpack.I18nLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 扫描导入目录（{@code <游戏目录>/simcity_expansion/import/}）中的结构散文件与 zip 拓展包。 */
public final class ImportScanner {
  private ImportScanner() {}

  private static final Logger LOGGER = LoggerFactory.getLogger(ImportScanner.class);

  /** 递归扫描全部 .nbt / .litematic 散文件，按相对路径排序；目录不存在时自动创建。 */
  public static List<ImportFile> scan() {
    Path root = ensureImportDir();
    List<ImportFile> files = new ArrayList<>();
    try (Stream<Path> stream = Files.walk(root)) {
      stream.filter(Files::isRegularFile).forEach(path -> {
        StructureFormat.byFileName(path.getFileName().toString()).ifPresent(format -> {
          try {
            files.add(new ImportFile(path, format, Files.size(path),
                Files.getLastModifiedTime(path).toInstant()));
          } catch (IOException e) {
            I18nLog.warn(LOGGER, e, "buildpack.log.import_attrs_failed", path);
            files.add(new ImportFile(path, format, 0L, Instant.EPOCH));
          }
        });
      });
    } catch (IOException e) {
      I18nLog.warn(LOGGER, e, "buildpack.log.import_scan_failed", root);
    }
    files.sort(Comparator.comparing(file -> root.relativize(file.path()).toString()
        .toLowerCase(Locale.ROOT)));
    return files;
  }

  /** 扫描导入目录（仅顶层及子目录）中的 zip 文件，按文件名排序。 */
  public static List<Path> scanZips() {
    Path root = ensureImportDir();
    List<Path> zips = new ArrayList<>();
    try (Stream<Path> stream = Files.walk(root)) {
      stream.filter(Files::isRegularFile)
          .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip"))
          .forEach(zips::add);
    } catch (IOException e) {
      I18nLog.warn(LOGGER, e, "buildpack.log.zip_scan_failed", root);
    }
    zips.sort(Comparator.comparing(path -> path.getFileName().toString().toLowerCase(Locale.ROOT)));
    return zips;
  }

  /** 确保导入目录存在并返回之。 */
  public static Path ensureImportDir() {
    Path root = BuildPack.importDir();
    try {
      Files.createDirectories(root);
    } catch (IOException e) {
      I18nLog.warn(LOGGER, e, "buildpack.log.import_dir_failed", root);
    }
    return root;
  }
}
