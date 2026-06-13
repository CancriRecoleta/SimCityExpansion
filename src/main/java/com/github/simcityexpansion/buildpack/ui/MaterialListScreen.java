package com.github.simcityexpansion.buildpack.ui;

import java.util.List;

import com.github.simcityexpansion.buildpack.BuildPack;
import com.github.simcityexpansion.buildpack.convert.StructureAnalysis.MaterialEntry;
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
import net.minecraft.network.chat.Component;

/** 材料清单独立页面：满屏分页展示某建筑的全部材料；「返回」回到建筑拓展包管理器。 */
public final class MaterialListScreen extends ModularUIScreen {

  private MaterialListScreen(ModularUI modularUI) {
    super(modularUI, Component.translatable("buildpack.materials.screen_title"));
  }

  /** 打开材料清单页面。 */
  public static void open(String buildingName, List<MaterialEntry> materials) {
    MaterialListView list = new MaterialListView();
    list.setMaterials(materials);

    UIElement root = new UIElement();
    root.addClass(BuildPack.cls("root"));
    root.layout(layout -> layout.widthPercent(100.0f).heightPercent(100.0f)
        .flexDirection(FlexDirection.COLUMN).paddingAll(10.0f).gapRow(4.0f));

    Label title = new Label();
    title.addClass(BuildPack.cls("title"));
    title.setValue(Component.translatable(
        "buildpack.materials.screen_heading", buildingName, materials.size()));
    title.layout(layout -> layout.marginLeft(10.0f).height(12.0f));

    UIElement box = new UIElement();
    box.addClass(BuildPack.cls("browser"));
    box.layout(layout -> layout.flexDirection(FlexDirection.COLUMN)
        .paddingAll(2.0f).widthStretch().flexGrow(1.0f));
    box.addChild(list);

    Button back = new Button().setText(Component.translatable("buildpack.action.back"));
    back.addClass(BuildPack.cls("action"));
    back.setOnClick(event -> BuildPackScreen.open());

    UIElement bottom = new UIElement();
    bottom.layout(layout -> layout.flexDirection(FlexDirection.ROW)
        .alignItems(AlignItems.CENTER).widthStretch().height(22.0f));
    bottom.addChild(back);

    root.addChildren(title, box, bottom);

    UI ui = UI.of(root, BuildPack.STYLESHEET);
    Minecraft.getInstance().setScreen(new MaterialListScreen(ModularUI.of(ui)));
  }
}
