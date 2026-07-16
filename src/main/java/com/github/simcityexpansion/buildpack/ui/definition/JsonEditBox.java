package com.github.simcityexpansion.buildpack.ui.definition;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import net.minecraft.Util;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractScrollWidget;
import net.minecraft.client.gui.components.MultilineTextField;
import net.minecraft.client.gui.components.Whence;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.network.chat.Component;
import net.minecraft.util.StringUtil;
import org.lwjgl.glfw.GLFW;

/**
 * Multiline JSON editor with syntax highlighting: a reimplementation of vanilla
 * {@code MultiLineEditBox} (whose text field is private) on top of the public
 * {@link MultilineTextField}, rendering each visual line as colored runs from
 * {@link JsonHighlighter}. Editing behavior — cursor, selection, keyboard/mouse handling,
 * scrolling — matches vanilla, extended with code-editor conveniences:
 *
 * <ul>
 *   <li>line-number gutter (logical lines; wrapped continuations unnumbered), current-line and
 *       matching-bracket highlights, an error-line marker, and search-match highlights;</li>
 *   <li>auto-indent on Enter (plus smart block expansion between {@code {}} / {@code []}),
 *       bracket/quote auto-close with skip-over, Tab / Shift+Tab (de)indentation;</li>
 *   <li>text undo/redo (Ctrl+Z / Ctrl+Y, typing runs coalesced) that survives widget rebuilds via
 *       {@link ViewState};</li>
 *   <li>case-insensitive search with next/previous navigation and replace support, plus cursor
 *       line/column and a JSON-path readout for the host's status bar.</li>
 * </ul>
 */
public final class JsonEditBox extends AbstractScrollWidget {

  private static final int CURSOR_COLOR = 0xFFD0D0D0;
  private static final int PLACEHOLDER_COLOR = 0xCCE0E0E0;
  private static final int LINE_HEIGHT = 9;
  private static final int CURSOR_BLINK_INTERVAL_MS = 300;
  /** Width of the line-number gutter (fits four digits). */
  private static final int GUTTER_W = 26;
  private static final int GUTTER_TEXT = 0xFF707070;
  private static final int GUTTER_TEXT_CURRENT = 0xFFC0C0C0;
  private static final int CURRENT_LINE_BG = 0x14FFFFFF;
  private static final int ERROR_LINE_BG = 0x38FF4040;
  private static final int BRACKET_BG = 0x6032A0C8;
  private static final int MATCH_BG = 0x50B08828;
  private static final int MATCH_CURRENT_BG = 0x80D89A20;
  /** Undo history depth. */
  private static final int MAX_HISTORY = 200;
  /** Consecutive small edits within this window coalesce into one undo step. */
  private static final long COALESCE_MS = 600;
  /** Cap on highlighted search matches (a degenerate query on a huge file stays cheap). */
  private static final int MAX_MATCHES = 2000;

  /** One undo/redo state. */
  public record Snapshot(String value, int cursor) {}

  private final Font font;
  private final Component placeholder;
  private final MultilineTextField textField;
  private long focusedTime = Util.getMillis();

  private String scannedText = "";
  private int[] scannedColors = new int[0];

  private Consumer<String> userListener;
  private String lastValue = "";
  private int cursorBeforeEdit;
  private boolean suppressHistory;
  private final Deque<Snapshot> undoStack = new ArrayDeque<>();
  private final Deque<Snapshot> redoStack = new ArrayDeque<>();
  private long lastPushTime;
  private boolean lastPushTyping;

  private String searchQuery = "";
  private final List<int[]> searchMatches = new ArrayList<>();
  private int currentMatchStart = -1;

  /** 1-based logical line highlighted as the syntax-error line; -1 = none (cleared on edit). */
  private int errorLine = -1;

  // Bracket-match cache (recomputed when text instance or cursor changes).
  private String bracketText;
  private int bracketCursor = -1;
  private int bracketA = -1;
  private int bracketB = -1;

  // Cursor line/column/path cache.
  private String cachedInfoText;
  private int cachedInfoCursor = -1;
  private int cachedLine = 1;
  private int cachedColumn = 1;
  private String cachedPath = "$";

