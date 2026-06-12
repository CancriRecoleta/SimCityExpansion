package com.github.simcityexpansion.buildpack.ui.component;

import com.github.simcityexpansion.buildpack.BuildPack;
import com.github.simcityexpansion.buildpack.ui.BuildPackView;
import com.github.simcityexpansion.buildpack.ui.SourceTab;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Toggle;
import dev.vfyjxf.taffy.style.FlexDirection;

/** 来源页签行：导入文件 / 拓展包 / 已安装（互斥单选）。 */
public final class SourceTabs {
  private SourceTabs() {}

  /** 构建页签行，切换时回调宿主视图。 */
  public static UIElement build(BuildPackView view, SourceTab initial) {
    // Litematica 的页签是左对齐的一排定宽按钮（高 20），不平铺整行。
    UIElement row = new UIElement();
    row.addClass(BuildPack.cls("tabs"));
    row.layout(layout -> layout.flexDirection(FlexDirection.ROW).gapColumn(4.0f).widthStretch());

    Toggle.ToggleGroup group = new Toggle.ToggleGroup().setAllowEmpty(false);
    for (SourceTab tab : SourceTab.values()) {
      Toggle toggle = new Toggle();
      toggle.addClass(BuildPack.cls("tab"));
      toggle.setText(tab.displayName());
      toggle.setToggleGroup(group);
      toggle.layout(layout -> layout.height(20.0f).width(80.0f));
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
