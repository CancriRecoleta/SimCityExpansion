package com.github.simcityexpansion.buildpack.model;

import java.nio.file.Path;
import java.time.Instant;

/**
 * 导入目录中发现的一个结构散文件。
 *
 * @param path 文件绝对路径
 * @param format 结构格式（按扩展名识别）
 * @param sizeBytes 文件大小
 * @param modifiedAt 最后修改时间
 */
public record ImportFile(Path path, StructureFormat format, long sizeBytes, Instant modifiedAt) {

  /** 文件名（含扩展名）。 */
  public String fileName() {
    return path.getFileName().toString();
  }

  /** 去掉扩展名的基础名，作为建筑默认名。 */
  public String baseName() {
    String name = fileName();
    int dot = name.lastIndexOf('.');
    return dot > 0 ? name.substring(0, dot) : name;
  }
}
