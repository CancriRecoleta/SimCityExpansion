package com.github.simcityexpansion.buildpack.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Numeric selection dialog: enter X/Y/Z for both min and max (automatically clamped to the
 * structure bounds), then invoke a callback on confirmation.
 * Used by the editor for region operations (delete/crop/fill region).
 */
public final class SelectionScreen extends Screen {

  /** Confirmation callback: receives the un-clamped min/max arrays (each of length 3). */
  public interface Callback {
    void apply(int[] min, int[] max);
  }

  private static final int W = 220;
  private static final int H = 132;
  private static final int LABEL_W = 30;

  private final Screen parent;
  private final int[] initialMin;
  private final int[] initialMax;
  private final int sizeX;
  private final int sizeY;
  private final int sizeZ;
  private final Callback callback;

  private EditBox minX;
  private EditBox minY;
  private EditBox minZ;
  private EditBox maxX;
  private EditBox maxY;
  private EditBox maxZ;

  private SelectionScreen(Screen parent, int[] min, int[] max,
      int sizeX, int sizeY, int sizeZ, Callback callback) {
    super(Component.translatable("buildpack.selection.title"));
    this.parent = parent;
    this.initialMin = min;
    this.initialMax = max;
    this.sizeX = sizeX;
    this.sizeY = sizeY;
    this.sizeZ = sizeZ;
    this.callback = callback;
  }

  /** Opens the selection dialog. */
  public static void open(int[] min, int[] max,
      int sizeX, int sizeY, int sizeZ, Callback callback) {
    Minecraft mc = Minecraft.getInstance();
    mc.setScreen(new SelectionScreen(mc.screen, min, max, sizeX, sizeY, sizeZ, callback));
  }

  private int left() {
    return (width - W) / 2;
  }

  private int top() {
    return (height - H) / 2;
  }

  @Override
  protected void init() {
    int colW = (W - 20 - LABEL_W * 2) / 2;
    int x1 = left() + 10 + LABEL_W;
    int x2 = left() + 10 + LABEL_W + colW + LABEL_W;
    int y = top() + 28;
    minX = field(x1, y, colW, initialMin[0]);
    maxX = field(x2, y, colW, initialMax[0]);
    y += 22;
    minY = field(x1, y, colW, initialMin[1]);
    maxY = field(x2, y, colW, initialMax[1]);
    y += 22;
    minZ = field(x1, y, colW, initialMin[2]);
    maxZ = field(x2, y, colW, initialMax[2]);

    int by = top() + H - 26;
    int bw = (W - 30) / 2;
    addRenderableWidget(new ThemedButton(left() + 10, by, bw, 18,
        Component.translatable("buildpack.prompt.confirm"), this::confirm));
    addRenderableWidget(new ThemedButton(left() + 20 + bw, by, bw, 18,
        Component.translatable("buildpack.prompt.cancel"), this::onClose));
  }

  private EditBox field(int x, int y, int w, int value) {
    EditBox box = new EditBox(font, x, y, w, 16, Component.empty());
    box.setMaxLength(8);
    box.setValue(Integer.toString(value));
    addRenderableWidget(box);
    return box;
  }

  private void confirm() {
    int[] min = {
        parse(minX, sizeX), parse(minY, sizeY), parse(minZ, sizeZ)};
    int[] max = {
        parse(maxX, sizeX), parse(maxY, sizeY), parse(maxZ, sizeZ)};
    callback.apply(min, max);
    onClose();
  }

  private int parse(EditBox box, int size) {
    int value;
    try {
      value = Integer.parseInt(box.getValue().trim());
    } catch (NumberFormatException e) {
      value = 0;
    }
    return Math.max(0, Math.min(value, size - 1));
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
    // No Gaussian-blur background.
  }

  @Override
  public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
    g.fill(0, 0, width, height, 0xC0000000);
    BuildPackTheme.fillPanel(g, left(), top(), W, H, 0xF0101010);
    g.drawString(font, Component.translatable("buildpack.selection.title"),
        left() + 10, top() + 10, BuildPackTheme.TITLE, true);
    int colW = (W - 20 - LABEL_W * 2) / 2;
    g.drawString(font, Component.translatable("buildpack.selection.min"),
        left() + 10 + LABEL_W + (colW - font.width("min")) / 2, top() + 20, BuildPackTheme.LABEL, true);
    g.drawString(font, Component.translatable("buildpack.selection.max"),
        left() + 10 + LABEL_W * 2 + colW + (colW - font.width("max")) / 2, top() + 20,
        BuildPackTheme.LABEL, true);
    int y = top() + 28;
    rowLabel(g, "X", y);
    y += 22;
    rowLabel(g, "Y", y);
    y += 22;
    rowLabel(g, "Z", y);
    drawDims(g);
    super.render(g, mouseX, mouseY, partialTick);
  }

  /** Live readout of the resulting selection size and volume; turns red on invalid input. */
  private void drawDims(GuiGraphics g) {
    int dx = Math.abs(parse(maxX, sizeX) - parse(minX, sizeX)) + 1;
    int dy = Math.abs(parse(maxY, sizeY) - parse(minY, sizeY)) + 1;
    int dz = Math.abs(parse(maxZ, sizeZ) - parse(minZ, sizeZ)) + 1;
    boolean valid = isValid(minX) && isValid(minY) && isValid(minZ)
        && isValid(maxX) && isValid(maxY) && isValid(maxZ);
    String text = Component.translatable("buildpack.preview.sel",
        dx + "×" + dy + "×" + dz, (long) dx * dy * dz).getString();
    g.drawString(font, text, left() + 10, top() + H - 40,
        valid ? BuildPackTheme.VALUE : BuildPackTheme.MESSAGE_ERROR, true);
  }

  private boolean isValid(EditBox box) {
    try {
      Integer.parseInt(box.getValue().trim());
      return true;
    } catch (NumberFormatException e) {
      return false;
    }
  }

  private void rowLabel(GuiGraphics g, String axis, int y) {
    g.drawString(font, axis, left() + 12, y + 4, BuildPackTheme.VALUE, true);
  }

  @Override
  public void onClose() {
    minecraft.setScreen(parent);
  }
}
