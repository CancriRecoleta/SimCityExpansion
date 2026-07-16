package com.github.simcityexpansion.buildpack.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Modal dialog for the array-copy tool: clone the current selection N times at a fixed offset
 * (fences, floors, window columns). Defaults to one copy offset by the selection width so
 * confirming immediately gives a side-by-side repeat.
 */
public final class ArrayRepeatScreen extends Screen {

  /** Callback with the confirmed repeat parameters. */
  public interface Handler {
    void apply(int count, int dx, int dy, int dz);
  }

  private static final int W = 240;
  private static final int H = 128;
  private static final int MAX_COUNT = 64;

  private final Screen parent;
  private final int defaultDx;
  private final int defaultDy;
  private final int defaultDz;
  private final Handler handler;
  private EditBox countBox;
  private EditBox dxBox;
  private EditBox dyBox;
  private EditBox dzBox;

  private ArrayRepeatScreen(Screen parent, int dx, int dy, int dz, Handler handler) {
    super(Component.translatable("buildpack.array.title"));
    this.parent = parent;
    this.defaultDx = dx;
    this.defaultDy = dy;
    this.defaultDz = dz;
    this.handler = handler;
  }

  /** Opens the dialog with the given default offset (typically the selection dimensions). */
  public static void open(int dx, int dy, int dz, Handler handler) {
    Minecraft mc = Minecraft.getInstance();
    mc.setScreen(new ArrayRepeatScreen(mc.screen, dx, dy, dz, handler));
  }

  private int left() {
    return (width - W) / 2;
  }

  private int top() {
    return (height - H) / 2;
  }

  @Override
  protected void init() {
    countBox = intField(left() + 70, top() + 26, 48, "1");
    setInitialFocus(countBox);
    int fieldW = 44;
    int fx = left() + 70;
    dxBox = intField(fx, top() + 50, fieldW, Integer.toString(defaultDx));
    dyBox = intField(fx + fieldW + 6, top() + 50, fieldW, Integer.toString(defaultDy));
    dzBox = intField(fx + (fieldW + 6) * 2, top() + 50, fieldW, Integer.toString(defaultDz));

    int by = top() + H - 26;
    int bw = (W - 30) / 2;
    addRenderableWidget(new ThemedButton(left() + 10, by, bw, 18,
        Component.translatable("buildpack.prompt.confirm"), this::confirm));
    addRenderableWidget(new ThemedButton(left() + 20 + bw, by, bw, 18,
        Component.translatable("buildpack.prompt.cancel"), this::onClose));
  }

  private EditBox intField(int x, int y, int w, String initial) {
    EditBox box = new EditBox(font, x, y, w, 18, Component.empty());
    box.setMaxLength(6);
    box.setValue(initial);
    box.setFilter(text -> text.isEmpty() || text.equals("-") || text.matches("-?\\d+"));
    addRenderableWidget(box);
    return box;
  }

  private void confirm() {
    int count = clampInt(parse(countBox, 1), 1, MAX_COUNT);
    int dx = parse(dxBox, 0);
    int dy = parse(dyBox, 0);
    int dz = parse(dzBox, 0);
    if (dx != 0 || dy != 0 || dz != 0) {
      handler.apply(count, dx, dy, dz);
    }
    onClose();
  }

  private static int parse(EditBox box, int fallback) {
    try {
      return Integer.parseInt(box.getValue().trim());
    } catch (NumberFormatException e) {
      return fallback;
    }
  }

  private static int clampInt(int v, int lo, int hi) {
    return Math.max(lo, Math.min(v, hi));
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
    g.drawString(font, Component.translatable("buildpack.array.heading"),
        left() + 10, top() + 10, BuildPackTheme.VALUE, true);
    g.drawString(font, Component.translatable("buildpack.array.count"),
        left() + 10, top() + 31, BuildPackTheme.LABEL, true);
    g.drawString(font, Component.translatable("buildpack.array.offset"),
        left() + 10, top() + 55, BuildPackTheme.LABEL, true);
    g.drawString(font, Component.translatable("buildpack.array.hint"),
        left() + 10, top() + 76, BuildPackTheme.HINT, true);
    super.render(g, mouseX, mouseY, partialTick);
  }

  @Override
  public void onClose() {
    minecraft.setScreen(parent);
  }
}