  public JsonEditBox(Font font, int x, int y, int width, int height, Component placeholder) {
    super(x, y, width, height, Component.empty());
    this.font = font;
    this.placeholder = placeholder;
    this.textField = new MultilineTextField(font, width - this.totalInnerPadding() - GUTTER_W);
    this.textField.setCursorListener(this::scrollToCursor);
    this.textField.setValueListener(this::handleValueChange);
  }

  /** See {@link MultilineTextField#setCharacterLimit(int)}. */
  public void setCharacterLimit(int characterLimit) {
    textField.setCharacterLimit(characterLimit);
  }

  /** Registers the change listener (fired on every edit, including {@link #setValue}). */
  public void setValueListener(Consumer<String> valueListener) {
    this.userListener = valueListener;
  }

  /** Replaces the text as one undoable step (cursor moves to the end, as in vanilla). */
  public void setValue(String value) {
    captureBeforeEdit();
    textField.setValue(value);
  }

  /** Replaces the text without recording history (initial document load). */
  public void setValueSilently(String value) {
    suppressHistory = true;
    try {
      textField.setValue(value);
    } finally {
      suppressHistory = false;
    }
  }

  /** Returns the current text. */
  public String getValue() {
    return textField.value();
  }

  /** Editor view snapshot (cursor, scroll, and undo/redo history), kept across widget rebuilds. */
  public record ViewState(int cursor, double scroll, List<Snapshot> undo, List<Snapshot> redo) {}

  /** Captures the current view for restoring after a screen re-init. */
  public ViewState viewState() {
    return new ViewState(textField.cursor(), scrollAmount(),
        List.copyOf(undoStack), List.copyOf(redoStack));
  }

  /** Restores a previously captured {@link #viewState()}. */
  public void restoreViewState(ViewState state) {
    textField.seekCursor(Whence.ABSOLUTE, state.cursor());
    setScrollAmount(state.scroll());
    undoStack.clear();
    undoStack.addAll(state.undo());
    redoStack.clear();
    redoStack.addAll(state.redo());
    lastValue = textField.value();
  }

  // ---- History ----

  private void handleValueChange(String newValue) {
    if (!newValue.equals(lastValue)) {
      if (!suppressHistory) {
        boolean typing = Math.abs(newValue.length() - lastValue.length()) <= 2;
        long now = Util.getMillis();
        if (!(typing && lastPushTyping && now - lastPushTime < COALESCE_MS)) {
          undoStack.push(new Snapshot(lastValue, cursorBeforeEdit));
          while (undoStack.size() > MAX_HISTORY) {
            undoStack.removeLast();
          }
          lastPushTime = now;
          lastPushTyping = typing;
        }
        redoStack.clear();
      }
      lastValue = newValue;
      errorLine = -1;
      recomputeSearchMatches(newValue);
    }
    if (userListener != null) {
      userListener.accept(newValue);
    }
  }

  private void captureBeforeEdit() {
    cursorBeforeEdit = textField.cursor();
  }

  /** Undoes the last edit; returns false when there is nothing to undo. */
  public boolean undo() {
    if (undoStack.isEmpty()) {
      return false;
    }
    redoStack.push(new Snapshot(textField.value(), textField.cursor()));
    applySnapshot(undoStack.pop());
    lastPushTyping = false;
    return true;
  }

  /** Redoes the last undone edit; returns false when there is nothing to redo. */
  public boolean redo() {
    if (redoStack.isEmpty()) {
      return false;
    }
    undoStack.push(new Snapshot(textField.value(), textField.cursor()));
    applySnapshot(redoStack.pop());
    lastPushTyping = false;
    return true;
  }

  private void applySnapshot(Snapshot snapshot) {
    suppressHistory = true;
    try {
      textField.setValue(snapshot.value());
    } finally {
      suppressHistory = false;
    }
    textField.seekCursor(Whence.ABSOLUTE,
        Math.min(snapshot.cursor(), textField.value().length()));
  }

  // ---- Search ----

  /** Sets the case-insensitive search query (empty clears the highlights). */
  public void setSearchQuery(String query) {
    searchQuery = query == null ? "" : query;
    currentMatchStart = -1;
    recomputeSearchMatches(textField.value());
  }

