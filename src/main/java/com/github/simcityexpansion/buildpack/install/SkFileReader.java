package com.github.simcityexpansion.buildpack.install;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** .sk 元数据的轻量解析（与 {@link SkFileWriter} 互逆），供直接读取 zip 内条目使用。 */
public final class SkFileReader {
  private SkFileReader() {}

  /** 解析 UTF-8 的 .sk 内容为键值对；跳过空行、{@code #} 注释与 BOM。 */
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
