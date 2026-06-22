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
 * Static structure analysis: material list (block counts by type) and missing block detection
 * (palette checked against the current registry — blocks whose mod is not installed become air
 * during placement).
 */
public final class StructureAnalysis {
  private StructureAnalysis() {}

  /**
   * A single material entry.
   *
   * @param blockId block id string
   * @param displayName localized name (falls back to the id text if missing from the registry)
   * @param count quantity
   * @param missing whether the block is missing in the current environment
   */
  public record MaterialEntry(
      String blockId, Component displayName, long count, boolean missing) {}

  /** Computes the material list (excluding air); missing blocks are sorted first, the rest in descending count order. */
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

  /** Returns block ids in the palette that are not found in the current registry (excluding air). */
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

  /** Returns the map color (ARGB) for each palette entry; air or missing blocks are 0 (transparent). */
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
          // Missing blocks are drawn in conspicuous magenta, signaling "something is here but not available in this environment".
          .orElse(0xFFCC00CC);
    }
    return colors;
  }

  private static Optional<Block> lookup(String blockId) {
    ResourceLocation id = ResourceLocation.tryParse(blockId);
    return id == null ? Optional.empty() : BuiltInRegistries.BLOCK.getOptional(id);
  }
}
