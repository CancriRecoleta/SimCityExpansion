package com.github.simcityexpansion.buildpack.convert;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;

import com.github.simcityexpansion.buildpack.BuildPack;
import com.github.simcityexpansion.buildpack.LocalizedIOException;
import com.github.simcityexpansion.buildpack.model.StructureFormat;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;

/**
 * Converts an in-memory structure (vanilla {@code .nbt}, Litematica, or Sponge schematic) into the
 * vanilla NBT tag SimuKraft builds from, upgrading old structures through the DataFixer. Shared by
 * the pack <b>activation</b> flow (writes to this mod's cache) and available for reuse by the
 * install flow; keeps the convert/upgrade switch in one place.
 *
 * @see com.github.simcityexpansion.buildpack.install.BuildingInstaller
 * @see com.github.simcityexpansion.buildpack.install.PackInstaller
 */
public final class StructureMaterializer {
  private StructureMaterializer() {}

  /** The converted vanilla tag plus the structure's dimensions (for the {@code .sk size:} field). */
  public record Result(CompoundTag nbt, int sizeX, int sizeY, int sizeZ) {}

  /**
   * Reads and converts the given compressed structure bytes to a vanilla NBT tag. Conversion
   * warnings (missing blocks, DataVersion notes, validation) are appended to {@code messages}.
   */
  public static Result toVanilla(byte[] structureBytes, StructureFormat format,
      List<Component> messages) throws IOException {
    CompoundTag root = NbtIo.readCompressed(new ByteArrayInputStream(structureBytes),
        NbtAccounter.create(BuildPack.MAX_STRUCTURE_NBT_BYTES));
    switch (format) {
      case LITEMATIC, SCHEM -> {
        NbtStructure structure = format == StructureFormat.LITEMATIC
            ? LitematicReader.readAndMerge(root)
            : SchemReader.read(root);
        messages.addAll(LitematicConverter.validate(structure));
        StructureUpgrader.warnMissingBlocks(structure, messages);
        CompoundTag tag = StructureUpgrader.upgradeToCurrent(
            StructureNbtWriter.toTag(structure), structure.dataVersion, messages);
        return new Result(tag, structure.sizeX, structure.sizeY, structure.sizeZ);
      }
      case VANILLA_NBT -> {
        // Upgrade the raw tag so fields not modeled here (e.g. entities) are preserved.
        NbtStructure structure = StructureNbtReader.read(root);
        StructureUpgrader.warnMissingBlocks(structure, messages);
        CompoundTag upgraded = StructureUpgrader.upgradeToCurrent(
            root, root.getInt("DataVersion"), messages);
        return new Result(upgraded, structure.sizeX, structure.sizeY, structure.sizeZ);
      }
      default -> throw new LocalizedIOException(
          Component.translatable("buildpack.error.unknown_format"));
    }
  }
}
