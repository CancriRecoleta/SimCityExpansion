package com.github.simcityexpansion.buildpack.model;

import java.util.Locale;
import java.util.Optional;

/**
 * 支持导入的结构文件格式。
 *
 * <p>SimuKraft 的建造系统只解析原版 NBT 结构格式（palette + blocks），因此
 * {@link #LITEMATIC} 在安装时必须先转换为 {@link #VANILLA_NBT} 再写入其建筑目录。
 */
public enum StructureFormat {
  /** 原版结构方块导出的 .nbt 模板。 */
  VANILLA_NBT(".nbt"),
  /** Litematica 投影 .litematic。 */
  LITEMATIC(".litematic");

  private final String extension;

  StructureFormat(String extension) {
    this.extension = extension;
  }

  /** 文件扩展名（含点，小写）。 */
  public String extension() {
    return extension;
  }

  /** 按文件名扩展名识别格式。 */
  public static Optional<StructureFormat> byFileName(String fileName) {
    String lower = fileName.toLowerCase(Locale.ROOT);
    for (StructureFormat format : values()) {
      if (lower.endsWith(format.extension)) {
        return Optional.of(format);
      }
    }
    return Optional.empty();
  }
}
