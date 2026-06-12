package com.github.simcityexpansion.buildpack.install;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.github.simcityexpansion.buildpack.BuildPack;
import com.github.simcityexpansion.buildpack.model.BuildingMetadata;

/**
 * 把 {@link BuildingMetadata} 写出为 SimuKraft 的 .sk 元数据文件
 * （UTF-8 逐行 {@code key:value}，与其内置建筑格式一致）。
 */
public final class SkFileWriter {
  private SkFileWriter() {}

  /** 写出 .sk（覆盖已存在文件）。 */
  public static void write(Path skPath, BuildingMetadata meta) throws IOException {
    if (skPath.getParent() != null) {
      Files.createDirectories(skPath.getParent());
    }
    List<String> lines = new ArrayList<>();
    lines.add("# " + BuildPack.GENERATED_MARKER);
    putLine(lines, "name", meta.name);
    putLine(lines, "size", meta.sizeString());
    putLine(lines, "amount", meta.amount);
    putLine(lines, "author", meta.author);
    // .sk 为行式格式，多行描述压成单行。
    putLine(lines, "description", meta.description.replace('\r', ' ').replace('\n', ' '));
    putLine(lines, "tags", meta.tags);
    putLine(lines, "job_type", meta.jobType);
    Files.write(skPath, lines, StandardCharsets.UTF_8);
  }

  private static void putLine(List<String> lines, String key, String value) {
    if (value != null && !value.isBlank()) {
      lines.add(key + ":" + value.trim());
    }
  }
}
