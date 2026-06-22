package com.github.simcityexpansion.buildpack.convert;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;

/** Writes an {@link NbtStructure} to a gzip-compressed vanilla NBT structure template file. */
public final class StructureNbtWriter {
  private StructureNbtWriter() {}

  /** Writes to the target path (overwrites any existing file). */
  public static void write(NbtStructure structure, Path target) throws IOException {
    writeTag(toTag(structure), target);
  }

  /** Writes an already-built (possibly DataFixer-upgraded) root tag directly. */
  public static void writeTag(CompoundTag root, Path target) throws IOException {
    if (target.getParent() != null) {
      Files.createDirectories(target.getParent());
    }
    NbtIo.writeCompressed(root, target);
  }

  /** Builds the root tag of a vanilla structure template. */
  public static CompoundTag toTag(NbtStructure structure) {
    CompoundTag root = new CompoundTag();

    ListTag size = new ListTag();
    size.add(IntTag.valueOf(structure.sizeX));
    size.add(IntTag.valueOf(structure.sizeY));
    size.add(IntTag.valueOf(structure.sizeZ));
    root.put("size", size);

    ListTag palette = new ListTag();
    for (NbtStructure.PaletteEntry entry : structure.palette) {
      CompoundTag state = new CompoundTag();
      state.putString("Name", entry.blockName());
      if (entry.properties() != null && !entry.properties().isEmpty()) {
        state.put("Properties", entry.properties().copy());
      }
      palette.add(state);
    }
    root.put("palette", palette);

    ListTag blocks = new ListTag();
    for (NbtStructure.BlockEntry entry : structure.blocks) {
      CompoundTag block = new CompoundTag();
      ListTag pos = new ListTag();
      pos.add(IntTag.valueOf(entry.x()));
      pos.add(IntTag.valueOf(entry.y()));
      pos.add(IntTag.valueOf(entry.z()));
      block.put("pos", pos);
      block.putInt("state", entry.stateIndex());
      if (entry.nbt() != null) {
        block.put("nbt", entry.nbt().copy());
      }
      blocks.add(block);
    }
    root.put("blocks", blocks);

    ListTag entities = new ListTag();
    for (NbtStructure.EntityEntry entry : structure.entities) {
      CompoundTag entity = new CompoundTag();
      ListTag pos = new ListTag();
      pos.add(DoubleTag.valueOf(entry.x()));
      pos.add(DoubleTag.valueOf(entry.y()));
      pos.add(DoubleTag.valueOf(entry.z()));
      entity.put("pos", pos);
      ListTag blockPos = new ListTag();
      blockPos.add(IntTag.valueOf((int) Math.floor(entry.x())));
      blockPos.add(IntTag.valueOf((int) Math.floor(entry.y())));
      blockPos.add(IntTag.valueOf((int) Math.floor(entry.z())));
      entity.put("blockPos", blockPos);
      entity.put("nbt", entry.nbt().copy());
      entities.add(entity);
    }
    root.put("entities", entities);
    root.putInt("DataVersion", structure.dataVersion);
    return root;
  }
}
