package com.github.simcityexpansion.buildpack.ui;

import java.util.Comparator;
import java.util.Locale;

import com.github.simcityexpansion.buildpack.model.ImportFile;
import net.minecraft.network.chat.Component;

/** 导入文件列表的排序方式。 */
public enum SortMode {
  /** 按文件名升序。 */
  NAME("name"),
  /** 按修改时间降序（最新在前）。 */
  MODIFIED("modified"),
  /** 按文件大小降序（最大在前）。 */
  SIZE("size");

  private final String key;

  SortMode(String key) {
    this.key = key;
  }

  /** 本地化显示名。 */
  public Component displayName() {
    return Component.translatable("buildpack.sort." + key);
  }

  /** 对应的导入文件比较器。 */
  public Comparator<ImportFile> comparator() {
    return switch (this) {
      case NAME -> Comparator.comparing(file -> file.fileName().toLowerCase(Locale.ROOT));
      case MODIFIED -> Comparator.comparing(ImportFile::modifiedAt).reversed();
      case SIZE -> Comparator.comparingLong(ImportFile::sizeBytes).reversed();
    };
  }
}
