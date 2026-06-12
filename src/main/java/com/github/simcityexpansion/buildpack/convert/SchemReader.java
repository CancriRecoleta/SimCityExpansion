package com.github.simcityexpansion.buildpack.convert;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.github.simcityexpansion.buildpack.LocalizedIOException;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;

/**
 * Sponge 原理图（WorldEdit {@code .schem}）读取器，支持 v1/v2 与 v3。
 *
 * <p>格式要点：
 * <ul>
 *   <li>v1/v2：根标签含 {@code Version / DataVersion / Width / Height / Length /
 *       Palette（状态串 → id 的 compound）/ BlockData（varint 字节数组）/ BlockEntities}；</li>
 *   <li>v3：包裹在根的 {@code Schematic} 子 compound 中，方块数据移入
 *       {@code Blocks{Palette, Data, BlockEntities}}，方块实体的附加数据嵌在 {@code Data} 里；</li>
 *   <li>索引顺序 YZX（x 最快）：{@code i = (y * length + z) * width + x}；</li>
 *   <li>调色板键形如 {@code minecraft:oak_stairs[facing=east,half=bottom]}。</li>
 * </ul>
 */
public final class SchemReader {
  private SchemReader() {}

  /** 读取 .schem 并转换为原版结构中间模型。 */
  public static NbtStructure read(Path path) throws IOException {
    return read(NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap()));
  }

  /** 从已读出的根标签解析。 */
  public static NbtStructure read(CompoundTag root) throws IOException {
    boolean v3 = root.contains("Schematic", Tag.TAG_COMPOUND);
    CompoundTag schem = v3 ? root.getCompound("Schematic") : root;
    int version = schem.getInt("Version");
    if (v3 && version != 3) {
      throw new LocalizedIOException(
          Component.translatable("buildpack.error.schem_version", version));
    }

    int width = schem.getShort("Width") & 0xFFFF;
    int height = schem.getShort("Height") & 0xFFFF;
    int length = schem.getShort("Length") & 0xFFFF;
    if (width == 0 || height == 0 || length == 0) {
      throw new LocalizedIOException(Component.translatable("buildpack.error.invalid_schem"));
    }

    CompoundTag paletteTag;
    byte[] data;
    ListTag blockEntities;
    if (v3) {
      CompoundTag blocks = schem.getCompound("Blocks");
      paletteTag = blocks.getCompound("Palette");
      data = blocks.getByteArray("Data");
      blockEntities = blocks.getList("BlockEntities", Tag.TAG_COMPOUND);
    } else {
      paletteTag = schem.getCompound("Palette");
      data = schem.getByteArray("BlockData");
      blockEntities = schem.contains("BlockEntities", Tag.TAG_LIST)
          ? schem.getList("BlockEntities", Tag.TAG_COMPOUND)
          : schem.getList("TileEntities", Tag.TAG_COMPOUND);
    }
    if (paletteTag.isEmpty() || data.length == 0) {
      throw new LocalizedIOException(Component.translatable("buildpack.error.invalid_schem"));
    }

    // 调色板：状态串 → id，按 id 还原为列表（空洞补空气）。
    int maxId = -1;
    Map<Integer, NbtStructure.PaletteEntry> byId = new HashMap<>();
    for (String stateString : paletteTag.getAllKeys()) {
      int id = paletteTag.getInt(stateString);
      byId.put(id, parseState(stateString));
      maxId = Math.max(maxId, id);
    }
    List<NbtStructure.PaletteEntry> palette = new ArrayList<>(maxId + 1);
    for (int id = 0; id <= maxId; id++) {
      palette.add(byId.getOrDefault(id, NbtStructure.PaletteEntry.AIR));
    }

    // 方块实体：Pos[x,y,z]；v3 附加数据在 Data 子标签，v1/v2 直接内联。
    Map<Long, CompoundTag> tileEntities = new HashMap<>();
    for (int i = 0; i < blockEntities.size(); i++) {
      CompoundTag entity = blockEntities.getCompound(i);
      int[] pos = entity.getIntArray("Pos");
      if (pos.length < 3) {
        continue;
      }
      CompoundTag nbt;
      if (v3) {
        nbt = entity.getCompound("Data").copy();
      } else {
        nbt = entity.copy();
        nbt.remove("Pos");
        nbt.remove("Id");
      }
      nbt.putString("id", entity.getString("Id"));
      tileEntities.put(encode(pos[0], pos[1], pos[2]), nbt);
    }

    // BlockData：varint 流，YZX 顺序展开；包含空气，保证建造时清空内部空间。
    long total = (long) width * height * length;
    List<NbtStructure.BlockEntry> blocks = new ArrayList<>((int) total);
    int cursor = 0;
    for (long i = 0; i < total; i++) {
      int id = 0;
      int shift = 0;
      while (true) {
        if (cursor >= data.length) {
          throw new LocalizedIOException(Component.translatable("buildpack.error.invalid_schem"));
        }
        byte b = data[cursor++];
        id |= (b & 0x7F) << shift;
        if ((b & 0x80) == 0) {
          break;
        }
        shift += 7;
      }
      int x = (int) (i % width);
      int z = (int) ((i / width) % length);
      int y = (int) (i / ((long) width * length));
      int state = id <= maxId && id >= 0 ? id : 0;
      blocks.add(new NbtStructure.BlockEntry(x, y, z, state, tileEntities.get(encode(x, y, z))));
    }

    return new NbtStructure(width, height, length, schem.getInt("DataVersion"), palette, blocks);
  }

  /** 解析 {@code ns:name[k=v,...]} 形式的状态串。 */
  private static NbtStructure.PaletteEntry parseState(String stateString) {
    int bracket = stateString.indexOf('[');
    if (bracket < 0) {
      return new NbtStructure.PaletteEntry(stateString, null);
    }
    String name = stateString.substring(0, bracket);
    CompoundTag properties = new CompoundTag();
    String body = stateString.substring(bracket + 1, stateString.length() - 1);
    for (String pair : body.split(",")) {
      int eq = pair.indexOf('=');
      if (eq > 0) {
        properties.putString(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
      }
    }
    return new NbtStructure.PaletteEntry(name, properties.isEmpty() ? null : properties);
  }

  private static long encode(int x, int y, int z) {
    return ((long) y << 42) | ((long) (z & 0x1FFFFF) << 21) | (x & 0x1FFFFF);
  }
}