  /** Number of matches for the current query (capped at {@value #MAX_MATCHES}). */
  public int matchCount() {
    return searchMatches.size();
  }

  private void recomputeSearchMatches(String text) {
    searchMatches.clear();
    if (searchQuery.isBlank()) {
      currentMatchStart = -1;
      return;
    }
    String haystack = text.toLowerCase(Locale.ROOT);
    String needle = searchQuery.toLowerCase(Locale.ROOT);
    int from = 0;
    while (searchMatches.size() < MAX_MATCHES) {
      int at = haystack.indexOf(needle, from);
      if (at < 0) {
        break;
      }
      searchMatches.add(new int[] {at, at + needle.length()});
      from = at + Math.max(1, needle.length());
    }
  }

  /** Selects the next/previous match relative to the cursor (wrapping); false when no matches. */
  public boolean findNext(boolean forward) {
    if (searchMatches.isEmpty()) {
      return false;
    }
    int cursor = textField.cursor();
    int[] target = null;
    if (forward) {
      for (int[] match : searchMatches) {
        if (match[0] >= cursor) {
          target = match;
          break;
        }
      }
      if (target == null) {
        target = searchMatches.get(0);
      }
    } else {
      int reference = textField.hasSelection()
          ? textField.getSelected().beginIndex() : cursor;
      for (int i = searchMatches.size() - 1; i >= 0; i--) {
        if (searchMatches.get(i)[0] < reference) {
          target = searchMatches.get(i);
          break;
        }
      }
      if (target == null) {
        target = searchMatches.get(searchMatches.size() - 1);
      }
    }
    selectRange(target[0], target[1]);
    currentMatchStart = target[0];
    return true;
  }

  /**
   * Replaces the currently selected match with {@code replacement} and jumps to the next one;
   * when the selection is not a match, only jumps (the usual two-step replace flow). Returns
   * whether a replacement happened.
   */
  public boolean replaceCurrent(String replacement) {
    if (!searchQuery.isBlank() && textField.hasSelection()) {
      MultilineTextField.StringView selection = textField.getSelected();
      String selected = textField.value().substring(selection.beginIndex(), selection.endIndex());
      if (selected.equalsIgnoreCase(searchQuery)) {
        captureBeforeEdit();
        textField.insertText(replacement);
        findNext(true);
        return true;
      }
    }
    findNext(true);
    return false;
  }

  /** Replaces every match with {@code replacement} in one undoable step; returns the count. */
  public int replaceAll(String replacement) {
    recomputeSearchMatches(textField.value());
    if (searchMatches.isEmpty()) {
      return 0;
    }
    String text = textField.value();
    StringBuilder out = new StringBuilder(text.length());
    int from = 0;
    int count = 0;
    for (int[] match : searchMatches) {
      out.append(text, from, match[0]).append(replacement);
      from = match[1];
      count++;
    }
    out.append(text, from, text.length());
    captureBeforeEdit();
    textField.setValue(out.toString());
    return count;
  }

  private void selectRange(int begin, int end) {
    textField.setSelecting(false);
    textField.seekCursor(Whence.ABSOLUTE, begin);
    textField.setSelecting(true);
    textField.seekCursor(Whence.ABSOLUTE, end);
    textField.setSelecting(false);
  }

  // ---- Error line / cursor info ----

  /** Highlights a 1-based logical line as the syntax-error line (-1 clears; any edit clears too). */
  public void setErrorLine(int oneBasedLine) {
    errorLine = oneBasedLine;
  }

  /** Moves the cursor to the start of a 1-based logical line (auto-scrolls there). */
  public void gotoLine(int oneBasedLine) {
    String text = textField.value();
    int index = 0;
    for (int line = 1; line < oneBasedLine && index <= text.length(); line++) {
      int next = text.indexOf('\n', index);
      if (next < 0) {
        break;
      }
      index = next + 1;
    }
    textField.setSelecting(false);
    textField.seekCursor(Whence.ABSOLUTE, index);
  }

  /** 1-based logical line of the cursor. */
  public int cursorLine() {
    ensureCursorInfo();
    return cachedLine;
  }

  /** 1-based column of the cursor within its logical line. */
  public int cursorColumn() {
    ensureCursorInfo();
    return cachedColumn;
  }

