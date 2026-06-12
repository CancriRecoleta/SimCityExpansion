package com.github.simcityexpansion.buildpack.convert;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.github.simcityexpansion.buildpack.LocalizedIOException;
import com.github.simcityexpansion.buildpack.model.StructureInfo;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;

/**
 * 原版 NBT 结构模板读取器。
 *
 * <p>兼容 SimuKraft 同样认可的「{@code Schematic} 包装层」：若根标签没有 {@code blocks}
 * 但有 {@code Schematic} 子 compound，则解开一层后再按原版格式解析。
 */
public final class StructureNbtReader {
  private StructureNbtReader() {}

  /** 从文件读取（gzip 压缩的 NBT）。 */
  public static NbtStructure read(Path path) throws IOException {
    return read(NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap()));
  }

  /** 从已读出的根标签解析。 */
  public static NbtStructure read(CompoundTag root) throws IOException {
    if (!root.contains("blocks", Tag.TAG_LIST) && root.contains("Schematic", Tag.TAG_COMPOUND)) {
      root = root.getCompound("Schematic");
    }
    if (!root.contains("blocks", Tag.TAG_LIST) || !root.contains("palette", Tag.TAG_LIST)) {
      throw new LocalizedIOException(Component.translatable("buildpack.error.invalid_nbt"));
    }

    ListTag sizeTag = root.getList("size", Tag.TAG_INT);
    int sizeX = sizeTag.size() > 0 ? sizeTag.getInt(0) : 0;
    int sizeY = sizeTag.size() > 1 ? sizeTag.getInt(1) : 0;
    int sizeZ = sizeTag.size() > 2 ? sizeTag.getInt(2) : 0;

    ListTag paletteTag = root.getList("palette", Tag.TAG_COMPOUND);
    List<NbtStructure.PaletteEntry> palette = new ArrayList<>(paletteTag.size());
    for (int i = 0; i < paletteTag.size(); i++) {
      CompoundTag state = paletteTag.getCompound(i);
      CompoundTag properties = state.contains("Properties", Tag.TAG_COMPOUND)
          ? state.getCompound("Properties")
          : null;
      palette.add(new NbtStructure.PaletteEntry(state.getString("Name"), properties));
    }

    ListTag blocksTag = root.getList("blocks", Tag.TAG_COMPOUND);
    List<NbtStructure.BlockEntry> blocks = new ArrayList<>(blocksTag.size());
    for (int i = 0; i < blocksTag.size(); i++) {
      CompoundTag block = blocksTag.getCompound(i);
      ListTag pos = block.getList("pos", Tag.TAG_INT);
      if (pos.size() < 3) {
        continue;
      }
      CompoundTag nbt = block.contains("nbt", Tag.TAG_COMPOUND) ? block.getCompound("nbt") : null;
      blocks.add(new NbtStructure.BlockEntry(
          pos.getInt(0), pos.getInt(1), pos.getInt(2), block.getInt("state"), nbt));
    }

    return new NbtStructure(sizeX, sizeY, sizeZ, root.getInt("DataVersion"), palette, blocks);
  }

  /** 生成只读摘要（原版 .nbt 无内嵌名称/作者/预览）。 */
  public static StructureInfo summarize(NbtStructure structure) {
    return new StructureInfo(null, null,
        structure.sizeX, structure.sizeY, structure.sizeZ,
        structure.countNonAir(), structure.volume(), 1, structure.dataVersion, null, 0);
  }
}
