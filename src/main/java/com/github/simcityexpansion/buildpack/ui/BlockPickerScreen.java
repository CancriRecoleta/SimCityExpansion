package com.github.simcityexpansion.buildpack.ui;

import java.util.function.Consumer;

import com.github.simcityexpansion.buildpack.convert.NbtStructure;
import com.github.simcityexpansion.buildpack.convert.StructureAnalysis;
import com.github.simcityexpansion.buildpack.ui.component.MaterialListView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** 方块类型选择页：分页列出结构里的所有方块类型，点选一个把它的 id 回调给调用方（用于删除/替换）。 */
public final class BlockPickerScreen extends Screen {

  private static final int PAD = 10;
  private static final int TITLE_H = 12;
  private static final int GAP = 4;
  private static final int BUTTON_H = 20;

  private final Screen previous;
  private final NbtStructure structure;
  private final Consumer<String> onPick;

  private BlockPickerScreen(Screen previous, NbtStructure structure, Consumer<String> onPick) {
    super(Component.translatable("buildpack.editor.remove_title"));
    this.previous = previous;
    this.structure = structure;
    this.onPick = onPick;
  }

  /** 打开方块类型选择页；点选某行回调其方块 id（后续导航由 onPick 自行决定）。 */
  public static void open(NbtStructure structure, Consumer<String> onPick) {
    Minecraft mc = Minecraft.getInstance();
    mc.setScreen(new BlockPickerScreen(mc.screen, structure, onPick));
  }

  private int boxY() {
    return PAD + TITLE_H + GAP;
  }

  private int boxHeight() {
    return height - PAD - BUTTON_H - GAP - boxY();
  }

  @Override
  protected void init() {
    MaterialListView list =
        new MaterialListView(PAD + 2, boxY() + 2, width - PAD * 2 - 4, boxHeight() - 4);
    list.setMaterials(StructureAnalysis.materials(structure));
    list.setOnSelect(entry -> onPick.accept(entry.blockId()));
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
    g.drawString(font, Component.translatable("buildpack.editor.remove_title"),
        PAD + 10, PAD, BuildPackTheme.TITLE, true);
    g.drawString(font, Component.translatable("buildpack.editor.remove_hint"),
        PAD + 90, height - PAD - BUTTON_H + (BUTTON_H - font.lineHeight) / 2,
        BuildPackTheme.HINT, true);
    super.render(g, mouseX, mouseY, partialTick);
  }

  @Override
  public void onClose() {
    if (previous != null) {
      minecraft.setScreen(previous);
    }
  }
}