  /** JSON path at the cursor (e.g. {@code $.recipes[0].steps[2].type}). */
  public String jsonPathAtCursor() {
    ensureCursorInfo();
    return cachedPath;
  }

  private void ensureCursorInfo() {
    String text = textField.value();
    int cursor = textField.cursor();
    if (text == cachedInfoText && cursor == cachedInfoCursor) {
      return;
    }
    cachedInfoText = text;
    cachedInfoCursor = cursor;
    int line = 1;
    int lineStart = 0;
    for (int i = 0; i < cursor && i < text.length(); i++) {
      if (text.charAt(i) == '\n') {
        line++;
        lineStart = i + 1;
      }
    }
    cachedLine = line;
    cachedColumn = cursor - lineStart + 1;
    cachedPath = JsonCursorPath.at(text, cursor);
  }

  @Override
  public void updateWidgetNarration(NarrationElementOutput output) {
    output.add(NarratedElementType.TITLE,
        Component.translatable("gui.narrate.editBox", getMessage(), getValue()));
  }

  // ---- Input ----

  @Override
  public boolean mouseClicked(double mouseX, double mouseY, int button) {
    if (withinContentAreaPoint(mouseX, mouseY) && button == 0) {
      captureBeforeEdit();
      textField.setSelecting(Screen.hasShiftDown());
      seekCursorScreen(mouseX, mouseY);
      return true;
    }
    return super.mouseClicked(mouseX, mouseY, button);
  }

