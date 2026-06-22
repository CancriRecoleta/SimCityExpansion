package com.github.simcityexpansion.buildpack.model;

import java.nio.file.Path;
import java.time.Instant;

/**
 * A loose structure file discovered in the import directory.
 *
 * @param path absolute path to the file
 * @param format structure format (identified by extension)
 * @param sizeBytes file size in bytes
 * @param modifiedAt last modification time
 */
public record ImportFile(Path path, StructureFormat format, long sizeBytes, Instant modifiedAt) {

  /** File name including extension. */
  public String fileName() {
    return path.getFileName().toString();
  }

  /** Base name with the extension stripped, used as the default building name. */
  public String baseName() {
    String name = fileName();
    int dot = name.lastIndexOf('.');
    return dot > 0 ? name.substring(0, dot) : name;
  }
}
