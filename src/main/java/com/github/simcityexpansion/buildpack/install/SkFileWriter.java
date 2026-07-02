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
 * Writes {@link BuildingMetadata} as a SimuKraft .sk metadata file
 * (UTF-8, one {@code key:value} per line, matching the format used by SimuKraft's built-in buildings).
 */
public final class SkFileWriter {
  private SkFileWriter() {}

  /** Writes a .sk file, overwriting any existing file. */
  public static void write(Path skPath, BuildingMetadata meta) throws IOException {
    if (skPath.getParent() != null) {
      Files.createDirectories(skPath.getParent());
    }
    Files.write(skPath, toBytes(meta));
  }

  /** Serializes metadata to UTF-8 .sk bytes (for writing into a zip package). */
  public static byte[] toBytes(BuildingMetadata meta) {
    List<String> lines = new ArrayList<>();
    lines.add("# " + BuildPack.GENERATED_MARKER);
    putLine(lines, "name", meta.name);
    putLine(lines, "size", meta.sizeString());
    putLine(lines, "amount", meta.amount);
    putLine(lines, "author", meta.author);
    // .sk is a line-oriented format; collapse multi-line descriptions to a single line.
    putLine(lines, "description", meta.description.replace('\r', ' ').replace('\n', ' '));
    putLine(lines, "tags", meta.tags);
    putLine(lines, "job_type", meta.jobType);
    return (String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8);
  }

  private static void putLine(List<String> lines, String key, String value) {
    if (value != null && !value.isBlank()) {
      lines.add(key + ":" + value.trim());
    }
  }
}
