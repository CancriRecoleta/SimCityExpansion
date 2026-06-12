package com.github.simcityexpansion.buildpack.ui.component;

import com.github.simcityexpansion.buildpack.BuildPack;
import com.github.simcityexpansion.buildpack.ui.BuildPackView;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextField;
import net.minecraft.network.chat.Component;

/** 搜索栏：按文件名/建筑名过滤当前页签的列表。 */
public final class SearchBar {
  private SearchBar() {}

  /** 构建搜索框，输入变化回调宿主视图。 */
  public static UIElement build(BuildPackView view) {
    TextField search = new TextField();
    search.addClass(BuildPack.cls("search-field"));
    search.setAnyString();
    search.setTextResponder(view::onSearchText);
    search.textFieldStyle(style ->
        style.placeholder(Component.translatable("buildpack.search.placeholder")));
    // Litematica 的目录导航/搜索条高 14。
    search.layout(layout -> layout.widthStretch().height(14.0f));
    return search;
  }
}
