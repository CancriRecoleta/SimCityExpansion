package com.github.simcityexpansion.buildpack.convert;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.github.simcityexpansion.buildpack.I18nLog;
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
 * Reader for Litematica projections (.litematic).
 *
 * <p>Format highlights:
 * <ul>
 *   <li>Top level: {@code Version / MinecraftDataVersion / Metadata / Regions}.</li>
 *   <li>Each Region: {@code Position}, {@code Size} (components may be negative),
 *       {@code BlockStatePalette}, {@code BlockStates} (long array, <b>tightly packed across long
 *       boundaries</b>, unlike the 1.16+ chunk format), and {@code TileEntities} (coordinates are
 *       region-local x/y/z).</li>
 *   <li>Bits per entry: {@code bits = max(2, ceil(log2(palette size)))}; index order is YZX
 *       (x changes fastest).</li>
 * </ul>
 */
public final class LitematicReader {
  private LitematicReader() {}

  private static final Logger LOGGER = LoggerFactory.getLogger(LitematicReader.class);

  /** Maximum merged bounding volume in blocks; conversion is refused above this limit to prevent memory exhaustion. */
  private static final long MAX_MERGED_VOLUME = 16_000_000L;

  /** Reads only the Metadata summary (without unpacking block data) for fast display when an item is selected in a list. */
  public static StructureInfo readInfo(Path path) throws IOException {
    return readInfo(NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap()));
  }

  /** Extracts the summary from an already-read root tag. */
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

  /** Fully parses and merges all regions into a single {@link NbtStructure} within one bounding box. */
  public static NbtStructure readAndMerge(Path path) throws IOException {
    return readAndMerge(NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap()));
  }

  /** Fully parses from an already-read root tag. */
  public static NbtStructure readAndMerge(CompoundTag root) throws IOException {
    CompoundTag regionsTag = root.getCompound("Regions");
    if (regionsTag.isEmpty()) {
      throw new LocalizedIOException(Component.translatable("buildpack.error.no_regions"));
    }

    // First pass: normalize each region's bounding extent and compute the global bounding box.
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

    // Global palette: air is fixed at index 0; entries are deduplicated by canonical key.
    List<NbtStructure.PaletteEntry> palette = new ArrayList<>();
    Map<String, Integer> paletteIndex = new LinkedHashMap<>();
    palette.add(NbtStructure.PaletteEntry.AIR);
    paletteIndex.put(NbtStructure.PaletteEntry.AIR.canonicalKey(), 0);

    int[] grid = new int[(int) volume];
    Map<Long, CompoundTag> tileEntities = new HashMap<>();

    // Second pass: unpack each region and write into the global grid (later regions overwrite earlier ones).
    for (Region region : regions) {
      mergeRegion(region, palette, paletteIndex, grid,
          tileEntities, gMinX, gMinY, gMinZ, sizeX, sizeZ);
    }

    // Output blocks list: includes air entries so the builder clears the interior space according to the template.
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
    // Local palette to global palette index mapping.
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
      I18nLog.warn(LOGGER, "buildpack.log.litematic_bad_indices", badIndices);
    }

    // Block entities: convert region-local coordinates to global coordinates and remove the coordinate keys (vanilla format blocks[].nbt does not contain coordinates).
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

  /** Tightly packed bit-unpacking: an entry may span two adjacent longs. */
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

  /** A normalized litematic region (dimensions are always positive). */
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
      // Size components may be negative: a negative value means the region extends in the negative direction from Position.
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
