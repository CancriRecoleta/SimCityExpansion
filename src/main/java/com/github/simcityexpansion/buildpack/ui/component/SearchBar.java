package com.github.simcityexpansion.buildpack.ui.component;

import java.util.List;

import com.github.simcityexpansion.buildpack.BuildPack;
import com.github.simcityexpansion.buildpack.ui.BuildPackView;
import com.github.simcityexpansion.buildpack.ui.SortMode;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Selector;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextField;
import dev.vfyjxf.taffy.style.AlignItems;
import dev.vfyjxf.taffy.style.FlexDirection;
import net.minecraft.network.chat.Component;

/** 搜索/导航条（Litematica 目录条位，高 14）：文本过滤 + 排序选择。 */
public final class SearchBar {
  private SearchBar() {}

  /** 构建搜索行，输入与排序变化回调宿主视图。 */
  public static UIElement build(BuildPackView view) {
    UIElement row = new UIElement();
    row.addClass(BuildPack.cls("search"));
    row.layout(layout -> layout.flexDirection(FlexDirection.ROW)
        .alignItems(AlignItems.CENTER).gapColumn(3.0f).widthStretch().height(14.0f));

    TextField search = new TextField();
    search.addClass(BuildPack.cls("search-field"));
    search.setAnyString();
    search.setTextResponder(view::onSearchText);
    search.textFieldStyle(style ->
        style.placeholder(Component.translatable("buildpack.search.placeholder")));
    search.layout(layout -> layout.heightStretch().flexGrow(1.0f));

    Selector<SortMode> sort = new Selector<>();
    sort.addClass(BuildPack.cls("sort"));
    sort.setCandidates(List.of(SortMode.values()));
    // 对 null 安全：Selector 设置 provider 时会用尚未选中的 null 试渲染一次。
    sort.setCandidateUIProvider(mode -> {
      Label option = new Label();
      option.setValue(mode == null ? Component.empty() : mode.displayName());
      return option;
    });
    sort.setSelected(view.sortMode());
    sort.setOnValueChanged(view::onSortChanged);
    sort.style(style -> style.tooltips(Component.translatable("buildpack.tooltip.sort")));
    sort.layout(layout -> layout.width(64.0f).heightStretch());

    row.addChildren(search, sort);
    return row;
  }
}
