package com.github.simcityexpansion.buildpack.model;

import java.nio.file.Path;
import java.util.List;

/**
 * A parsed zip build pack.
 *
 * @param zipPath path to the zip file
 * @param manifest pack.json manifest
 * @param buildings all building entries contained in the pack
 */
public record PackArchive(Path zipPath, PackManifest manifest, List<PackBuildingEntry> buildings) {

  /** Returns the zip file name. */
  public String fileName() {
    return zipPath.getFileName().toString();
  }
}
