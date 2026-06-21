package com.github.simcityexpansion.buildpack.ui.preview;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

/**
 * 预览插槽：一个持久存在的控件，内部托管「当前预览子控件」（3D 场景 / 等距图 / 俯视图 /
 * 内嵌缩略图 / 占位）。选中项变化时只需 {@link #setChild} 换子控件，无需在屏幕上反复增删控件。
 * 渲染与鼠标事件都按本插槽边界同步给子控件后再转发。
 */
public final class PreviewSlot extends AbstractWidget {
  @Nullable
  private AbstractWidget child;

  public PreviewSlot(int x, int y, int width, int height) {
    super(x, y, width, height, Component.empty());
  }

  /** 设置当前预览子控件（{@code null} 表示清空）；替换时释放上一个场景的 GPU 缓冲。 */
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
