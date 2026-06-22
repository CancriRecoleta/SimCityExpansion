package com.github.simcityexpansion.buildpack.install;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** Lightweight parser for .sk metadata (the inverse of {@link SkFileWriter}), used when reading entries directly from a zip. */
public final class SkFileReader {
  private SkFileReader() {}

  /** Parses UTF-8 .sk content into key-value pairs, skipping blank lines, {@code #} comments, and the BOM. */
  public static Map<String, String> parseFields(byte[] utf8Bytes) {
    Map<String, String> fields = new LinkedHashMap<>();
    for (String rawLine : new String(utf8Bytes, StandardCharsets.UTF_8).split("\\R")) {
      String line = stripBom(rawLine).trim();
      if (line.isEmpty() || line.startsWith("#")) {
        continue;
      }
      int colon = line.indexOf(':');
      if (colon > 0) {
        fields.put(line.substring(0, colon).trim(), line.substring(colon + 1).trim());
      }
    }
    return fields;
  }

  private static String stripBom(String line) {
    return !line.isEmpty() && line.charAt(0) == 0xFEFF ? line.substring(1) : line;
  }
}
