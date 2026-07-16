package com.github.simcityexpansion.buildpack.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Modal dialog for the family swap (e.g. {@code oak → spruce} across planks/stairs/doors/fences in
 * one step): "from" and "to" word roots, plus an optional selection-only scope. The actual token
 * replacement lives in
 * {@link com.github.simcityexpansion.buildpack.convert.StructureTransforms#swapFamily}.
 */
public final class FamilySwapScreen extends Screen {

  /** Callback with the confirmed swap parameters. */
  public interface Handler {
    void apply(String from, String to, boolean selectionOnly);
  }

  private static final int W = 260;
  private static final int H = 150;

  private final Screen parent;
  private final boolean offerSelection;
  private final Handler handler;
  private EditBox fromBox;
  private EditBox toBox;
  private boolean selectionOnly;

  private FamilySwapScreen(Screen parent, boolean offerSelection, Handler handler) {
    super(Component.translatable("buildpack.swap.title"));
    this.parent = parent;
    this.offerSelection = offerSelection;
    this.handler = handler;
  }

  /** Opens the swap dialog; {@code offerSelection} shows the "selection only" checkbox. */
  public static void open(boolean offerSelection, Handler handler) {
    Minecraft mc = Minecraft.getInstance();
    mc.setScreen(new FamilySwapScreen(mc.screen, offerSelection, handler));
  }

  private int left() {
    return (width - W) / 2;
  }

  private int top() {
    return (height - H) / 2;
  }

  @Override
  protected void init() {
    fromBox = new EditBox(font, left() + 70, top() + 26, W - 80, 18, Component.empty());
    fromBox.setMaxLength(64);
    addRenderableWidget(fromBox);
    setInitialFocus(fromBox);

    toBox = new EditBox(font, left() + 70, top() + 48, W - 80, 18, Component.empty());
    toBox.setMaxLength(64);
    addRenderableWidget(toBox);

    if (offerSelection) {
      addRenderableWidget(Checkbox.builder(
              Component.translatable("buildpack.swap.selection_only"), font)
          .pos(left() + 10, top() + 72)
          .selected(selectionOnly)
          .onValueChange((checkbox, value) -> selectionOnly = value)
          .build());
    }

    int by = top() + H - 26;
    int bw = (W - 30) / 2;
    addRenderableWidget(new ThemedButton(left() + 10, by, bw, 18,
        Component.translatable("buildpack.prompt.confirm"), this::confirm));
    addRenderableWidget(new ThemedButton(left() + 20 + bw, by, bw, 18,
        Component.translatable("buildpack.prompt.cancel"), this::onClose));
  }

  private void confirm() {
    String from = fromBox.getValue().trim();
    String to = toBox.getValue().trim();
    if (!from.isEmpty() && !to.isEmpty()) {
      handler.apply(from, to, offerSelection && selectionOnly);
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
    g.drawString(font, Component.translatable("buildpack.swap.heading"),
        left() + 10, top() + 10, BuildPackTheme.VALUE, true);
    g.drawString(font, Component.translatable("buildpack.swap.from"),
        left() + 10, top() + 31, BuildPackTheme.LABEL, true);
    g.drawString(font, Component.translatable("buildpack.swap.to"),
        left() + 10, top() + 53, BuildPackTheme.LABEL, true);
    g.drawString(font, Component.translatable("buildpack.swap.hint"),
        left() + 10, top() + H - 40, BuildPackTheme.HINT, true);
    super.render(g, mouseX, mouseY, partialTick);
  }

  @Override
  public void onClose() {
    minecraft.setScreen(parent);
  }
}
