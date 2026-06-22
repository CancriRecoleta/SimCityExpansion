package com.github.simcityexpansion.buildpack.ui.preview;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

/**
 * Preview slot: a persistent widget that hosts the "current preview child widget" (3D scene /
 * isometric view / top-down view / embedded thumbnail / placeholder). When the selection changes,
 * only {@link #setChild} is needed to swap the child — no need to repeatedly add or remove widgets
 * from the screen. Render and mouse events are forwarded to the child after syncing this slot's
 * bounds.
 */
public final class PreviewSlot extends AbstractWidget {
  @Nullable
  private AbstractWidget child;

  public PreviewSlot(int x, int y, int width, int height) {
    super(x, y, width, height, Component.empty());
  }

  /** Sets the current preview child widget ({@code null} clears it); releases the previous scene's GPU buffer on replacement. */
  public void setChild(@Nullable AbstractWidget child) {
    if (this.child instanceof StructureScene scene && this.child != child) {
      scene.close();
    }
    this.child = child;
    syncBounds();
  }

  private void syncBounds() {
    if (child != null) {
      child.setX(getX());
      child.setY(getY());
      child.setWidth(getWidth());
      child.setHeight(getHeight());
    }
  }

  @Override
  protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
    syncBounds();
    if (child != null) {
      child.render(g, mouseX, mouseY, partialTick);
    }
  }

  @Override
  public boolean mouseClicked(double mouseX, double mouseY, int button) {
    syncBounds();
    return child != null && child.mouseClicked(mouseX, mouseY, button);
  }

  @Override
  public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
    syncBounds();
    return child != null && child.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
  }

  @Override
  protected void updateWidgetNarration(NarrationElementOutput output) {}
}
