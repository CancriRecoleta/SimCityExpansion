package com.github.simcityexpansion.buildpack.ui;

import java.util.List;

import com.github.simcityexpansion.buildpack.convert.StructureAnalysis.MaterialEntry;
import com.github.simcityexpansion.buildpack.ui.component.MaterialListView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** 材料清单独立页面：满屏分页展示某建筑的全部材料；「返回」回到打开它的界面。 */
public final class MaterialListScreen extends Screen {

  private static final int PAD = 10;
  private static final int TITLE_H = 12;
  private static final int GAP = 4;
  private static final int BUTTON_H = 20;

  private final Screen previous;
  private final String buildingName;
  private final List<MaterialEntry> materials;

  private MaterialListScreen(Screen previous, String buildingName, List<MaterialEntry> materials) {
    super(Component.translatable("buildpack.materials.screen_title"));
    this.previous = previous;
    this.buildingName = buildingName;
    this.materials = materials;
  }

  /** 打开材料清单页面（返回时切回打开它的界面）。 */
  public static void open(String buildingName, List<MaterialEntry> materials) {
    Minecraft mc = Minecraft.getInstance();
    mc.setScreen(new MaterialListScreen(mc.screen, buildingName, materials));
  }

  private int boxY() {
    return PAD + TITLE_H + GAP;
  }

  private int boxHeight() {
    return height - PAD - BUTTON_H - GAP - boxY();
  }

  @Override
  protected void init() {
    MaterialListView list = new MaterialListView(PAD + 2, boxY() + 2, width - PAD * 2 - 4, boxHeight() - 4);
    list.setMaterials(materials);
    addRenderableWidget(list);

    addRenderableWidget(new ThemedButton(PAD, height - PAD - BUTTON_H, 80, BUTTON_H,
        Component.translatable("buildpack.action.back"), this::onClose));
  }

  @Override
  public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
    // 覆盖原版：不做高斯模糊背景。
  }

  @Override
  public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
    g.fill(0, 0, width, height, BuildPackTheme.ROOT_BG);
    BuildPackTheme.panel(g, PAD, boxY(), width - PAD * 2, boxHeight());
    g.drawString(font, Component.translatable(
        "buildpack.materials.screen_heading", buildingName, materials.size()),
        PAD + 10, PAD, BuildPackTheme.TITLE, true);
    super.render(g, mouseX, mouseY, partialTick);
  }

  @Override
  public void onClose() {
    if (previous != null) {
      minecraft.setScreen(previous);
    } else {
      BuildPackScreen.open();
    }
  }
}
