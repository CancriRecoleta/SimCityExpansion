package com.github.simcityexpansion.buildpack.convert;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;

/**
 * 结构静态分析：材料清单（按方块种类计数）与缺失方块检测
 * （调色板对照当前注册表——所属模组未安装的方块建造时会变成空气）。
 */
public final class StructureAnalysis {
  private StructureAnalysis() {}

  /**
   * 一种材料。
   *
   * @param blockId 方块 id 字符串
   * @param displayName 本地化名（注册表缺失时回退为 id 文本）
   * @param count 数量
   * @param missing 当前环境是否缺失该方块
   */
  public record MaterialEntry(
      String blockId, Component displayName, long count, boolean missing) {}

  /** 统计材料清单（不含空气），缺失方块排在最前，其余按数量降序。 */
  public static List<MaterialEntry> materials(NbtStructure structure) {
    long[] countsByIndex = new long[structure.palette.size()];
    for (NbtStructure.BlockEntry block : structure.blocks) {
      if (block.stateIndex() >= 0 && block.stateIndex() < countsByIndex.length) {
        countsByIndex[block.stateIndex()]++;
      }
    }

    Map<String, long[]> countsById = new LinkedHashMap<>();
    for (int i = 0; i < structure.palette.size(); i++) {
      NbtStructure.PaletteEntry entry = structure.palette.get(i);
      if (entry.isAir() || countsByIndex[i] == 0) {
        continue;
      }
      countsById.computeIfAbsent(entry.blockName(), key -> new long[1])[0] += countsByIndex[i];
    }

    List<MaterialEntry> result = new ArrayList<>(countsById.size());
    for (Map.Entry<String, long[]> entry : countsById.entrySet()) {
      Optional<Block> block = lookup(entry.getKey());
      result.add(new MaterialEntry(
          entry.getKey(),
          block.<Component>map(Block::getName)
              .orElse(Component.literal(entry.getKey())),
          entry.getValue()[0],
          block.isEmpty()));
    }
    result.sort(Comparator
        .comparing((MaterialEntry entry) -> !entry.missing())
        .thenComparing(Comparator.comparingLong(MaterialEntry::count).reversed()));
    return result;
  }

  /** 调色板中当前注册表查不到的方块 id（不含空气）。 */
  public static List<String> findMissingBlocks(NbtStructure structure) {
    List<String> missing = new ArrayList<>();
    for (NbtStructure.PaletteEntry entry : structure.palette) {
      if (!entry.isAir() && lookup(entry.blockName()).isEmpty()
          && !missing.contains(entry.blockName())) {
        missing.add(entry.blockName());
      }
    }
    return missing;
  }

  /** 调色板各条目的地图色（ARGB；空气/缺失为 0，即透明）。 */
  public static int[] paletteMapColors(NbtStructure structure) {
    int[] colors = new int[structure.palette.size()];
    for (int i = 0; i < structure.palette.size(); i++) {
      NbtStructure.PaletteEntry entry = structure.palette.get(i);
      if (entry.isAir()) {
        continue;
      }
      colors[i] = lookup(entry.blockName())
          .map(block -> {
            int rgb = block.defaultBlockState()
                .getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).col;
            return rgb == 0 ? 0 : 0xFF000000 | rgb;
          })
          // 缺失方块画成显眼的品红，提示「这里有东西但当前环境没有」。
          .orElse(0xFFCC00CC);
    }
    return colors;
  }

  private static Optional<Block> lookup(String blockId) {
    ResourceLocation id = ResourceLocation.tryParse(blockId);
    return id == null ? Optional.empty() : BuiltInRegistries.BLOCK.getOptional(id);
  }
}
