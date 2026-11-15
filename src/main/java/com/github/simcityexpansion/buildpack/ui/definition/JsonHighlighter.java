package com.github.simcityexpansion.buildpack.ui.definition;

import java.util.Arrays;

import com.github.simcityexpansion.buildpack.ui.BuildPackTheme;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

/**
 * Single-pass JSON syntax scanner for editor highlighting: assigns a token kind to every character
 * (object keys, string values, numbers, keywords, punctuation), tolerant of half-typed input —
 * an unterminated string simply colors to the end of the text, everything else falls back to the
 * default kind. Kinds are colored at draw time with GitHub's dark or light syntax palette, so the
 * highlighting follows the UI theme without rescanning.
 */
public final class JsonHighlighter {
  private JsonHighlighter() {}

  /** Token kind: default text (unclassified characters). */
  public static final int DEFAULT = 0;
  /** Token kind: object key strings. */
  public static final int KEY = 1;
  /** Token kind: string values. */
  public static final int STRING = 2;
  /** Token kind: numbers. */
  public static final int NUMBER = 3;
  /** Token kind: {@code true} / {@code false} / {@code null}. */
  public static final int KEYWORD = 4;
  /** Token kind: braces, brackets, colons, commas. */
  public static final int PUNCTUATION = 5;

  /** GitHub dark syntax colors, indexed by token kind. */
  private static final int[] DARK_COLORS =
      {0xFFC9D1D9, 0xFF7EE787, 0xFFA5D6FF, 0xFF79C0FF, 0xFFFF7B72, 0xFF8B949E};
  /** GitHub light syntax colors, indexed by token kind. */
  private static final int[] LIGHT_COLORS =
      {0xFF1F2328, 0xFF116329, 0xFF0A3069, 0xFF0550AE, 0xFFCF222E, 0xFF57606A};

  /** ARGB color of a token kind in the active theme. */
  public static int color(int token) {
    return (BuildPackTheme.light() ? LIGHT_COLORS : DARK_COLORS)[token];
  }

  /** Scans the whole text and returns one token kind per character. */
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
   * Builds a styled component for the substring {@code [begin, end)} using the scanned token kinds
   * (consecutive same-kind characters become one styled run, colored for the active theme).
   */
  public static Component styled(String text, int[] colors, int begin, int end) {
    MutableComponent line = Component.empty();
    int runStart = begin;
    for (int i = begin + 1; i <= end; i++) {
      if (i == end || colors[i] != colors[runStart]) {
        line.append(Component.literal(text.substring(runStart, i)).setStyle(
            Style.EMPTY.withColor(TextColor.fromRgb(color(colors[runStart]) & 0xFFFFFF))));
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
