package com.github.simcityexpansion.buildpack.install;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.github.simcityexpansion.buildpack.model.PoiLine;

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

  /**
   * Reads SimuKraft's optional city-level requirement ({@code unlockLevel} or {@code unlock_level},
   * matched case-insensitively like SimuKraft does); {@code 0} means the building is never locked.
   * A malformed value also yields {@code 0} — SimuKraft rejects such a building outright, and its
   * own catalog stays authoritative for anything but this display metadata.
   */
  public static int parseUnlockLevel(Map<String, String> fields) {
    for (Map.Entry<String, String> field : fields.entrySet()) {
      String key = field.getKey().toLowerCase(Locale.ROOT);
      if (!key.equals("unlocklevel") && !key.equals("unlock_level")) {
        continue;
      }
      try {
        return Math.max(0, Integer.parseInt(field.getValue().trim()));
      } catch (NumberFormatException e) {
        return 0;
      }
    }
    return 0;
  }

  /**
   * Parses every {@code poi:} line in file order. Repeated keys collapse in {@link #parseFields}'s
   * map, so POI declarations (which may repeat) are collected separately.
   */
  public static List<PoiLine> parsePoiLines(byte[] utf8Bytes) {
    List<PoiLine> lines = new ArrayList<>();
    for (String rawLine : new String(utf8Bytes, StandardCharsets.UTF_8).split("\\R")) {
      String line = stripBom(rawLine).trim();
      if (line.isEmpty() || line.startsWith("#")) {
        continue;
      }
      int colon = line.indexOf(':');
      if (colon > 0 && line.substring(0, colon).trim().toLowerCase(Locale.ROOT).equals("poi")) {
        lines.add(PoiLine.parse(line.substring(colon + 1)));
      }
    }
    return lines;
  }

  private static String stripBom(String line) {
    return !line.isEmpty() && line.charAt(0) == 0xFEFF ? line.substring(1) : line;
  }
}
