package com.github.simcityexpansion.buildpack.ui.definition;

import java.util.Arrays;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

/**
 * Single-pass JSON syntax scanner for editor highlighting: assigns a color to every character
 * (object keys, string values, numbers, keywords, punctuation), tolerant of half-typed input —
 * an unterminated string simply colors to the end of the text, everything else falls back to the
 * default color. Colors follow a common dark-editor palette and read well on the mod's panels.
 */
public final class JsonHighlighter {
  private JsonHighlighter() {}

  /** Default text (unclassified characters). */
  public static final int DEFAULT = 0xFFE6E6E6;
  /** Object key strings. */
  public static final int KEY = 0xFF7EC8F2;
  /** String values. */
  public static final int STRING = 0xFFCE9178;
  /** Numbers. */
  public static final int NUMBER = 0xFFB5CEA8;
  /** {@code true} / {@code false} / {@code null}. */
  public static final int KEYWORD = 0xFF569CD6;
  /** Braces, brackets, colons, commas. */
  public static final int PUNCTUATION = 0xFF909090;

  /** Scans the whole text and returns one color per character. */
  public static int[] scan(String text) {
    int length = text.length();
    int[] colors = new int[length];
    Arrays.fill(colors, DEFAULT);
    int i = 0;
    while (i < length) {
      char c = text.charAt(i);
      if (c == '"') {
        int end = stringEnd(text, i);
        int color = isKey(text, end) ? KEY : STRING;
        Arrays.fill(colors, i, Math.min(end + 1, length), color);
        i = end + 1;
      } else if (c == '-' || (c >= '0' && c <= '9')) {
        int start = i;
        i++;
        while (i < length && isNumberChar(text.charAt(i))) {
          i++;
        }
        Arrays.fill(colors, start, i, NUMBER);
      } else if (Character.isLetter(c)) {
        int start = i;
        i++;
        while (i < length && Character.isLetterOrDigit(text.charAt(i))) {
          i++;
        }
        String word = text.substring(start, i);
        if ("true".equals(word) || "false".equals(word) || "null".equals(word)) {
          Arrays.fill(colors, start, i, KEYWORD);
        }
      } else {
        if (c == '{' || c == '}' || c == '[' || c == ']' || c == ':' || c == ',') {
          colors[i] = PUNCTUATION;
        }
        i++;
      }
    }
    return colors;
  }

  /**
   * Builds a styled component for the substring {@code [begin, end)} using the scanned colors
   * (consecutive same-color characters become one styled run).
   */
  public static Component styled(String text, int[] colors, int begin, int end) {
    MutableComponent line = Component.empty();
    int runStart = begin;
    for (int i = begin + 1; i <= end; i++) {
      if (i == end || colors[i] != colors[runStart]) {
        line.append(Component.literal(text.substring(runStart, i)).setStyle(
            Style.EMPTY.withColor(TextColor.fromRgb(colors[runStart] & 0xFFFFFF))));
        runStart = i;
      }
    }
    return line;
  }

  /** Index of the closing quote (skipping escapes), or the last character when unterminated. */
  private static int stringEnd(String text, int openQuote) {
    for (int i = openQuote + 1; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c == '\\') {
        i++;
      } else if (c == '"') {
        return i;
      }
    }
    return text.length() - 1;
  }

  /** Whether the string closed at {@code closeQuote} is followed by a colon (an object key). */
  private static boolean isKey(String text, int closeQuote) {
    for (int i = closeQuote + 1; i < text.length(); i++) {
      char c = text.charAt(i);
      if (!Character.isWhitespace(c)) {
        return c == ':';
      }
    }
    return false;
  }

  private static boolean isNumberChar(char c) {
    return (c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-';
  }
}
