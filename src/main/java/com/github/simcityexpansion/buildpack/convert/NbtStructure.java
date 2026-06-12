package com.github.simcityexpansion.buildpack.convert;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

import net.minecraft.nbt.CompoundTag;
import org.jetbrains.annotations.Nullable;

/**
 * 原版 NBT 结构格式的内存中间模型，也是 litematic 转换的目标表示。
 *
 * <p>与磁盘格式一一对应：size（三轴尺寸）、palette（方块状态调色板）、
 * blocks（坐标 + 调色板索引 + 可选方块实体数据）。
 */
public final class NbtStructure {

  /**
   * 调色板条目。
   *
   * @param blockName 方块 id（如 {@code minecraft:oak_stairs}）
   * @param properties 方块状态属性（键值均为字符串的 compound；无属性时为 null）
   */
  public record PaletteEntry(String blockName, @Nullable CompoundTag properties) {

    /** 空气条目。 */
    public static final PaletteEntry AIR = new PaletteEntry("minecraft:air", null);

    /**
     * 调色板去重键：{@code name[k1=v1,k2=v2]}，属性键排序保证同一状态键唯一
     * （CompoundTag 内部为哈希表，toString 顺序不可靠）。
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

    /** 是否为空气。 */
    public boolean isAir() {
      return "minecraft:air".equals(blockName) || "air".equals(blockName);
    }
  }

  /**
   * 方块条目（坐标为结构内局部坐标，自 0 起）。
   *
   * @param nbt 方块实体数据（不含坐标键；无则为 null）
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

  /** 包围体积。 */
  public long volume() {
    return (long) sizeX * sizeY * sizeZ;
  }

  /** 非空气方块数。 */
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
