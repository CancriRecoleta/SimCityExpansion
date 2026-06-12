package com.github.simcityexpansion.buildpack.model;

import java.nio.file.Path;
import java.util.List;

/**
 * 已解析的 zip 拓展包。
 *
 * @param zipPath zip 文件路径
 * @param manifest pack.json 清单
 * @param buildings 包内全部建筑条目
 */
public record PackArchive(Path zipPath, PackManifest manifest, List<PackBuildingEntry> buildings) {

  /** zip 文件名。 */
  public String fileName() {
    return zipPath.getFileName().toString();
  }
}
