package com.github.simcityexpansion.buildpack.ui;

import java.util.function.Consumer;

import com.github.simcityexpansion.buildpack.model.BuildingCategory;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Pick one of the five fixed SimuKraft building categories. The chosen category is handed to the
 * caller's callback (which decides navigation); used by the editor's "save &amp; install".
 */
public final class CategoryPickerScreen extends Screen {

  private static final int W = 200;
  private static final int ROW = 22;
  private static final int BTN_H = 18;

  private final Screen parent;
  private final Consumer<BuildingCategory> onPick;

  private CategoryPickerScreen(Screen parent, Consumer<BuildingCategory> onPick) {
    super(Component.translatable("buildpack.category.title"));
    this.parent = parent;
    this.onPick = onPick;
  }

  /** Open the category picker; the chosen category is delivered to {@code onPick}. */
  public static void open(Consumer<BuildingCategory> onPick) {
    Minecraft mc = Minecraft.getInstance();
    mc.setScreen(new CategoryPickerScreen(mc.screen, onPick));
  }

  private int panelH() {
    return 26 + (BuildingCategory.values().length + 1) * ROW + 4;
  }

  private int left() {
    return (width - W) / 2;
  }

  private int top() {
    return (height - panelH()) / 2;
  }

  @Override
  protected void init() {
    int y = top() + 26;
    for (BuildingCategory category : BuildingCategory.values()) {
      addRenderableWidget(new ThemedButton(left() + 10, y, W - 20, BTN_H,
          category.displayName(), () -> onPick.accept(category)));
      y += ROW;
    }
    addRenderableWidget(new ThemedButton(left() + 10, y, W - 20, BTN_H,
        Component.translatable("buildpack.prompt.cancel"), this::onClose));
  }

  @Override
  public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
    // No vanilla Gaussian blur.
  }

  @Override
  public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
    g.fill(0, 0, width, height, BuildPackTheme.ROOT_BG);
    BuildPackTheme.fillPanel(g, left(), top(), W, panelH(), 0xF0101010);
    g.drawString(font, Component.translatable("buildpack.category.title"),
        left() + 10, top() + 10, BuildPackTheme.TITLE, true);
    super.render(g, mouseX, mouseY, partialTick);
  }

  @Override
  public void onClose() {
    minecraft.setScreen(parent);
  }
}
