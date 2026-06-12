package com.github.simcityexpansion.buildpack.ui.component;

import java.util.List;

import com.github.simcityexpansion.buildpack.BuildPack;
import com.github.simcityexpansion.buildpack.convert.StructureAnalysis;
import com.github.simcityexpansion.buildpack.ui.UiFormats;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import dev.vfyjxf.taffy.style.FlexDirection;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

/**
 * 材料清单（Litematica 的招牌功能）：按方块种类列出数量，
 * 缺失方块置顶并标红——SimuKraft 的建造者按材料施工，装前可见成本。
 */
public final class MaterialList {
  private MaterialList() {}

  /** 单次最多展示的行数，余量折叠为「还有 N 种」。 */
  private static final int MAX_ROWS = 100;

  /** 构建材料清单区块；清单为空时返回 null。 */
  public static UIElement build(List<StructureAnalysis.MaterialEntry> materials) {
    if (materials.isEmpty()) {
      return null;
    }
    UIElement column = new UIElement();
    column.addClass(BuildPack.cls("materials"));
    column.layout(layout -> layout.flexDirection(FlexDirection.COLUMN)
        .gapRow(2.0f).widthStretch());

    Label title = new Label();
    title.addClass(BuildPack.cls("form-title"));
    title.setValue(Component.translatable("buildpack.materials.title", materials.size()));
    column.addChild(title);

    int shown = Math.min(materials.size(), MAX_ROWS);
    for (int i = 0; i < shown; i++) {
      StructureAnalysis.MaterialEntry entry = materials.get(i);
      Label row = new Label();
      row.addClass(BuildPack.cls(entry.missing() ? "material-missing" : "material-row"));
      row.setValue(Component.translatable("buildpack.materials.entry",
          Component.literal(UiFormats.integer(entry.count()))
              .withStyle(ChatFormatting.WHITE),
          entry.missing()
              ? Component.literal(entry.blockId())
              : entry.displayName()));
      column.addChild(row);
    }
    if (materials.size() > shown) {
      Label more = new Label();
      more.addClass(BuildPack.cls("material-row"));
      more.setValue(Component.translatable("buildpack.materials.more",
          materials.size() - shown));
      column.addChild(more);
    }
    return column;
  }
}
