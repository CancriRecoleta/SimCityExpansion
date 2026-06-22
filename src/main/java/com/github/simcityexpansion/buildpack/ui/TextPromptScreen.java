package com.github.simcityexpansion.buildpack.ui;

import java.util.function.Consumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * General-purpose modal text-input dialog (overlaid on the previous screen): title/prompt + input field + confirm/cancel.
 * Used for renaming, moving to a folder, editing tags/notes, exporting a pack name, etc. Enter = confirm, Esc = cancel.
 */
public final class TextPromptScreen extends Screen {

  private static final int W = 240;
  private static final int H = 84;

  private final Screen parent;
  private final Component prompt;
  private final String initial;
  private final Consumer<String> onConfirm;
  private EditBox input;

  private TextPromptScreen(Screen parent, Component title, Component prompt,
      String initial, Consumer<String> onConfirm) {
    super(title);
    this.parent = parent;
    this.prompt = prompt;
    this.initial = initial;
    this.onConfirm = onConfirm;
  }

  /** Opens the input dialog; on confirm, calls back with the trimmed, non-empty input text; on cancel, no callback is made. */
  public static void open(
      Component title, Component prompt, String initial, Consumer<String> onConfirm) {
    Minecraft mc = Minecraft.getInstance();
    mc.setScreen(new TextPromptScreen(mc.screen, title, prompt, initial, onConfirm));
  }

  private int left() {
    return (width - W) / 2;
  }

  private int top() {
    return (height - H) / 2;
  }

  @Override
  protected void init() {
    input = new EditBox(font, left() + 10, top() + 32, W - 20, 18, Component.empty());
    input.setMaxLength(256);
    input.setValue(initial == null ? "" : initial);
    addRenderableWidget(input);
    setInitialFocus(input);

    int by = top() + H - 26;
    int bw = (W - 30) / 2;
    addRenderableWidget(new ThemedButton(left() + 10, by, bw, 18,
        Component.translatable("buildpack.prompt.confirm"), this::confirm));
    addRenderableWidget(new ThemedButton(left() + 20 + bw, by, bw, 18,
        Component.translatable("buildpack.prompt.cancel"), this::onClose));
  }

  private void confirm() {
    String value = input.getValue().trim();
    if (!value.isEmpty()) {
      onConfirm.accept(value);
    }
    onClose();
  }

  @Override
  public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
    if (keyCode == 257 || keyCode == 335) {
      confirm();
      return true;
    }
    return super.keyPressed(keyCode, scanCode, modifiers);
  }

  @Override
  public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
    // Override vanilla: skip the Gaussian-blur background.
  }

  @Override
  public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
    g.fill(0, 0, width, height, 0xC0000000);
    BuildPackTheme.fillPanel(g, left(), top(), W, H, 0xF0101010);
    g.drawString(font, prompt, left() + 10, top() + 12, BuildPackTheme.VALUE, true);
    super.render(g, mouseX, mouseY, partialTick);
  }

  @Override
  public void onClose() {
    minecraft.setScreen(parent);
  }
}
