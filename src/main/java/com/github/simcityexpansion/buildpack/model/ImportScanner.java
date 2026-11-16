package com.github.simcityexpansion.buildpack.model;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import com.github.simcityexpansion.buildpack.BuildPack;
import com.github.simcityexpansion.buildpack.I18nLog;
import com.github.simcityexpansion.buildpack.integration.CreateSchematics;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Scans the import directory ({@code <game-dir>/simcity_expansion/import/}) for loose structure
 * files and build packs (zipped or extracted into a directory).
 */
public final class ImportScanner {
  private ImportScanner() {}

  private static final Logger LOGGER = LoggerFactory.getLogger(ImportScanner.class);

  /** Recursively scans all loose .nbt / .litematic files, sorted by relative path; creates the directory if it does not exist. */
  public static List<ImportFile> scan() {
    return scanStructures(ensureImportDir());
  }

  /**
   * Recursively scans the Create mod's schematic directory ({@code <game dir>/schematics/},
   * including {@code uploaded/}) for structure files; empty when the Create integration is not
   * {@linkplain CreateSchematics#available() available}. Never creates the directory.
   */
  public static List<ImportFile> scanCreateSchematics() {
    Path root = CreateSchematics.schematicsDir();
    if (!CreateSchematics.available() || !Files.isDirectory(root)) {
      return List.of();
    }
    return scanStructures(root);
  }

  /**
   * Recursively collects structure files under {@code root}, sorted by relative path. Extracted
   * build-pack directories (see {@link #scanPacks()}) are skipped: their structures belong to the
   * pack, just as the contents of a zipped pack are not listed as loose files.
   */
  private static List<ImportFile> scanStructures(Path root) {
    List<ImportFile> files = new ArrayList<>();
    if (!Files.isDirectory(root)) {
      return files;
    }
    try {
      walk(root, path -> StructureFormat.byFileName(path.getFileName().toString())
          .ifPresent(format -> {
            try {
              files.add(new ImportFile(path, format, Files.size(path),
                  Files.getLastModifiedTime(path).toInstant()));
            } catch (IOException e) {
              I18nLog.warn(LOGGER, e, "buildpack.log.import_attrs_failed", path);
              files.add(new ImportFile(path, format, 0L, Instant.EPOCH));
            }
          }), null);
    } catch (IOException e) {
      I18nLog.warn(LOGGER, e, "buildpack.log.import_scan_failed", root);
    }
    files.sort(Comparator.comparing(file -> root.relativize(file.path()).toString()
        .toLowerCase(Locale.ROOT)));
    return files;
  }

  /**
   * Scans the import directory (top level and subdirectories) for build packs, sorted by file
   * name: {@code .zip} files and extracted pack directories (a directory with a {@code pack.json}
   * at its root, e.g. a copy of SimuKraft 2.3's {@code simukraftbuilding/official_building/}).
   * A pack directory's own contents are not searched further.
   */
  public static List<Path> scanPacks() {
    Path root = ensureImportDir();
    List<Path> packs = new ArrayList<>();
    try {
      walk(root, path -> {
        if (path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip")) {
          packs.add(path);
        }
      }, packs::add);
    } catch (IOException e) {
      I18nLog.warn(LOGGER, e, "buildpack.log.zip_scan_failed", root);
    }
    packs.sort(Comparator.comparing(
        path -> path.getFileName().toString().toLowerCase(Locale.ROOT)));
    return packs;
  }

  /**
   * Walks {@code root} depth-first, handing every regular file to {@code onFile}. A subdirectory
   * holding a {@code pack.json} is an extracted build pack: it goes to {@code onPackDir} (when
   * non-null) and is not descended into. Unreadable subtrees are skipped, not fatal.
   */
  private static void walk(Path root, Consumer<Path> onFile, @Nullable Consumer<Path> onPackDir)
      throws IOException {
    Files.walkFileTree(root, new SimpleFileVisitor<>() {
      @Override
      public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
        if (!dir.equals(root) && Files.isRegularFile(dir.resolve("pack.json"))) {
          if (onPackDir != null) {
            onPackDir.accept(dir);
          }
          return FileVisitResult.SKIP_SUBTREE;
        }
        return FileVisitResult.CONTINUE;
      }

      @Override
      public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
        if (attrs.isRegularFile()) {
          onFile.accept(file);
        }
        return FileVisitResult.CONTINUE;
      }

      @Override
      public FileVisitResult visitFileFailed(Path file, IOException e) {
        I18nLog.warn(LOGGER, e, "buildpack.log.import_scan_failed", file);
        return FileVisitResult.CONTINUE;
      }
    });
  }

  /** Ensures the import directory exists and returns its path. */
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
