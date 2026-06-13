package com.github.simcityexpansion.buildpack.convert;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

import com.github.simcityexpansion.buildpack.convert.NbtStructure.BlockEntry;
import com.github.simcityexpansion.buildpack.convert.NbtStructure.PaletteEntry;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 结构整体变换：绕 Y 轴旋转 90°、镜像、裁剪空白。坐标重映射的同时，
 * 用 {@link BlockState#rotate}/{@link BlockState#mirror} 把楼梯/朝向类方块的状态一并旋转/镜像，
 * 保证变换后朝向正确。产出新的 {@link NbtStructure}（原结构不变）。
 */
public final class StructureTransforms {
  private StructureTransforms() {}

  /** 绕 Y 轴顺时针旋转 90°（俯视）。 */
  public static NbtStructure rotateClockwise(NbtStructure s) {
    List<PaletteEntry> palette = mapPalette(s.palette, state -> state.rotate(Rotation.CLOCKWISE_90));
    List<BlockEntry> blocks = new ArrayList<>(s.blocks.size());
    for (BlockEntry b : s.blocks) {
      // (x,z) -> (sizeZ-1-z, x)
      blocks.add(new BlockEntry(s.sizeZ - 1 - b.z(), b.y(), b.x(), b.stateIndex(), b.nbt()));
    }
    return new NbtStructure(s.sizeZ, s.sizeY, s.sizeX, s.dataVersion, palette, blocks);
  }

  /** 沿 X 轴镜像（左右翻转）。 */
  public static NbtStructure mirrorX(NbtStructure s) {
    List<PaletteEntry> palette = mapPalette(s.palette, state -> state.mirror(Mirror.FRONT_BACK));
    List<BlockEntry> blocks = new ArrayList<>(s.blocks.size());
    for (BlockEntry b : s.blocks) {
      blocks.add(new BlockEntry(s.sizeX - 1 - b.x(), b.y(), b.z(), b.stateIndex(), b.nbt()));
    }
    return new NbtStructure(s.sizeX, s.sizeY, s.sizeZ, s.dataVersion, palette, blocks);
  }

  /** 沿 Z 轴镜像（前后翻转）。 */
  public static NbtStructure mirrorZ(NbtStructure s) {
    List<PaletteEntry> palette = mapPalette(s.palette, state -> state.mirror(Mirror.LEFT_RIGHT));
    List<BlockEntry> blocks = new ArrayList<>(s.blocks.size());
    for (BlockEntry b : s.blocks) {
      blocks.add(new BlockEntry(b.x(), b.y(), s.sizeZ - 1 - b.z(), b.stateIndex(), b.nbt()));
    }
    return new NbtStructure(s.sizeX, s.sizeY, s.sizeZ, s.dataVersion, palette, blocks);
  }

  /** 绕 Y 轴逆时针旋转 90°（等价于顺时针 3 次）。 */
  public static NbtStructure rotateCounterClockwise(NbtStructure s) {
    return rotateClockwise(rotateClockwise(rotateClockwise(s)));
  }

  /** 把某方块 id 的全部实例替换为另一方块 id（取目标在调色板里的首个变体）。 */
  public static NbtStructure replaceBlock(NbtStructure s, String fromId, String toId) {
    int target = -1;
    for (int i = 0; i < s.palette.size(); i++) {
      if (s.palette.get(i).blockName().equals(toId)) {
        target = i;
        break;
      }
    }
    if (target < 0 || fromId.equals(toId)) {
      return s;
    }
    boolean[] match = new boolean[s.palette.size()];
    for (int i = 0; i < match.length; i++) {
      match[i] = s.palette.get(i).blockName().equals(fromId);
    }
    List<BlockEntry> blocks = new ArrayList<>(s.blocks.size());
    for (BlockEntry b : s.blocks) {
      int idx = b.stateIndex();
      if (idx >= 0 && idx < match.length && match[idx]) {
        blocks.add(new BlockEntry(b.x(), b.y(), b.z(), target, null));
      } else {
        blocks.add(b);
      }
    }
    return new NbtStructure(s.sizeX, s.sizeY, s.sizeZ, s.dataVersion, s.palette, blocks);
  }

  /** 镂空：只保留至少有一面贴空气/边界的方块（去掉被完全包裹的内部方块）。 */
  public static NbtStructure hollow(NbtStructure s) {
    boolean[] air = new boolean[s.palette.size()];
    for (int i = 0; i < air.length; i++) {
      air[i] = s.palette.get(i).isAir();
    }
    boolean[] solid = new boolean[s.sizeX * s.sizeY * s.sizeZ];
    for (BlockEntry b : s.blocks) {
      if (!isAir(b, air) && inBounds(b, s)) {
        solid[(b.y() * s.sizeZ + b.z()) * s.sizeX + b.x()] = true;
      }
    }
    List<BlockEntry> blocks = new ArrayList<>();
    for (BlockEntry b : s.blocks) {
      if (isAir(b, air) || !inBounds(b, s)) {
        continue;
      }
      if (isExposed(solid, b.x(), b.y(), b.z(), s)) {
        blocks.add(b);
      }
    }
    return new NbtStructure(s.sizeX, s.sizeY, s.sizeZ, s.dataVersion, s.palette, blocks);
  }

  private static boolean inBounds(BlockEntry b, NbtStructure s) {
    return b.x() >= 0 && b.x() < s.sizeX && b.y() >= 0 && b.y() < s.sizeY
        && b.z() >= 0 && b.z() < s.sizeZ;
  }

  private static boolean isExposed(boolean[] solid, int x, int y, int z, NbtStructure s) {
    return !solidAt(solid, x - 1, y, z, s) || !solidAt(solid, x + 1, y, z, s)
        || !solidAt(solid, x, y - 1, z, s) || !solidAt(solid, x, y + 1, z, s)
        || !solidAt(solid, x, y, z - 1, s) || !solidAt(solid, x, y, z + 1, s);
  }

  private static boolean solidAt(boolean[] solid, int x, int y, int z, NbtStructure s) {
    if (x < 0 || x >= s.sizeX || y < 0 || y >= s.sizeY || z < 0 || z >= s.sizeZ) {
      return false;
    }
    return solid[(y * s.sizeZ + z) * s.sizeX + x];
  }

  /** 删除某方块 id 的全部实例（含其所有状态变体）。 */
  public static NbtStructure removeBlock(NbtStructure s, String blockId) {
    boolean[] match = new boolean[s.palette.size()];
    for (int i = 0; i < match.length; i++) {
      match[i] = s.palette.get(i).blockName().equals(blockId);
    }
    List<BlockEntry> blocks = new ArrayList<>();
    for (BlockEntry b : s.blocks) {
      if (b.stateIndex() >= 0 && b.stateIndex() < match.length && match[b.stateIndex()]) {
        continue;
      }
      blocks.add(b);
    }
    return new NbtStructure(s.sizeX, s.sizeY, s.sizeZ, s.dataVersion, s.palette, blocks);
  }

  /** 裁掉外围全空气的边距，并丢弃空气方块，把包围盒收紧到非空气范围。 */
  public static NbtStructure crop(NbtStructure s) {
    boolean[] air = new boolean[s.palette.size()];
    for (int i = 0; i < air.length; i++) {
      air[i] = s.palette.get(i).isAir();
    }
    int minX = Integer.MAX_VALUE;
    int minY = Integer.MAX_VALUE;
    int minZ = Integer.MAX_VALUE;
    int maxX = Integer.MIN_VALUE;
    int maxY = Integer.MIN_VALUE;
    int maxZ = Integer.MIN_VALUE;
    for (BlockEntry b : s.blocks) {
      if (isAir(b, air)) {
        continue;
      }
      minX = Math.min(minX, b.x());
      maxX = Math.max(maxX, b.x());
      minY = Math.min(minY, b.y());
      maxY = Math.max(maxY, b.y());
      minZ = Math.min(minZ, b.z());
      maxZ = Math.max(maxZ, b.z());
    }
    if (maxX < minX) {
      return s;
    }
    List<BlockEntry> blocks = new ArrayList<>();
    for (BlockEntry b : s.blocks) {
      if (isAir(b, air)) {
        continue;
      }
      blocks.add(new BlockEntry(b.x() - minX, b.y() - minY, b.z() - minZ, b.stateIndex(), b.nbt()));
    }
    return new NbtStructure(maxX - minX + 1, maxY - minY + 1, maxZ - minZ + 1,
        s.dataVersion, s.palette, blocks);
  }

  private static boolean isAir(BlockEntry b, boolean[] air) {
    return b.stateIndex() < 0 || b.stateIndex() >= air.length || air[b.stateIndex()];
  }

  /** 对调色板每个非空气项解析为 BlockState、应用变换、再序列化回 name+properties。 */
  private static List<PaletteEntry> mapPalette(List<PaletteEntry> palette, UnaryOperator<BlockState> op) {
    HolderGetter<Block> lookup = BuiltInRegistries.BLOCK.asLookup();
    List<PaletteEntry> out = new ArrayList<>(palette.size());
    for (PaletteEntry entry : palette) {
      if (entry.isAir()) {
        out.add(entry);
        continue;
      }
      try {
        CompoundTag in = new CompoundTag();
        in.putString("Name", entry.blockName());
        if (entry.properties() != null) {
          in.put("Properties", entry.properties());
        }
        BlockState state = op.apply(NbtUtils.readBlockState(lookup, in));
        CompoundTag serialized = NbtUtils.writeBlockState(state);
        CompoundTag props = serialized.contains("Properties")
            ? serialized.getCompound("Properties") : null;
        out.add(new PaletteEntry(serialized.getString("Name"), props));
      } catch (RuntimeException ignored) {
        out.add(entry);
      }
    }
    return out;
  }
}
