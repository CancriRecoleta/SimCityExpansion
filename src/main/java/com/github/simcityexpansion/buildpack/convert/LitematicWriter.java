package com.github.simcityexpansion.buildpack.convert;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.github.simcityexpansion.buildpack.convert.NbtStructure.BlockEntry;
import com.github.simcityexpansion.buildpack.convert.NbtStructure.PaletteEntry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;

/**
 * Writes an {@link NbtStructure} as a Litematica projection (.litematic): single region, tightly
 * bit-packed BlockStates, and metadata. This is the inverse of {@link LitematicReader} (bit packing
 * and YZX order match exactly).
 */
public final class LitematicWriter {
  private LitematicWriter() {}

  /** Litematic schema version (the reader does not validate the exact value; a common value is used). */
  private static final int SCHEMATIC_VERSION = 6;

  /** Writes the structure as a .litematic file. */
  public static void write(NbtStructure s, String name, String author, Path target) throws IOException {
    int sizeX = s.sizeX;
    int sizeY = s.sizeY;
    int sizeZ = s.sizeZ;
    int volume = sizeX * sizeY * sizeZ;

    // Palette: ensure air is present as the grid default.
    List<PaletteEntry> palette = new ArrayList<>(s.palette);
    int airIndex = -1;
    for (int i = 0; i < palette.size(); i++) {
      if (palette.get(i).isAir()) {
        airIndex = i;
        break;
      }
    }
    if (airIndex < 0) {
      palette.add(PaletteEntry.AIR);
      airIndex = palette.size() - 1;
    }

    int[] grid = new int[volume];
    Arrays.fill(grid, airIndex);
    for (BlockEntry b : s.blocks) {
      if (b.x() < 0 || b.x() >= sizeX || b.y() < 0 || b.y() >= sizeY || b.z() < 0 || b.z() >= sizeZ) {
        continue;
      }
      int idx = b.stateIndex();
      grid[(b.y() * sizeZ + b.z()) * sizeX + b.x()] = idx < 0 || idx >= palette.size() ? airIndex : idx;
    }

    boolean[] air = new boolean[palette.size()];
    for (int i = 0; i < air.length; i++) {
      air[i] = palette.get(i).isAir();
    }
    long totalBlocks = 0;
    for (int cell : grid) {
      if (!air[cell]) {
        totalBlocks++;
      }
    }

    // Bit packing (mirrors the reader's unpack: bits >= 2, YZX order, may span long boundaries).
    int bits = Math.max(2, 32 - Integer.numberOfLeadingZeros(Math.max(palette.size() - 1, 1)));
    long mask = (1L << bits) - 1;
    long[] longs = new long[(int) (((long) volume * bits + 63) / 64)];
    for (int i = 0; i < volume; i++) {
      long value = grid[i] & mask;
      long startBit = (long) i * bits;
      int startLong = (int) (startBit >>> 6);
      int offset = (int) (startBit & 63);
      longs[startLong] |= value << offset;
      if (offset + bits > 64) {
        longs[startLong + 1] |= value >>> (64 - offset);
      }
    }

    ListTag paletteTag = new ListTag();
    for (PaletteEntry entry : palette) {
      CompoundTag state = new CompoundTag();
      state.putString("Name", entry.blockName());
      if (entry.properties() != null && !entry.properties().isEmpty()) {
        state.put("Properties", entry.properties().copy());
      }
      paletteTag.add(state);
    }

    CompoundTag region = new CompoundTag();
    region.put("Position", xyz(0, 0, 0));
    region.put("Size", xyz(sizeX, sizeY, sizeZ));
    region.put("BlockStatePalette", paletteTag);
    region.putLongArray("BlockStates", longs);
    region.put("TileEntities", new ListTag());
    region.put("Entities", new ListTag());
    region.put("PendingBlockTicks", new ListTag());
    region.put("PendingFluidTicks", new ListTag());

    CompoundTag regions = new CompoundTag();
    regions.put("main", region);

    long now = System.currentTimeMillis();
    CompoundTag metadata = new CompoundTag();
    metadata.putString("Name", name);
    metadata.putString("Author", author == null ? "" : author);
    metadata.putString("Description", "");
    metadata.putInt("RegionCount", 1);
    metadata.putInt("TotalVolume", volume);
    metadata.putInt("TotalBlocks", (int) Math.min(totalBlocks, Integer.MAX_VALUE));
    metadata.putLong("TimeCreated", now);
    metadata.putLong("TimeModified", now);
    metadata.put("EnclosingSize", xyz(sizeX, sizeY, sizeZ));

    CompoundTag root = new CompoundTag();
    root.putInt("Version", SCHEMATIC_VERSION);
    root.putInt("MinecraftDataVersion", s.dataVersion);
    root.put("Metadata", metadata);
    root.put("Regions", regions);

    NbtIo.writeCompressed(root, target);
  }

  private static CompoundTag xyz(int x, int y, int z) {
    CompoundTag tag = new CompoundTag();
    tag.putInt("x", x);
    tag.putInt("y", y);
    tag.putInt("z", z);
    return tag;
  }
}