  @Override
  public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX,
      double dragY) {
    if (super.mouseDragged(mouseX, mouseY, button, dragX, dragY)) {
      return true;
    }
    if (withinContentAreaPoint(mouseX, mouseY) && button == 0) {
      textField.setSelecting(true);
      seekCursorScreen(mouseX, mouseY);
      textField.setSelecting(Screen.hasShiftDown());
      return true;
    }
    return false;
  }

  @Override
  public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
    boolean ctrl = Screen.hasControlDown();
    boolean shift = Screen.hasShiftDown();
    if (ctrl && keyCode == GLFW.GLFW_KEY_Z && !shift) {
      undo();
      return true;
    }
    if (ctrl && (keyCode == GLFW.GLFW_KEY_Y || (shift && keyCode == GLFW.GLFW_KEY_Z))) {
      redo();
      return true;
    }
    if (keyCode == GLFW.GLFW_KEY_TAB) {
      handleTab(shift);
      return true;
    }
    if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
      insertNewlineAutoIndented();
      return true;
    }
    captureBeforeEdit();
    return textField.keyPressed(keyCode);
  }

  @Override
  public boolean charTyped(char codePoint, int modifiers) {
    if (!visible || !isFocused() || !StringUtil.isAllowedChatCharacter(codePoint)) {
      return false;
    }
    captureBeforeEdit();
    if (!textField.hasSelection()) {
      String text = textField.value();
      int cursor = textField.cursor();
      char next = cursor < text.length() ? text.charAt(cursor) : 0;
      // Skip over an identical closer instead of doubling it.
      if ((codePoint == '}' || codePoint == ']' || codePoint == '"') && next == codePoint) {
        textField.seekCursor(Whence.RELATIVE, 1);
        return true;
      }
      // Auto-close braces/brackets/quotes; the cursor lands between the pair.
      if (codePoint == '{' || codePoint == '[' || codePoint == '"') {
        char close = codePoint == '{' ? '}' : codePoint == '[' ? ']' : '"';
        textField.insertText(String.valueOf(codePoint) + close);
        textField.seekCursor(Whence.RELATIVE, -1);
        return true;
      }
    }
    textField.insertText(Character.toString(codePoint));
    return true;
  }

  /** Enter with auto-indent: keep the current line's leading spaces, +2 after an opener; expand {@code {}} / {@code []} into a block. */
  private void insertNewlineAutoIndented() {
    captureBeforeEdit();
    String text = textField.value();
    int reference = textField.hasSelection()
        ? textField.getSelected().beginIndex() : textField.cursor();
    int lineStart = text.lastIndexOf('\n', Math.max(0, reference - 1)) + 1;
    int indentEnd = lineStart;
    while (indentEnd < Math.min(text.length(), reference) && text.charAt(indentEnd) == ' ') {
      indentEnd++;
    }
    String indent = text.substring(lineStart, indentEnd);
    char previous = 0;
    for (int i = reference - 1; i >= lineStart; i--) {
      if (text.charAt(i) != ' ') {
        previous = text.charAt(i);
        break;
      }
    }
    boolean opener = previous == '{' || previous == '[';
    int cursor = textField.cursor();
    char next = !textField.hasSelection() && cursor < text.length() ? text.charAt(cursor) : 0;
    boolean expandBlock = opener && (next == '}' || next == ']');
    String insert = "\n" + indent + (opener ? "  " : "");
    if (expandBlock) {
      textField.insertText(insert + "\n" + indent);
      textField.seekCursor(Whence.RELATIVE, -(indent.length() + 1));
    } else {
      textField.insertText(insert);
    }
  }

  /** Tab: insert two spaces, or (de)indent every logical line the selection touches. */
  private void handleTab(boolean dedent) {
    captureBeforeEdit();
    String text = textField.value();
    boolean selection = textField.hasSelection();
    if (!selection && !dedent) {
      textField.insertText("  ");
      return;
    }
    int begin = selection ? textField.getSelected().beginIndex() : textField.cursor();
    int end = selection ? textField.getSelected().endIndex() : textField.cursor();
    int firstLine = text.lastIndexOf('\n', Math.max(0, begin - 1)) + 1;
    StringBuilder out = new StringBuilder(text.length() + 64);
    out.append(text, 0, firstLine);
    int lineStart = firstLine;
    int newEnd = end;
    int newBegin = begin;
    while (lineStart < text.length() && lineStart <= end) {
      int lineEnd = text.indexOf('\n', lineStart);
      if (lineEnd < 0) {
        lineEnd = text.length();
      }
      String line = text.substring(lineStart, lineEnd);
      String adjusted;
      if (dedent) {
        int strip = line.startsWith("  ") ? 2 : line.startsWith(" ") ? 1 : 0;
        adjusted = line.substring(strip);
        if (lineStart <= begin) {
          newBegin = Math.max(lineStart, newBegin - strip);
        }
        newEnd -= Math.min(strip, Math.max(0, end - lineStart));
      } else {
        adjusted = line.isEmpty() ? line : "  " + line;
        if (!line.isEmpty()) {
          if (lineStart <= begin) {
            newBegin += 2;
          }
          newEnd += 2;
        }
      }
      out.append(adjusted);
      if (lineEnd < text.length()) {
        out.append('\n');
      }
      lineStart = lineEnd + 1;
    }
    if (lineStart <= text.length()) {
      out.append(text, Math.min(lineStart, text.length()), text.length());
    }
    textField.setValue(out.toString());
    int length = textField.value().length();
    if (selection) {
      selectRange(Math.min(newBegin, length), Math.min(Math.max(newBegin, newEnd), length));
    } else {
      textField.seekCursor(Whence.ABSOLUTE, Math.min(Math.max(0, newBegin), length));
    }
  }

  // ---- Rendering ----

  @Override
  protected void renderContents(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
    String text = textField.value();
    if (text.isEmpty() && !isFocused()) {
      g.drawWordWrap(font, placeholder, getX() + innerPadding() + GUTTER_W,
          getY() + innerPadding(), width - totalInnerPadding() - GUTTER_W, PLACEHOLDER_COLOR);
      return;
    }
    ensureScanned(text);
    ensureBracketMatch(text);
    int cursor = textField.cursor();
    boolean blinkOn =
        isFocused() && (Util.getMillis() - focusedTime) / CURSOR_BLINK_INTERVAL_MS % 2L == 0L;
    boolean cursorInside = cursor < text.length();
    int lineY = getY() + innerPadding();
    int lineX = getX() + innerPadding() + GUTTER_W;
    int contentRight = getX() + width - innerPadding();
    int logicalLine = 0;

    for (MultilineTextField.StringView line : textField.iterateLines()) {
      boolean logicalStart = line.beginIndex() == 0 || text.charAt(line.beginIndex() - 1) == '\n';
      if (logicalStart) {
        logicalLine++;
      }
      boolean visibleLine = withinContentAreaTopBottom(lineY, lineY + LINE_HEIGHT);
      if (visibleLine) {
        boolean cursorLine = cursor >= line.beginIndex() && cursor <= line.endIndex();
        if (logicalLine == errorLine) {
          g.fill(lineX - 2, lineY - 1, contentRight, lineY + 1 + LINE_HEIGHT, ERROR_LINE_BG);
        } else if (cursorLine && isFocused()) {
          g.fill(lineX - 2, lineY - 1, contentRight, lineY + 1 + LINE_HEIGHT, CURRENT_LINE_BG);
        }
        drawSearchHighlights(g, text, line, lineX, lineY);
        drawBracketHighlight(g, text, line, bracketA, lineX, lineY);
        drawBracketHighlight(g, text, line, bracketB, lineX, lineY);
        if (logicalStart) {
          String number = Integer.toString(logicalLine);
          g.drawString(font, number, lineX - 6 - font.width(number), lineY,
              cursorLine ? GUTTER_TEXT_CURRENT : GUTTER_TEXT, false);
        }
        g.drawString(font,
            JsonHighlighter.styled(text, scannedColors, line.beginIndex(), line.endIndex()),
            lineX, lineY, JsonHighlighter.DEFAULT);
        if (blinkOn && cursorInside && cursorLine) {
          int cursorX = lineX + font.width(text.substring(line.beginIndex(), cursor));
          g.fill(cursorX, lineY - 1, cursorX + 1, lineY + 1 + LINE_HEIGHT, CURSOR_COLOR);
        }
      }
      lineY += LINE_HEIGHT;
    }

    if (blinkOn && !cursorInside) {
      // Cursor sits at the very end of the text: draw the vanilla trailing underscore.
      int lastIndex = textField.getLineCount() - 1;
      int endY = getY() + innerPadding() + lastIndex * LINE_HEIGHT;
      if (withinContentAreaTopBottom(endY, endY + LINE_HEIGHT)) {
        MultilineTextField.StringView lastLine = textField.getLineView(lastIndex);
        int endX = lineX + font.width(text.substring(lastLine.beginIndex(), lastLine.endIndex()));
        g.drawString(font, "_", endX, endY, CURSOR_COLOR);
      }
    }

    if (textField.hasSelection()) {
      MultilineTextField.StringView selection = textField.getSelected();
      lineY = getY() + innerPadding();
      for (MultilineTextField.StringView line : textField.iterateLines()) {
        if (selection.beginIndex() > line.endIndex()) {
          lineY += LINE_HEIGHT;
          continue;
        }
        if (line.beginIndex() > selection.endIndex()) {
          break;
        }
        if (withinContentAreaTopBottom(lineY, lineY + LINE_HEIGHT)) {
          int from = font.width(text.substring(line.beginIndex(),
              Math.max(selection.beginIndex(), line.beginIndex())));
          int to = selection.endIndex() > line.endIndex()
              ? width - innerPadding() - GUTTER_W
              : font.width(text.substring(line.beginIndex(), selection.endIndex()));
          g.fill(RenderType.guiTextHighlight(), lineX + from, lineY, lineX + to,
              lineY + LINE_HEIGHT, 0xFF0000FF);
        }
        lineY += LINE_HEIGHT;
      }
    }
  }

  /** Amber boxes behind every search match intersecting the line (the current match brighter). */
  private void drawSearchHighlights(GuiGraphics g, String text,
      MultilineTextField.StringView line, int lineX, int lineY) {
    if (searchMatches.isEmpty()) {
      return;
    }
    for (int[] match : searchMatches) {
      if (match[1] <= line.beginIndex()) {
        continue;
      }
      if (match[0] > line.endIndex()) {
        break;
      }
      int begin = Math.max(match[0], line.beginIndex());
      int end = Math.min(match[1], line.endIndex());
      if (begin >= end) {
        continue;
      }
      int from = font.width(text.substring(line.beginIndex(), begin));
      int to = font.width(text.substring(line.beginIndex(), end));
      g.fill(lineX + from, lineY - 1, lineX + to, lineY + 1 + LINE_HEIGHT,
          match[0] == currentMatchStart ? MATCH_CURRENT_BG : MATCH_BG);
    }
  }

  /** Blue box behind one matched bracket character when it falls on this line. */
  private void drawBracketHighlight(GuiGraphics g, String text,
      MultilineTextField.StringView line, int position, int lineX, int lineY) {
    if (position < line.beginIndex() || position >= line.endIndex()) {
      return;
    }
    int from = font.width(text.substring(line.beginIndex(), position));
    int to = from + font.width(String.valueOf(text.charAt(position)));
    g.fill(lineX + from, lineY - 1, lineX + to, lineY + 1 + LINE_HEIGHT, BRACKET_BG);
  }

  // ---- Bracket matching ----

  private void ensureBracketMatch(String text) {
    int cursor = textField.cursor();
    if (text == bracketText && cursor == bracketCursor) {
      return;
    }
    bracketText = text;
    bracketCursor = cursor;
    bracketA = -1;
    bracketB = -1;
    int candidate = -1;
    if (cursor < text.length() && isStructuralBracket(text, cursor)) {
      candidate = cursor;
    } else if (cursor > 0 && isStructuralBracket(text, cursor - 1)) {
      candidate = cursor - 1;
    }
    if (candidate < 0) {
      return;
    }
    int match = findMatchingBracket(text, candidate);
    if (match >= 0) {
      bracketA = candidate;
      bracketB = match;
    }
  }

  /** A brace/bracket that is real JSON structure (the highlighter marked it punctuation, not string content). */
  private boolean isStructuralBracket(String text, int index) {
    char c = text.charAt(index);
    return (c == '{' || c == '}' || c == '[' || c == ']')
        && index < scannedColors.length && scannedColors[index] == JsonHighlighter.PUNCTUATION;
  }

  private int findMatchingBracket(String text, int index) {
    char open = text.charAt(index);
    boolean forward = open == '{' || open == '[';
    char close = switch (open) {
      case '{' -> '}';
      case '}' -> '{';
      case '[' -> ']';
      default -> '[';
    };
    int depth = 0;
    int step = forward ? 1 : -1;
    for (int i = index; i >= 0 && i < text.length(); i += step) {
      if (!isStructuralBracket(text, i)) {
        continue;
      }
      char c = text.charAt(i);
      if (c == open) {
        depth++;
      } else if (c == close) {
        depth--;
        if (depth == 0) {
          return i;
        }
      }
    }
    return -1;
  }

  // ---- Plumbing (vanilla parity) ----

  @Override
  public int getInnerHeight() {
    return LINE_HEIGHT * textField.getLineCount();
  }

  @Override
  protected boolean scrollbarVisible() {
    return textField.getLineCount() > displayableLineCount();
  }

  @Override
  protected double scrollRate() {
    return LINE_HEIGHT / 2.0;
  }

  @Override
  public void setFocused(boolean focused) {
    super.setFocused(focused);
    if (focused) {
      focusedTime = Util.getMillis();
    }
  }

  private void ensureScanned(String text) {
    // Identity check: MultilineTextField keeps the same string instance until the next edit.
    if (text != scannedText) {
      scannedText = text;
      scannedColors = JsonHighlighter.scan(text);
    }
  }

  private double displayableLineCount() {
    return (double) (height - totalInnerPadding()) / LINE_HEIGHT;
  }

  private void scrollToCursor() {
    double scroll = scrollAmount();
    MultilineTextField.StringView top = textField.getLineView((int) (scroll / LINE_HEIGHT));
    if (textField.cursor() <= top.beginIndex()) {
      scroll = textField.getLineAtCursor() * LINE_HEIGHT;
    } else {
      MultilineTextField.StringView bottom =
          textField.getLineView((int) ((scroll + height) / LINE_HEIGHT) - 1);
      if (textField.cursor() > bottom.endIndex()) {
        scroll = textField.getLineAtCursor() * LINE_HEIGHT - height + LINE_HEIGHT
            + totalInnerPadding();
      }
    }
    setScrollAmount(scroll);
  }

  private void seekCursorScreen(double mouseX, double mouseY) {
    double localX = mouseX - getX() - innerPadding() - GUTTER_W;
    double localY = mouseY - getY() - innerPadding() + scrollAmount();
    textField.seekCursorToPoint(localX, localY);
  }
}
