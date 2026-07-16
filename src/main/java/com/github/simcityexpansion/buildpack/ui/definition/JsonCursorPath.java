package com.github.simcityexpansion.buildpack.ui.definition;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Computes the JSON path at a text offset (e.g. {@code $.recipes[0].steps[2].type}) with a single
 * tolerant forward scan — no parse tree, so it works on half-typed documents. Strings are skipped
 * with escape handling; a string followed by a colon becomes the pending object key; braces and
 * brackets push/pop frames labeled by the key or array index they were entered under.
 */
final class JsonCursorPath {
  private JsonCursorPath() {}

  /** Context frame: an object or array, remembered with the label it was entered under. */
  private static final class Frame {
    final String label;
    final boolean array;
    int index;
    String key;

    Frame(String label, boolean array) {
      this.label = label;
      this.array = array;
    }
  }

  /** The path at {@code offset} (clamped to the text length); {@code $} when at the top level. */
  static String at(String text, int offset) {
    int upTo = Math.min(offset, text.length());
    Deque<Frame> stack = new ArrayDeque<>();
    String pendingString = null;
    int i = 0;
    while (i < upTo) {
      char c = text.charAt(i);
      switch (c) {
        case '"' -> {
          int end = stringEnd(text, i);
          if (end >= upTo) {
            // The cursor sits inside this string; its content (so far) is not yet a key.
            i = upTo;
            continue;
          }
          pendingString = text.substring(i + 1, end);
          i = end + 1;
          continue;
        }
        case ':' -> {
          Frame top = stack.peek();
          if (top != null && !top.array && pendingString != null) {
            top.key = pendingString;
          }
          pendingString = null;
        }
        case ',' -> {
          Frame top = stack.peek();
          if (top != null) {
            if (top.array) {
              top.index++;
            } else {
              top.key = null;
            }
          }
          pendingString = null;
        }
        case '{', '[' -> {
          stack.push(new Frame(enterLabel(stack.peek()), c == '['));
          pendingString = null;
        }
        case '}', ']' -> {
          if (!stack.isEmpty()) {
            stack.pop();
          }
          pendingString = null;
        }
        default -> { }
      }
      i++;
    }

    StringBuilder path = new StringBuilder("$");
    // The deque iterates top-first; rebuild bottom-up.
    Frame[] frames = stack.toArray(new Frame[0]);
    for (int f = frames.length - 1; f >= 0; f--) {
      path.append(frames[f].label);
    }
    Frame top = stack.peek();
    if (top != null) {
      path.append(top.array ? "[" + top.index + "]" : top.key != null ? "." + top.key : "");
    }
    return path.toString();
  }

  /** The label a new frame is entered under: the parent's current key or array index. */
  private static String enterLabel(Frame parent) {
    if (parent == null) {
      return "";
    }
    if (parent.array) {
      return "[" + parent.index + "]";
    }
    return parent.key != null ? "." + parent.key : "";
  }

  /** Index of the closing quote (skipping escapes), or the text length when unterminated. */
  private static int stringEnd(String text, int openQuote) {
    for (int i = openQuote + 1; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c == '\\') {
        i++;
      } else if (c == '"') {
        return i;
      }
    }
    return text.length();
  }
}
