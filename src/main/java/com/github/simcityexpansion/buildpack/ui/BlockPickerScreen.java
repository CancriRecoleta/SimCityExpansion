package com.github.simcityexpansion.buildpack.ui;

import java.util.function.Consumer;

import com.github.simcityexpansion.buildpack.BuildPack;
import com.github.simcityexpansion.buildpack.convert.NbtStructure;
import com.github.simcityexpansion.buildpack.convert.StructureAnalysis;
import com.github.simcityexpansion.buildpack.ui.component.MaterialListView;
import com.lowdragmc.lowdraglib2.gui.holder.ModularUIScreen;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import dev.vfyjxf.taffy.style.AlignItems;
import dev.vfyjxf.taffy.style.FlexDirection;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** 方块类型选择页：分页列出结构里的所有方块类型，点选一个把它的 id 回调给调用方（用于删除）。 */
public final class BlockPickerScreen extends ModularUIScreen {

  private BlockPickerScreen(ModularUI modularUI) {
    super(modularUI, Component.translatable("buildpack.editor.remove_title"));
  }

  /** 打开方块类型选择页；点选某行回调其方块 id 并切回上一界面。 */
  public static void open(NbtStructure structure, Consumer<String> onPick) {
    Screen previous = Minecraft.getInstance().screen;

    MaterialListView list = new MaterialListView();
    list.setMaterials(StructureAnalysis.materials(structure));
    // onPick 自行决定后续导航（删除：回编辑器；替换：再开一次选择器），故这里不自动返回。
    list.setOnSelect(entry -> onPick.accept(entry.blockId()));

    UIElement root = new UIElement();
    root.addClass(BuildPack.cls("root"));
    root.layout(layout -> layout.widthPercent(100.0f).heightPercent(100.0f)
        .flexDirection(FlexDirection.COLUMN).paddingAll(10.0f).gapRow(4.0f));

    Label title = new Label();
    title.addClass(BuildPack.cls("title"));
    title.setValue(Component.translatable("buildpack.editor.remove_title"));
    title.layout(layout -> layout.marginLeft(10.0f).height(12.0f));

    UIElement box = new UIElement();
    box.addClass(BuildPack.cls("browser"));
    box.layout(layout -> layout.flexDirection(FlexDirection.COLUMN)
        .paddingAll(2.0f).widthStretch().flexGrow(1.0f));
    box.addChild(list);

    Button back = new Button().setText(Component.translatable("buildpack.action.back"));
    back.addClass(BuildPack.cls("action"));
    back.setOnClick(event -> {
      if (previous != null) {
        Minecraft.getInstance().setScreen(previous);
      }
    });

    Label hint = new Label();
    hint.addClass(BuildPack.cls("status-hint"));
    hint.setValue(Component.translatable("buildpack.editor.remove_hint"));
    hint.layout(layout -> layout.marginLeft(8.0f).flexGrow(1.0f));

    UIElement bottom = new UIElement();
    bottom.layout(layout -> layout.flexDirection(FlexDirection.ROW)
        .alignItems(AlignItems.CENTER).gapColumn(4.0f).widthStretch().height(22.0f));
    bottom.addChildren(back, hint);

    root.addChildren(title, box, bottom);

    UI ui = UI.of(root, BuildPack.STYLESHEET);
    Minecraft.getInstance().setScreen(new BlockPickerScreen(ModularUI.of(ui)));
  }
}
