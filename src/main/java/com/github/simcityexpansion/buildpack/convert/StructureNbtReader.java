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
 * Vanilla NBT structure template reader.
 *
 * <p>Compatible with the "wrapper layer" also accepted by SimuKraft: if the root tag lacks
 * {@code blocks} but contains a {@code Schematic} sub-compound, the wrapper is unwrapped
 * before parsing in vanilla format.
 */
public final class StructureNbtReader {
  private StructureNbtReader() {}

  /** Reads from a file (gzip-compressed NBT). */
  public static NbtStructure read(Path path) throws IOException {
    return read(NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap()));
  }

  /** Parses from an already-read root tag. */
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

    ListTag entitiesTag = root.getList("entities", Tag.TAG_COMPOUND);
    List<NbtStructure.EntityEntry> entities = new ArrayList<>(entitiesTag.size());
    for (int i = 0; i < entitiesTag.size(); i++) {
      CompoundTag entity = entitiesTag.getCompound(i);
      ListTag pos = entity.getList("pos", Tag.TAG_DOUBLE);
      if (pos.size() < 3 || !entity.contains("nbt", Tag.TAG_COMPOUND)) {
        continue;
      }
      entities.add(new NbtStructure.EntityEntry(
          pos.getDouble(0), pos.getDouble(1), pos.getDouble(2), entity.getCompound("nbt")));
    }

    return new NbtStructure(sizeX, sizeY, sizeZ, root.getInt("DataVersion"),
        palette, blocks, entities);
  }

  /** Generates a read-only summary (vanilla .nbt / .schem files have no embedded name, author, creation time, or preview). */
  public static StructureInfo summarize(NbtStructure structure) {
    return new StructureInfo(null, null,
        structure.sizeX, structure.sizeY, structure.sizeZ,
        structure.countNonAir(), structure.volume(), 1, structure.dataVersion, 0L, null, 0);
  }
}
