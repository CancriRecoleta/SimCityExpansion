package com.github.simcityexpansion.buildpack.convert;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.github.simcityexpansion.buildpack.LocalizedIOException;
import com.github.simcityexpansion.buildpack.model.StructureInfo;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Litematica 投影（.litematic）读取器。
 *
 * <p>格式要点：
 * <ul>
 *   <li>顶层：{@code Version / MinecraftDataVersion / Metadata / Regions}；</li>
 *   <li>每个 Region：{@code Position}、{@code Size}（分量可为负）、{@code BlockStatePalette}、
 *       {@code BlockStates}（long 数组，<b>紧密跨 long 边界</b>位打包，区别于 1.16+ 区块格式）、
 *       {@code TileEntities}（坐标为区域局部 x/y/z）；</li>
 *   <li>每条目位宽 {@code bits = max(2, ceil(log2(调色板大小)))}，
 *       索引顺序为 YZX（x 变化最快）。</li>
 * </ul>
 */
public final class LitematicReader {
  private LitematicReader() {}

  private static final Logger LOGGER = LoggerFactory.getLogger(LitematicReader.class);

  /** 合并后包围体积上限（格），超过则拒绝转换以防内存失控。 */
  private static final long MAX_MERGED_VOLUME = 16_000_000L;

  /** 仅读取 Metadata 摘要（不解包方块数据），供列表选中时快速展示。 */
  public static StructureInfo readInfo(Path path) throws IOException {
    return readInfo(NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap()));
  }

  /** 从已读出的根标签提取摘要。 */
  public static StructureInfo readInfo(CompoundTag root) {
    CompoundTag metadata = root.getCompound("Metadata");
    CompoundTag enclosing = metadata.getCompound("EnclosingSize");

    int[] preview = null;
    int previewSize = 0;
    if (metadata.contains("PreviewImageData", Tag.TAG_INT_ARRAY)) {
      int[] raw = metadata.getIntArray("PreviewImageData");
      int side = (int) Math.sqrt(raw.length);
      if (side > 0 && side * side == raw.length) {
        preview = raw;
        previewSize = side;
      }
    }

    String name = metadata.getString("Name");
    String author = metadata.getString("Author");
    return new StructureInfo(
        name.isBlank() ? null : name,
        author.isBlank() ? null : author,
        enclosing.getInt("x"), enclosing.getInt("y"), enclosing.getInt("z"),
        metadata.getInt("TotalBlocks"),
        metadata.getInt("TotalVolume"),
        metadata.getInt("RegionCount"),
        root.getInt("MinecraftDataVersion"),
        metadata.getLong("TimeCreated"),
        preview, previewSize);
  }

  /** 完整解析并把所有区域合并为一个包围盒内的 {@link NbtStructure}。 */
  public static NbtStructure readAndMerge(Path path) throws IOException {
    return readAndMerge(NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap()));
  }

  /** 完整解析（根标签版本）。 */
  public static NbtStructure readAndMerge(CompoundTag root) throws IOException {
    CompoundTag regionsTag = root.getCompound("Regions");
    if (regionsTag.isEmpty()) {
      throw new LocalizedIOException(Component.translatable("buildpack.error.no_regions"));
    }

    // 第一遍：归一化各区域包围范围，求全局包围盒。
    List<Region> regions = new ArrayList<>();
    int gMinX = Integer.MAX_VALUE;
    int gMinY = Integer.MAX_VALUE;
    int gMinZ = Integer.MAX_VALUE;
    int gMaxX = Integer.MIN_VALUE;
    int gMaxY = Integer.MIN_VALUE;
    int gMaxZ = Integer.MIN_VALUE;
    for (String regionName : regionsTag.getAllKeys()) {
      Region region = Region.of(regionsTag.getCompound(regionName));
      regions.add(region);
      gMinX = Math.min(gMinX, region.minX);
      gMinY = Math.min(gMinY, region.minY);
      gMinZ = Math.min(gMinZ, region.minZ);
      gMaxX = Math.max(gMaxX, region.minX + region.sizeX - 1);
      gMaxY = Math.max(gMaxY, region.minY + region.sizeY - 1);
      gMaxZ = Math.max(gMaxZ, region.minZ + region.sizeZ - 1);
    }
    int sizeX = gMaxX - gMinX + 1;
    int sizeY = gMaxY - gMinY + 1;
    int sizeZ = gMaxZ - gMinZ + 1;
    long volume = (long) sizeX * sizeY * sizeZ;
    if (volume <= 0 || volume > MAX_MERGED_VOLUME) {
      throw new LocalizedIOException(Component.translatable(
          "buildpack.error.volume_exceeded", sizeX + " x " + sizeY + " x " + sizeZ));
    }

    // 全局调色板：空气固定占索引 0，按规范化键去重。
    List<NbtStructure.PaletteEntry> palette = new ArrayList<>();
    Map<String, Integer> paletteIndex = new LinkedHashMap<>();
    palette.add(NbtStructure.PaletteEntry.AIR);
    paletteIndex.put(NbtStructure.PaletteEntry.AIR.canonicalKey(), 0);

    int[] grid = new int[(int) volume];
    Map<Long, CompoundTag> tileEntities = new HashMap<>();

    // 第二遍：逐区域解包并写入全局网格（后写区域覆盖先写）。
    for (Region region : regions) {
      mergeRegion(region, palette, paletteIndex, grid,
          tileEntities, gMinX, gMinY, gMinZ, sizeX, sizeZ);
    }

    // 输出 blocks 列表：包含空气条目，保证 SimuKraft 建造时按模板清空内部空间。
    List<NbtStructure.BlockEntry> blocks = new ArrayList<>((int) volume);
    for (int y = 0; y < sizeY; y++) {
      for (int z = 0; z < sizeZ; z++) {
        for (int x = 0; x < sizeX; x++) {
          int state = grid[(y * sizeZ + z) * sizeX + x];
          CompoundTag nbt = tileEntities.get(encode(x, y, z));
          blocks.add(new NbtStructure.BlockEntry(x, y, z, state, nbt));
        }
      }
    }

    return new NbtStructure(
        sizeX, sizeY, sizeZ, root.getInt("MinecraftDataVersion"), palette, blocks);
  }

  private static void mergeRegion(Region region,
      List<NbtStructure.PaletteEntry> palette, Map<String, Integer> paletteIndex,
      int[] grid, Map<Long, CompoundTag> tileEntities,
      int gMinX, int gMinY, int gMinZ, int sizeX, int sizeZ) throws IOException {
    // 局部调色板 → 全局调色板索引映射。
    int[] localToGlobal = new int[region.palette.size()];
    for (int i = 0; i < region.palette.size(); i++) {
      NbtStructure.PaletteEntry entry = region.palette.get(i);
      localToGlobal[i] = paletteIndex.computeIfAbsent(entry.canonicalKey(), key -> {
        palette.add(entry);
        return palette.size() - 1;
      });
    }

    int paletteSize = region.palette.size();
    int bits = Math.max(2, 32 - Integer.numberOfLeadingZeros(Math.max(paletteSize - 1, 1)));
    long mask = (1L << bits) - 1;
    long regionVolume = (long) region.sizeX * region.sizeY * region.sizeZ;
    long neededLongs = (regionVolume * bits + 63) / 64;
    if (region.blockStates.length < neededLongs) {
      throw new LocalizedIOException(Component.translatable(
          "buildpack.error.blockstates_truncated", neededLongs, region.blockStates.length));
    }

    int offsetX = region.minX - gMinX;
    int offsetY = region.minY - gMinY;
    int offsetZ = region.minZ - gMinZ;
    int badIndices = 0;
    for (int y = 0; y < region.sizeY; y++) {
      for (int z = 0; z < region.sizeZ; z++) {
        for (int x = 0; x < region.sizeX; x++) {
          long index = ((long) y * region.sizeZ + z) * region.sizeX + x;
          int local = unpack(region.blockStates, index, bits, mask);
          if (local >= paletteSize) {
            badIndices++;
            continue;
          }
          int gx = offsetX + x;
          int gy = offsetY + y;
          int gz = offsetZ + z;
          grid[(gy * sizeZ + gz) * sizeX + gx] = localToGlobal[local];
        }
      }
    }
    if (badIndices > 0) {
      LOGGER.warn("BuildPack: litematic 区域含 {} 个越界调色板索引，已按空气处理", badIndices);
    }

    // 方块实体：区域局部坐标 → 全局坐标，并移除坐标键（原版格式的 blocks[].nbt 不含坐标）。
    for (int i = 0; i < region.tileEntities.size(); i++) {
      CompoundTag te = region.tileEntities.getCompound(i).copy();
      int gx = offsetX + te.getInt("x");
      int gy = offsetY + te.getInt("y");
      int gz = offsetZ + te.getInt("z");
      te.remove("x");
      te.remove("y");
      te.remove("z");
      tileEntities.put(encode(gx, gy, gz), te);
    }
  }

  /** 紧密位打包解码：条目可跨越相邻两个 long。 */
  private static int unpack(long[] longs, long index, int bits, long mask) {
    long startBit = index * bits;
    int startLong = (int) (startBit >>> 6);
    int offset = (int) (startBit & 63);
    long value = longs[startLong] >>> offset;
    if (offset + bits > 64) {
      value |= longs[startLong + 1] << (64 - offset);
    }
    return (int) (value & mask);
  }

  private static long encode(int x, int y, int z) {
    return ((long) y << 42) | ((long) (z & 0x1FFFFF) << 21) | (x & 0x1FFFFF);
  }

  /** 一个已归一化（尺寸恒为正）的 litematic 区域。 */
  private record Region(int minX, int minY, int minZ, int sizeX, int sizeY, int sizeZ,
      List<NbtStructure.PaletteEntry> palette, long[] blockStates, ListTag tileEntities) {

    static Region of(CompoundTag tag) throws IOException {
      CompoundTag position = tag.getCompound("Position");
      CompoundTag size = tag.getCompound("Size");
      int px = position.getInt("x");
      int py = position.getInt("y");
      int pz = position.getInt("z");
      int sx = size.getInt("x");
      int sy = size.getInt("y");
      int sz = size.getInt("z");
      if (sx == 0 || sy == 0 || sz == 0) {
        throw new LocalizedIOException(Component.translatable("buildpack.error.zero_region"));
      }
      // Size 分量可为负：负值表示从 Position 往负方向延伸。
      int minX = sx >= 0 ? px : px + sx + 1;
      int minY = sy >= 0 ? py : py + sy + 1;
      int minZ = sz >= 0 ? pz : pz + sz + 1;

      ListTag paletteTag = tag.getList("BlockStatePalette", Tag.TAG_COMPOUND);
      List<NbtStructure.PaletteEntry> palette = new ArrayList<>(paletteTag.size());
      for (int i = 0; i < paletteTag.size(); i++) {
        CompoundTag state = paletteTag.getCompound(i);
        CompoundTag properties = state.contains("Properties", Tag.TAG_COMPOUND)
            ? state.getCompound("Properties")
            : null;
        palette.add(new NbtStructure.PaletteEntry(state.getString("Name"), properties));
      }
      if (palette.isEmpty()) {
        palette.add(NbtStructure.PaletteEntry.AIR);
      }

      return new Region(minX, minY, minZ, Math.abs(sx), Math.abs(sy), Math.abs(sz),
          palette, tag.getLongArray("BlockStates"),
          tag.getList("TileEntities", Tag.TAG_COMPOUND));
    }
  }
}
