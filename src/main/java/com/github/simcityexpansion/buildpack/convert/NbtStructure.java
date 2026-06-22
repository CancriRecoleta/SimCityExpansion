package com.github.simcityexpansion.buildpack.convert;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

import net.minecraft.nbt.CompoundTag;
import org.jetbrains.annotations.Nullable;

/**
 * In-memory intermediate model of the vanilla NBT structure format, and the target representation
 * for litematic conversion.
 *
 * <p>Maps one-to-one with the on-disk format: size (three-axis dimensions), palette (block-state
 * palette), and blocks (coordinates + palette index + optional block entity data).
 */
public final class NbtStructure {

  /**
   * A palette entry.
   *
   * @param blockName block ID (e.g., {@code minecraft:oak_stairs})
   * @param properties block-state properties (compound with string keys and values; null when no properties)
   */
  public record PaletteEntry(String blockName, @Nullable CompoundTag properties) {

    /** Air palette entry. */
    public static final PaletteEntry AIR = new PaletteEntry("minecraft:air", null);

    /**
     * Deduplication key for the palette: {@code name[k1=v1,k2=v2]} with property keys sorted to
     * guarantee a unique key per state (CompoundTag is internally a hash map whose toString order
     * is unreliable).
     */
    public String canonicalKey() {
      if (properties == null || properties.isEmpty()) {
        return blockName;
      }
      StringBuilder key = new StringBuilder(blockName).append('[');
      boolean first = true;
      for (String property : new TreeSet<>(properties.getAllKeys())) {
        if (!first) {
          key.append(',');
        }
        key.append(property).append('=').append(properties.getString(property));
        first = false;
      }
      return key.append(']').toString();
    }

    /** Returns true if this entry represents air. */
    public boolean isAir() {
      return "minecraft:air".equals(blockName) || "air".equals(blockName);
    }
  }

  /**
   * A block entry (coordinates are structure-local, starting at 0).
   *
   * @param nbt block entity data (without coordinate keys; null if absent)
   */
  public record BlockEntry(int x, int y, int z, int stateIndex, @Nullable CompoundTag nbt) {}

  public final int sizeX;
  public final int sizeY;
  public final int sizeZ;
  public final int dataVersion;
  public final List<PaletteEntry> palette;
  public final List<BlockEntry> blocks;

  public NbtStructure(int sizeX, int sizeY, int sizeZ, int dataVersion,
      List<PaletteEntry> palette, List<BlockEntry> blocks) {
    this.sizeX = sizeX;
    this.sizeY = sizeY;
    this.sizeZ = sizeZ;
    this.dataVersion = dataVersion;
    this.palette = palette;
    this.blocks = blocks;
  }

  /** Returns the bounding volume. */
  public long volume() {
    return (long) sizeX * sizeY * sizeZ;
  }

  /** Returns the number of non-air blocks. */
  public long countNonAir() {
    List<Boolean> airIndex = new ArrayList<>(palette.size());
    for (PaletteEntry entry : palette) {
      airIndex.add(entry.isAir());
    }
    long count = 0;
    for (BlockEntry block : blocks) {
      if (block.stateIndex() >= 0 && block.stateIndex() < airIndex.size()
          && !airIndex.get(block.stateIndex())) {
        count++;
      }
    }
    return count;
  }
}
