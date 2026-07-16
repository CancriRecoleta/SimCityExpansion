package com.github.simcityexpansion.buildpack.convert;

import java.util.HashSet;
import java.util.Set;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/**
 * In-place operations on vanilla structure NBT tags (the format SimuKraft builds from).
 *
 * <p>Kept separate from {@link StructureMaterializer} so callers can opt in per flow: install and
 * activation strip {@code structure_void} (SimuKraft's builder places it as a real block —
 * invisible but occupying, replacing terrain — which is never what a pack author wants), while
 * Create-schematic export keeps it (schematic tools use it as a "don't place" marker).
 */
public final class StructureTagOps {
  private StructureTagOps() {}

  private static final String STRUCTURE_VOID = "minecraft:structure_void";

  /**
   * Removes every {@code minecraft:structure_void} block entry from the structure tag (top-level
   * or {@code Schematic}-wrapped); returns the number of removed block entries.
   */
  public static int stripStructureVoid(CompoundTag root) {
    CompoundTag structure = root.contains("Schematic", Tag.TAG_COMPOUND)
        && !root.contains("blocks", Tag.TAG_LIST)
        ? root.getCompound("Schematic") : root;
    ListTag palette = structurePalette(structure);
    if (palette == null || !structure.contains("blocks", Tag.TAG_LIST)) {
      return 0;
    }
    Set<Integer> voidIndices = new HashSet<>();
    for (int i = 0; i < palette.size(); i++) {
      if (STRUCTURE_VOID.equals(palette.getCompound(i).getString("Name"))) {
        voidIndices.add(i);
      }
    }
    if (voidIndices.isEmpty()) {
      return 0;
    }
    ListTag blocks = structure.getList("blocks", Tag.TAG_COMPOUND);
    int removed = 0;
    for (int i = blocks.size() - 1; i >= 0; i--) {
      if (voidIndices.contains(blocks.getCompound(i).getInt("state"))) {
        blocks.remove(i);
        removed++;
      }
    }
    return removed;
  }

  /** The active palette list: {@code palette}, or the first entry of {@code palettes}. */
  private static ListTag structurePalette(CompoundTag structure) {
    if (structure.contains("palette", Tag.TAG_LIST)) {
      return structure.getList("palette", Tag.TAG_COMPOUND);
    }
    if (structure.contains("palettes", Tag.TAG_LIST)) {
      ListTag palettes = structure.getList("palettes", Tag.TAG_LIST);
      if (!palettes.isEmpty()) {
        return palettes.getList(0);
      }
    }
    return null;
  }
}
