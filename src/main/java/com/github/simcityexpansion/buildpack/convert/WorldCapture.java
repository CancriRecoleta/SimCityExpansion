package com.github.simcityexpansion.buildpack.convert;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.github.simcityexpansion.buildpack.convert.NbtStructure.BlockEntry;
import com.github.simcityexpansion.buildpack.convert.NbtStructure.PaletteEntry;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Captures a bounding box region from the current world (save) into an {@link NbtStructure}:
 * reads block states cell by cell, deduplicates them into a palette (air is always at index 0),
 * and produces a complete grid blocks list.
 *
 * <p>Block entity data (chest contents, sign text, etc.) is not captured for now — building
 * blueprints typically want empty buildings; this can be added later.
 */
public final class WorldCapture {
  private WorldCapture() {}

  /** Captures all blocks (including air) within the inclusive range [min, max] as a structure. */
  public static NbtStructure capture(LevelReader level, BlockPos min, BlockPos max) {
    int sizeX = max.getX() - min.getX() + 1;
    int sizeY = max.getY() - min.getY() + 1;
    int sizeZ = max.getZ() - min.getZ() + 1;

    List<PaletteEntry> palette = new ArrayList<>();
    Map<String, Integer> paletteIndex = new LinkedHashMap<>();
    palette.add(PaletteEntry.AIR);
    paletteIndex.put(PaletteEntry.AIR.canonicalKey(), 0);

    List<BlockEntry> blocks = new ArrayList<>(sizeX * sizeY * sizeZ);
    BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
    for (int y = 0; y < sizeY; y++) {
      for (int z = 0; z < sizeZ; z++) {
        for (int x = 0; x < sizeX; x++) {
          cursor.set(min.getX() + x, min.getY() + y, min.getZ() + z);
          BlockState state = level.getBlockState(cursor);
          int index;
          if (state.isAir()) {
            index = 0;
          } else {
            PaletteEntry entry = toEntry(state);
            index = paletteIndex.computeIfAbsent(entry.canonicalKey(), key -> {
              palette.add(entry);
              return palette.size() - 1;
            });
          }
          blocks.add(new BlockEntry(x, y, z, index, null));
        }
      }
    }
    return new NbtStructure(sizeX, sizeY, sizeZ,
        SharedConstants.getCurrentVersion().getDataVersion().getVersion(), palette, blocks);
  }

  private static PaletteEntry toEntry(BlockState state) {
    CompoundTag tag = NbtUtils.writeBlockState(state);
    CompoundTag props = tag.contains("Properties") ? tag.getCompound("Properties") : null;
    return new PaletteEntry(tag.getString("Name"), props);
  }
}
