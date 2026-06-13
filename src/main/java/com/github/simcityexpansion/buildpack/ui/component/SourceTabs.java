package com.github.simcityexpansion.buildpack.ui.component;

import com.github.simcityexpansion.buildpack.BuildPack;
import com.github.simcityexpansion.buildpack.ui.BuildPackView;
import com.github.simcityexpansion.buildpack.ui.SourceTab;
import com.github.simcityexpansion.buildpack.ui.UiCheckbox;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Toggle;
import dev.vfyjxf.taffy.style.FlexDirection;

/** 来源页签行：导入文件 / 拓展包 / 已安装（互斥单选，呈一排带勾的勾选框）。 */
public final class SourceTabs {
  private SourceTabs() {}

  /** 构建页签行，切换时回调宿主视图。 */
  public static UIElement build(BuildPackView view, SourceTab initial) {
    // 一排左对齐的勾选框（互斥单选：当前来源被勾选，其余为空框）。
    UIElement row = new UIElement();
    row.addClass(BuildPack.cls("tabs"));
    row.layout(layout -> layout.flexDirection(FlexDirection.ROW).gapColumn(4.0f).widthStretch());

    Toggle.ToggleGroup group = new Toggle.ToggleGroup().setAllowEmpty(false);
    for (SourceTab tab : SourceTab.values()) {
      Toggle toggle = new Toggle();
      toggle.addClass(BuildPack.cls("tab"));
      toggle.setText(tab.displayName());
      UiCheckbox.style(toggle);
      toggle.setToggleGroup(group);
      toggle.style(style -> style.tooltips(tab.tooltip()));
      // 90px 容纳英文页签文本（"Import Files" 在 80px 下贴边）。
      toggle.layout(layout -> layout.height(UiCheckbox.HEIGHT).width(90.0f));
      toggle.setOn(tab == initial, false);
      toggle.setOnToggleChanged(on -> {
        if (on) {
          view.onTabChanged(tab);
        }
      });
      row.addChild(toggle);
    }
    return row;
  }
}
