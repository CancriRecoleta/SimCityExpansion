package com.github.simcityexpansion.buildpack.ui.definition;

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

/**
 * Multiline JSON editor with syntax highlighting: a reimplementation of vanilla
 * {@code MultiLineEditBox} (whose text field is private) on top of the public
 * {@link MultilineTextField}, rendering each visual line as colored runs from
 * {@link JsonHighlighter} instead of flat text. Editing behavior — cursor, selection,
 * keyboard/mouse handling, scrolling — matches vanilla. The scan result is cached and only
 * recomputed when the text instance changes (each edit produces a new string).
 */
public final class JsonEditBox extends AbstractScrollWidget {

  private static final int CURSOR_COLOR = 0xFFD0D0D0;
  private static final int PLACEHOLDER_COLOR = 0xCCE0E0E0;
  private static final int LINE_HEIGHT = 9;
  private static final int CURSOR_BLINK_INTERVAL_MS = 300;

  private final Font font;
  private final Component placeholder;
  private final MultilineTextField textField;
  private long focusedTime = Util.getMillis();

  private String scannedText = "";
  private int[] scannedColors = new int[0];

  public JsonEditBox(Font font, int x, int y, int width, int height, Component placeholder) {
    super(x, y, width, height, Component.empty());
    this.font = font;
    this.placeholder = placeholder;
    this.textField = new MultilineTextField(font, width - this.totalInnerPadding());
    this.textField.setCursorListener(this::scrollToCursor);
  }

  /** See {@link MultilineTextField#setCharacterLimit(int)}. */
  public void setCharacterLimit(int characterLimit) {
    textField.setCharacterLimit(characterLimit);
  }

  /** Registers the change listener (fired on every edit, including {@link #setValue}). */
  public void setValueListener(Consumer<String> valueListener) {
    textField.setValueListener(valueListener);
  }

  /** Replaces the text (cursor moves to the end, as in vanilla). */
  public void setValue(String value) {
    textField.setValue(value);
  }

  /** Returns the current text. */
  public String getValue() {
    return textField.value();
  }

  /** Editor view snapshot (cursor position + scroll offset), kept across widget rebuilds. */
  public record ViewState(int cursor, double scroll) {}

  /** Captures the current view for restoring after a screen re-init. */
  public ViewState viewState() {
    return new ViewState(textField.cursor(), scrollAmount());
  }

  /** Restores a previously captured {@link #viewState()}. */
  public void restoreViewState(ViewState state) {
    textField.seekCursor(Whence.ABSOLUTE, state.cursor());
    setScrollAmount(state.scroll());
  }

  @Override
  public void updateWidgetNarration(NarrationElementOutput output) {
    output.add(NarratedElementType.TITLE,
        Component.translatable("gui.narrate.editBox", getMessage(), getValue()));
  }

  @Override
  public boolean mouseClicked(double mouseX, double mouseY, int button) {
    if (withinContentAreaPoint(mouseX, mouseY) && button == 0) {
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
    return textField.keyPressed(keyCode);
  }

  @Override
  public boolean charTyped(char codePoint, int modifiers) {
    if (visible && isFocused() && StringUtil.isAllowedChatCharacter(codePoint)) {
      textField.insertText(Character.toString(codePoint));
      return true;
    }
    return false;
  }

  @Override
  protected void renderContents(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
    String text = textField.value();
    if (text.isEmpty() && !isFocused()) {
      g.drawWordWrap(font, placeholder, getX() + innerPadding(), getY() + innerPadding(),
          width - totalInnerPadding(), PLACEHOLDER_COLOR);
      return;
    }
    ensureScanned(text);
    int cursor = textField.cursor();
    boolean blinkOn =
        isFocused() && (Util.getMillis() - focusedTime) / CURSOR_BLINK_INTERVAL_MS % 2L == 0L;
    boolean cursorInside = cursor < text.length();
    int lineY = getY() + innerPadding();
    int lineX = getX() + innerPadding();

    for (MultilineTextField.StringView line : textField.iterateLines()) {
      boolean visibleLine = withinContentAreaTopBottom(lineY, lineY + LINE_HEIGHT);
      if (visibleLine) {
        g.drawString(font,
            JsonHighlighter.styled(text, scannedColors, line.beginIndex(), line.endIndex()),
            lineX, lineY, JsonHighlighter.DEFAULT);
        if (blinkOn && cursorInside && cursor >= line.beginIndex() && cursor <= line.endIndex()) {
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
      int left = getX() + innerPadding();
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
              ? width - innerPadding()
              : font.width(text.substring(line.beginIndex(), selection.endIndex()));
          g.fill(RenderType.guiTextHighlight(), left + from, lineY, left + to,
              lineY + LINE_HEIGHT, 0xFF0000FF);
        }
        lineY += LINE_HEIGHT;
      }
    }
  }

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
    double localX = mouseX - getX() - innerPadding();
    double localY = mouseY - getY() - innerPadding() + scrollAmount();
    textField.seekCursorToPoint(localX, localY);
  }
}
