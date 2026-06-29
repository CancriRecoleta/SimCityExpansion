package com.github.simcityexpansion.buildpack.convert;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;

import com.github.simcityexpansion.buildpack.BuildPack;
import com.github.simcityexpansion.buildpack.model.StructureFormat;
import com.github.simcityexpansion.buildpack.model.StructureInfo;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

/**
 * Complete parse result from a single disk read: summary (info panel / metadata pre-fill) plus
 * block data (material list, top-down view, install conversion). Shared between the client UI and
 * server commands.
 *
 * @param info read-only summary
 * @param structure complete intermediate model
 */
public record ParsedStructure(StructureInfo info, NbtStructure structure) {

  /** Parses a structure file in the given format (gzip NBT is read only once). */
  public static ParsedStructure parse(Path path, StructureFormat format) throws IOException {
    return parse(NbtIo.readCompressed(
        path, NbtAccounter.create(BuildPack.MAX_STRUCTURE_NBT_BYTES)), format);
  }

  /** Parses from in-memory bytes (used for entries inside a zip build pack, avoiding a disk write). */
  public static ParsedStructure parse(byte[] gzipBytes, StructureFormat format)
      throws IOException {
    return parse(NbtIo.readCompressed(new ByteArrayInputStream(gzipBytes),
        NbtAccounter.create(BuildPack.MAX_STRUCTURE_NBT_BYTES)), format);
  }

  /** Parses from an already-read root tag. */
  public static ParsedStructure parse(CompoundTag root, StructureFormat format)
      throws IOException {
    return switch (format) {
      case LITEMATIC -> new ParsedStructure(
          LitematicReader.readInfo(root), LitematicReader.readAndMerge(root));
      case VANILLA_NBT -> {
        NbtStructure structure = StructureNbtReader.read(root);
        yield new ParsedStructure(StructureNbtReader.summarize(structure), structure);
      }
      case SCHEM -> {
        NbtStructure structure = SchemReader.read(root);
        yield new ParsedStructure(StructureNbtReader.summarize(structure), structure);
      }
    };
  }
}
