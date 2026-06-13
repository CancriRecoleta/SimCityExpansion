package com.github.simcityexpansion.buildpack.ui;

import com.github.simcityexpansion.buildpack.BuildPack;
import com.github.simcityexpansion.buildpack.convert.NbtStructure;
import com.github.simcityexpansion.buildpack.ui.preview.StructureScene;
import com.lowdragmc.lowdraglib2.gui.holder.ModularUIScreen;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import dev.vfyjxf.taffy.style.AlignItems;
import dev.vfyjxf.taffy.style.FlexDirection;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * 精细 3D 预览界面：满屏真实方块模型，左键拖动旋转、右键平移、滚轮缩放、中键/按钮重置视角；
 * 「返回」回到建筑拓展包管理器。
 */
public final class StructurePreviewScreen extends ModularUIScreen {

  private StructurePreviewScreen(ModularUI modularUI) {
    super(modularUI, Component.translatable("buildpack.preview.screen_title"));
  }

  /** 打开精细 3D 预览（结构超限/无法渲染时不打开）。 */
  public static void open(NbtStructure structure) {
    StructureScene scene = new StructureScene(true);
    if (!scene.setStructure(structure)) {
      return;
    }

    UIElement root = new UIElement();
    root.addClass(BuildPack.cls("root"));
    root.layout(layout -> layout.widthPercent(100.0f).heightPercent(100.0f)
        .flexDirection(FlexDirection.COLUMN).paddingAll(10.0f).gapRow(4.0f));

    Label title = new Label();
    title.addClass(BuildPack.cls("title"));
    title.setValue(Component.translatable("buildpack.preview.screen_title"));
    title.layout(layout -> layout.marginLeft(10.0f).height(12.0f));

    UIElement box = new UIElement();
    box.addClass(BuildPack.cls("preview"));
    box.layout(layout -> layout.flexDirection(FlexDirection.COLUMN)
        .widthStretch().flexGrow(1.0f));
    box.addChild(scene);

    Button back = new Button().setText(Component.translatable("buildpack.action.back"));
    back.addClass(BuildPack.cls("action"));
    back.setOnClick(event -> BuildPackScreen.open());

    Button reset = new Button().setText(Component.translatable("buildpack.preview.reset"));
    reset.addClass(BuildPack.cls("action"));
    reset.setOnClick(event -> scene.resetView());

    Label dims = new Label();
    dims.addClass(BuildPack.cls("status-count"));
    dims.setValue(Component.translatable("buildpack.preview.dims",
        structure.sizeX, structure.sizeY, structure.sizeZ, structure.countNonAir()));

    Label hint = new Label();
    hint.addClass(BuildPack.cls("status-hint"));
    hint.setValue(Component.translatable("buildpack.preview.controls"));
    hint.layout(layout -> layout.flexGrow(1.0f));

    UIElement bottom = new UIElement();
    bottom.layout(layout -> layout.flexDirection(FlexDirection.ROW)
        .alignItems(AlignItems.CENTER).gapColumn(8.0f).widthStretch().height(22.0f));
    bottom.addChildren(back, reset, dims, hint);

    root.addChildren(title, box, bottom);

    UI ui = UI.of(root, BuildPack.STYLESHEET);
    Minecraft.getInstance().setScreen(new StructurePreviewScreen(ModularUI.of(ui)));
  }
}
