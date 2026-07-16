package com.github.simcityexpansion.buildpack.convert;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.UnaryOperator;

import com.github.simcityexpansion.buildpack.convert.NbtStructure.BlockEntry;
import com.github.simcityexpansion.buildpack.convert.NbtStructure.EntityEntry;
import com.github.simcityexpansion.buildpack.convert.NbtStructure.PaletteEntry;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import org.jetbrains.annotations.Nullable;

/**
 * Whole-structure transforms: 90° rotation around the Y axis, mirroring, and whitespace cropping.
 * Coordinate remapping is paired with {@link BlockState#rotate}/{@link BlockState#mirror} to
 * rotate/mirror the state of stairs and other facing/orientation blocks, ensuring correct
 * orientation after the transform. Produces a new {@link NbtStructure}; the original is unchanged.
 *
 * <p>Entities are carried through every transform: their relative position (and the {@code Pos},
 * {@code Rotation} yaw, and hanging-entity {@code TileX/Y/Z} tags) are remapped alongside the
 * blocks; region operations drop or translate the entities the region covers.
 */
public final class StructureTransforms {
  private StructureTransforms() {}

  /** Continuous-coordinate remap for entity positions. */
  @FunctionalInterface
  private interface PosMap {
    double[] apply(double x, double y, double z);
  }

  /** Block-cell remap for hanging-entity tile coordinates. */
  @FunctionalInterface
  private interface CellMap {
    int[] apply(int x, int y, int z);
  }

  /** Maximum bounding volume for the "fill" operation; larger structures are skipped to avoid excessive flood-fill time and memory use. */
  private static final int FILL_MAX_VOLUME = 2_000_000;

  /** Rotates 90° clockwise around the Y axis (top-down view). */
  public static NbtStructure rotateClockwise(NbtStructure s) {
    List<PaletteEntry> palette = mapPalette(s.palette, state -> state.rotate(Rotation.CLOCKWISE_90));
    List<BlockEntry> blocks = new ArrayList<>(s.blocks.size());
    for (BlockEntry b : s.blocks) {
      // (x,z) -> (sizeZ-1-z, x)
      blocks.add(new BlockEntry(s.sizeZ - 1 - b.z(), b.y(), b.x(), b.stateIndex(), b.nbt()));
    }
    List<EntityEntry> entities = mapEntities(s.entities,
        (x, y, z) -> new double[] {s.sizeZ - z, y, x},
        (x, y, z) -> new int[] {s.sizeZ - 1 - z, y, x},
        yaw -> yaw + 90.0f);
    return new NbtStructure(s.sizeZ, s.sizeY, s.sizeX, s.dataVersion, palette, blocks, entities);
  }

  /** Mirrors along the X axis (left-right flip). */
  public static NbtStructure mirrorX(NbtStructure s) {
    List<PaletteEntry> palette = mapPalette(s.palette, state -> state.mirror(Mirror.FRONT_BACK));
    List<BlockEntry> blocks = new ArrayList<>(s.blocks.size());
    for (BlockEntry b : s.blocks) {
      blocks.add(new BlockEntry(s.sizeX - 1 - b.x(), b.y(), b.z(), b.stateIndex(), b.nbt()));
    }
    List<EntityEntry> entities = mapEntities(s.entities,
        (x, y, z) -> new double[] {s.sizeX - x, y, z},
        (x, y, z) -> new int[] {s.sizeX - 1 - x, y, z},
        yaw -> -yaw);
    return new NbtStructure(s.sizeX, s.sizeY, s.sizeZ, s.dataVersion, palette, blocks, entities);
  }

  /** Mirrors along the Z axis (front-back flip). */
  public static NbtStructure mirrorZ(NbtStructure s) {
    List<PaletteEntry> palette = mapPalette(s.palette, state -> state.mirror(Mirror.LEFT_RIGHT));
    List<BlockEntry> blocks = new ArrayList<>(s.blocks.size());
    for (BlockEntry b : s.blocks) {
      blocks.add(new BlockEntry(b.x(), b.y(), s.sizeZ - 1 - b.z(), b.stateIndex(), b.nbt()));
    }
    List<EntityEntry> entities = mapEntities(s.entities,
        (x, y, z) -> new double[] {x, y, s.sizeZ - z},
        (x, y, z) -> new int[] {x, y, s.sizeZ - 1 - z},
        yaw -> 180.0f - yaw);
    return new NbtStructure(s.sizeX, s.sizeY, s.sizeZ, s.dataVersion, palette, blocks, entities);
  }

  /** Rotates 90° counter-clockwise around the Y axis (equivalent to three clockwise rotations). */
  public static NbtStructure rotateCounterClockwise(NbtStructure s) {
    return rotateClockwise(rotateClockwise(rotateClockwise(s)));
  }

  /** Replace every instance of {@code fromId} with {@code toId} (appended to the palette if absent). */
  public static NbtStructure replaceBlock(NbtStructure s, String fromId, String toId) {
    if (fromId.equals(toId)) {
      return s;
    }
    List<PaletteEntry> palette = s.palette;
    int target = -1;
    for (int i = 0; i < palette.size(); i++) {
      if (palette.get(i).blockName().equals(toId)) {
        target = i;
        break;
      }
    }
    if (target < 0) {
      palette = new ArrayList<>(s.palette);
      palette.add(new PaletteEntry(toId, null));
      target = palette.size() - 1;
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
    return new NbtStructure(s.sizeX, s.sizeY, s.sizeZ, s.dataVersion, palette, blocks, s.entities);
  }

  /** Outcome of a family swap: the result plus how much changed and which targets were missing. */
  public record SwapOutcome(NbtStructure structure, int changedBlocks, List<String> missingTargets) {}

  /**
   * Wood families whose names contain an underscore; guards the token matcher so swapping
   * {@code oak} leaves {@code dark_oak} blocks alone.
   */
  private static final Set<String> COMPOUND_FAMILIES = Set.of("dark_oak", "pale_oak");

  /**
   * Family swap: replaces the token sequence {@code from} inside every block id path with
   * {@code to} (e.g. {@code oak → spruce} maps planks/stairs/doors/fences in one step), keeping
   * each block-state property the target block also supports. Ids whose swapped form is not
   * registered are left unchanged and reported. With {@code mn}/{@code mx} non-null, only blocks
   * inside that inclusive region are remapped.
   */
  public static SwapOutcome swapFamily(NbtStructure s, String from, String to,
      @Nullable int[] mn, @Nullable int[] mx) {
    String fromToken = from.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
    String toToken = to.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
    if (fromToken.isEmpty() || toToken.isEmpty() || fromToken.equals(toToken)) {
      return new SwapOutcome(s, 0, List.of());
    }
    List<PaletteEntry> palette = new ArrayList<>(s.palette);
    int[] mapping = new int[s.palette.size()];
    List<String> missing = new ArrayList<>();
    for (int i = 0; i < s.palette.size(); i++) {
      mapping[i] = i;
      PaletteEntry entry = s.palette.get(i);
      if (entry.isAir()) {
        continue;
      }
      String swappedId = swapIdTokens(entry.blockName(), fromToken, toToken);
      if (swappedId == null) {
        continue;
      }
      ResourceLocation id = ResourceLocation.tryParse(swappedId);
      Block block = id == null ? null : BuiltInRegistries.BLOCK.getOptional(id).orElse(null);
      if (block == null) {
        if (!missing.contains(swappedId)) {
          missing.add(swappedId);
        }
        continue;
      }
      PaletteEntry swapped = new PaletteEntry(swappedId,
          retainSupportedProperties(block, entry.properties()));
      int existing = -1;
      for (int j = 0; j < palette.size(); j++) {
        if (palette.get(j).canonicalKey().equals(swapped.canonicalKey())) {
          existing = j;
          break;
        }
      }
      if (existing < 0) {
        palette.add(swapped);
        existing = palette.size() - 1;
      }
      mapping[i] = existing;
    }
    int changed = 0;
    List<BlockEntry> blocks = new ArrayList<>(s.blocks.size());
    for (BlockEntry b : s.blocks) {
      int idx = b.stateIndex();
      boolean inScope = mn == null || mx == null
          || inRegion(b, mn[0], mn[1], mn[2], mx[0], mx[1], mx[2]);
      if (inScope && idx >= 0 && idx < mapping.length && mapping[idx] != idx) {
        blocks.add(new BlockEntry(b.x(), b.y(), b.z(), mapping[idx], b.nbt()));
        changed++;
      } else {
        blocks.add(b);
      }
    }
    return new SwapOutcome(
        new NbtStructure(s.sizeX, s.sizeY, s.sizeZ, s.dataVersion, palette, blocks, s.entities),
        changed, missing);
  }

  /**
   * Replaces the underscore-token sequence {@code from} inside the id's path with {@code to};
   * returns null when nothing matches. A match that is really part of a compound family name
   * (e.g. {@code oak} inside {@code dark_oak_planks}) is skipped.
   */
  @Nullable
  static String swapIdTokens(String blockId, String from, String to) {
    int colon = blockId.indexOf(':');
    String namespace = colon >= 0 ? blockId.substring(0, colon + 1) : "";
    String path = colon >= 0 ? blockId.substring(colon + 1) : blockId;
    String[] tokens = path.split("_");
    String[] fromTokens = from.split("_");
    StringBuilder out = new StringBuilder();
    boolean changed = false;
    int i = 0;
    while (i < tokens.length) {
      if (matchesAt(tokens, i, fromTokens) && !partOfCompound(tokens, i, fromTokens.length, from)) {
        if (out.length() > 0) {
          out.append('_');
        }
        out.append(to);
        i += fromTokens.length;
        changed = true;
      } else {
        if (out.length() > 0) {
          out.append('_');
        }
        out.append(tokens[i]);
        i++;
      }
    }
    return changed ? namespace + out : null;
  }

  private static boolean matchesAt(String[] tokens, int start, String[] want) {
    if (start + want.length > tokens.length) {
      return false;
    }
    for (int i = 0; i < want.length; i++) {
      if (!tokens[start + i].equals(want[i])) {
        return false;
      }
    }
    return true;
  }

  /** Whether the matched window extends into a known compound family name on either side. */
  private static boolean partOfCompound(String[] tokens, int start, int length, String matched) {
    if (start > 0 && COMPOUND_FAMILIES.contains(tokens[start - 1] + "_" + matched)) {
      return true;
    }
    int end = start + length;
    return end < tokens.length && COMPOUND_FAMILIES.contains(matched + "_" + tokens[end]);
  }

  /** Keeps only the properties the target block actually declares (with valid values). */
  @Nullable
  private static CompoundTag retainSupportedProperties(Block block, @Nullable CompoundTag props) {
    if (props == null || props.isEmpty()) {
      return null;
    }
    CompoundTag kept = new CompoundTag();
    var definition = block.getStateDefinition();
    for (String key : props.getAllKeys()) {
      Property<?> property = definition.getProperty(key);
      if (property != null && property.getValue(props.getString(key)).isPresent()) {
        kept.putString(key, props.getString(key));
      }
    }
    return kept.isEmpty() ? null : kept;
  }

  /** Hollows the structure: keeps only blocks that have at least one face adjacent to air or a boundary, removing fully interior blocks. */
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
    return new NbtStructure(s.sizeX, s.sizeY, s.sizeZ, s.dataVersion, s.palette, blocks,
        s.entities);
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

  /** Removes all instances of the given block ID, including all of its state variants. */
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
    return new NbtStructure(s.sizeX, s.sizeY, s.sizeZ, s.dataVersion, s.palette, blocks,
        s.entities);
  }

  /** Crops all-air margins and discards air blocks, tightening the bounding box to the non-air extent. */
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
    int newX = maxX - minX + 1;
    int newY = maxY - minY + 1;
    int newZ = maxZ - minZ + 1;
    List<EntityEntry> entities = filterEntities(
        translateEntities(s.entities, -minX, -minY, -minZ), newX, newY, newZ);
    return new NbtStructure(newX, newY, newZ, s.dataVersion, s.palette, blocks, entities);
  }

  /** Mirrors along the Y axis (top-bottom flip). Note: vanilla Mirror has no vertical mirror, so facing/orientation block states (e.g., stair half) are not flipped. */
  public static NbtStructure mirrorY(NbtStructure s) {
    List<BlockEntry> blocks = new ArrayList<>(s.blocks.size());
    for (BlockEntry b : s.blocks) {
      blocks.add(new BlockEntry(b.x(), s.sizeY - 1 - b.y(), b.z(), b.stateIndex(), b.nbt()));
    }
    List<EntityEntry> entities = mapEntities(s.entities,
        (x, y, z) -> new double[] {x, s.sizeY - y, z},
        (x, y, z) -> new int[] {x, s.sizeY - 1 - y, z},
        null);
    return new NbtStructure(s.sizeX, s.sizeY, s.sizeZ, s.dataVersion, s.palette, blocks, entities);
  }

  /** Rotates 90° around the X axis (tips forward). Note: vanilla block-state rotation only supports the Y axis, so facing/orientation block states remain unchanged. */
  public static NbtStructure rotateX(NbtStructure s) {
    List<BlockEntry> blocks = new ArrayList<>(s.blocks.size());
    for (BlockEntry b : s.blocks) {
      blocks.add(new BlockEntry(b.x(), s.sizeZ - 1 - b.z(), b.y(), b.stateIndex(), b.nbt()));
    }
    List<EntityEntry> entities = mapEntities(s.entities,
        (x, y, z) -> new double[] {x, s.sizeZ - z, y},
        (x, y, z) -> new int[] {x, s.sizeZ - 1 - z, y},
        null);
    return new NbtStructure(s.sizeX, s.sizeZ, s.sizeY, s.dataVersion, s.palette, blocks, entities);
  }

  /** Rotates 90° around the Z axis (tips sideways). Same caveat as {@link #rotateX}. */
  public static NbtStructure rotateZ(NbtStructure s) {
    List<BlockEntry> blocks = new ArrayList<>(s.blocks.size());
    for (BlockEntry b : s.blocks) {
      blocks.add(new BlockEntry(s.sizeY - 1 - b.y(), b.x(), b.z(), b.stateIndex(), b.nbt()));
    }
    List<EntityEntry> entities = mapEntities(s.entities,
        (x, y, z) -> new double[] {s.sizeY - y, x, z},
        (x, y, z) -> new int[] {s.sizeY - 1 - y, x, z},
        null);
    return new NbtStructure(s.sizeY, s.sizeX, s.sizeZ, s.dataVersion, s.palette, blocks, entities);
  }

  /** Frame: keeps only blocks on the edges of the bounding box (at least two coordinates on a boundary). */
  public static NbtStructure frame(NbtStructure s) {
    boolean[] air = airFlags(s);
    List<BlockEntry> blocks = new ArrayList<>();
    for (BlockEntry b : s.blocks) {
      if (isAir(b, air) || !inBounds(b, s)) {
        continue;
      }
      int extremes = 0;
      if (b.x() == 0 || b.x() == s.sizeX - 1) {
        extremes++;
      }
      if (b.y() == 0 || b.y() == s.sizeY - 1) {
        extremes++;
      }
      if (b.z() == 0 || b.z() == s.sizeZ - 1) {
        extremes++;
      }
      if (extremes >= 2) {
        blocks.add(b);
      }
    }
    return new NbtStructure(s.sizeX, s.sizeY, s.sizeZ, s.dataVersion, s.palette, blocks,
        s.entities);
  }

  /** Fill: fills interior cavities unreachable from outside air with the most common solid block in the structure. */
  public static NbtStructure fill(NbtStructure s) {
    long volume = s.volume();
    if (volume <= 0 || volume > FILL_MAX_VOLUME) {
      return s;
    }
    boolean[] air = airFlags(s);
    int n = (int) volume;
    boolean[] solid = new boolean[n];
    int[] counts = new int[s.palette.size()];
    for (BlockEntry b : s.blocks) {
      if (!isAir(b, air) && inBounds(b, s)) {
        solid[idx(b.x(), b.y(), b.z(), s)] = true;
        if (b.stateIndex() >= 0 && b.stateIndex() < counts.length) {
          counts[b.stateIndex()]++;
        }
      }
    }
    int fillIdx = -1;
    int best = -1;
    for (int i = 0; i < counts.length; i++) {
      if (!air[i] && counts[i] > best) {
        best = counts[i];
        fillIdx = i;
      }
    }
    if (fillIdx < 0) {
      return s;
    }
    boolean[] reached = new boolean[n];
    int[] stack = new int[n];
    int sp = 0;
    for (int y = 0; y < s.sizeY; y++) {
      for (int z = 0; z < s.sizeZ; z++) {
        for (int x = 0; x < s.sizeX; x++) {
          boolean boundary = x == 0 || x == s.sizeX - 1 || y == 0 || y == s.sizeY - 1
              || z == 0 || z == s.sizeZ - 1;
          if (!boundary) {
            continue;
          }
          int i = idx(x, y, z, s);
          if (!solid[i] && !reached[i]) {
            reached[i] = true;
            stack[sp++] = i;
          }
        }
      }
    }
    while (sp > 0) {
      int i = stack[--sp];
      int x = i % s.sizeX;
      int t = i / s.sizeX;
      int z = t % s.sizeZ;
      int y = t / s.sizeZ;
      sp = flood(solid, reached, stack, sp, x - 1, y, z, s);
      sp = flood(solid, reached, stack, sp, x + 1, y, z, s);
      sp = flood(solid, reached, stack, sp, x, y - 1, z, s);
      sp = flood(solid, reached, stack, sp, x, y + 1, z, s);
      sp = flood(solid, reached, stack, sp, x, y, z - 1, s);
      sp = flood(solid, reached, stack, sp, x, y, z + 1, s);
    }
    List<BlockEntry> blocks = new ArrayList<>(s.blocks);
    for (int y = 0; y < s.sizeY; y++) {
      for (int z = 0; z < s.sizeZ; z++) {
        for (int x = 0; x < s.sizeX; x++) {
          int i = idx(x, y, z, s);
          if (!solid[i] && !reached[i]) {
            blocks.add(new BlockEntry(x, y, z, fillIdx, null));
          }
        }
      }
    }
    return new NbtStructure(s.sizeX, s.sizeY, s.sizeZ, s.dataVersion, s.palette, blocks,
        s.entities);
  }

  private static int flood(boolean[] solid, boolean[] reached, int[] stack, int sp,
      int x, int y, int z, NbtStructure s) {
    if (x < 0 || x >= s.sizeX || y < 0 || y >= s.sizeY || z < 0 || z >= s.sizeZ) {
      return sp;
    }
    int i = idx(x, y, z, s);
    if (!solid[i] && !reached[i]) {
      reached[i] = true;
      stack[sp++] = i;
    }
    return sp;
  }

  /** Expand: adds one layer of air on every side of the bounding box (size +2, content shifted +1). */
  public static NbtStructure expand(NbtStructure s) {
    List<BlockEntry> blocks = new ArrayList<>(s.blocks.size());
    for (BlockEntry b : s.blocks) {
      blocks.add(new BlockEntry(b.x() + 1, b.y() + 1, b.z() + 1, b.stateIndex(), b.nbt()));
    }
    return new NbtStructure(s.sizeX + 2, s.sizeY + 2, s.sizeZ + 2, s.dataVersion, s.palette,
        blocks, translateEntities(s.entities, 1, 1, 1));
  }

  /** Deletes all blocks inside the region (inclusive, coordinates auto-normalized). */
  public static NbtStructure deleteRegion(NbtStructure s,
      int x0, int y0, int z0, int x1, int y1, int z1) {
    int ax0 = Math.min(x0, x1);
    int ay0 = Math.min(y0, y1);
    int az0 = Math.min(z0, z1);
    int ax1 = Math.max(x0, x1);
    int ay1 = Math.max(y0, y1);
    int az1 = Math.max(z0, z1);
    List<BlockEntry> blocks = new ArrayList<>();
    for (BlockEntry b : s.blocks) {
      if (!inRegion(b, ax0, ay0, az0, ax1, ay1, az1)) {
        blocks.add(b);
      }
    }
    List<EntityEntry> entities = new ArrayList<>();
    for (EntityEntry e : s.entities) {
      if (!cellInRegion(e, ax0, ay0, az0, ax1, ay1, az1)) {
        entities.add(e);
      }
    }
    return new NbtStructure(s.sizeX, s.sizeY, s.sizeZ, s.dataVersion, s.palette, blocks, entities);
  }

  /** Crops to the region (inclusive, auto-normalized and clamped to structure bounds), translating content to the origin. */
  public static NbtStructure cropToRegion(NbtStructure s,
      int x0, int y0, int z0, int x1, int y1, int z1) {
    int ax0 = clamp(Math.min(x0, x1), 0, s.sizeX - 1);
    int ay0 = clamp(Math.min(y0, y1), 0, s.sizeY - 1);
    int az0 = clamp(Math.min(z0, z1), 0, s.sizeZ - 1);
    int ax1 = clamp(Math.max(x0, x1), 0, s.sizeX - 1);
    int ay1 = clamp(Math.max(y0, y1), 0, s.sizeY - 1);
    int az1 = clamp(Math.max(z0, z1), 0, s.sizeZ - 1);
    List<BlockEntry> blocks = new ArrayList<>();
    for (BlockEntry b : s.blocks) {
      if (inRegion(b, ax0, ay0, az0, ax1, ay1, az1)) {
        blocks.add(new BlockEntry(
            b.x() - ax0, b.y() - ay0, b.z() - az0, b.stateIndex(), b.nbt()));
      }
    }
    int newX = ax1 - ax0 + 1;
    int newY = ay1 - ay0 + 1;
    int newZ = az1 - az0 + 1;
    List<EntityEntry> entities = filterEntities(
        translateEntities(s.entities, -ax0, -ay0, -az0), newX, newY, newZ);
    return new NbtStructure(newX, newY, newZ, s.dataVersion, s.palette, blocks, entities);
  }

  /** Fills the region with the specified block (auto-normalized and clamped; {@code blockId} is appended to the palette if absent). */
  public static NbtStructure fillRegion(NbtStructure s,
      int x0, int y0, int z0, int x1, int y1, int z1, String blockId) {
    int ax0 = clamp(Math.min(x0, x1), 0, s.sizeX - 1);
    int ay0 = clamp(Math.min(y0, y1), 0, s.sizeY - 1);
    int az0 = clamp(Math.min(z0, z1), 0, s.sizeZ - 1);
    int ax1 = clamp(Math.max(x0, x1), 0, s.sizeX - 1);
    int ay1 = clamp(Math.max(y0, y1), 0, s.sizeY - 1);
    int az1 = clamp(Math.max(z0, z1), 0, s.sizeZ - 1);
    PaletteEntry want = paletteEntryOf(blockId);
    List<PaletteEntry> palette = s.palette;
    int fillIdx = -1;
    for (int i = 0; i < palette.size(); i++) {
      if (palette.get(i).canonicalKey().equals(want.canonicalKey())) {
        fillIdx = i;
        break;
      }
    }
    if (fillIdx < 0) {
      palette = new ArrayList<>(s.palette);
      palette.add(want);
      fillIdx = palette.size() - 1;
    }
    List<BlockEntry> blocks = new ArrayList<>();
    for (BlockEntry b : s.blocks) {
      if (!inRegion(b, ax0, ay0, az0, ax1, ay1, az1)) {
        blocks.add(b);
      }
    }
    for (int y = ay0; y <= ay1; y++) {
      for (int z = az0; z <= az1; z++) {
        for (int x = ax0; x <= ax1; x++) {
          blocks.add(new BlockEntry(x, y, z, fillIdx, null));
        }
      }
    }
    return new NbtStructure(s.sizeX, s.sizeY, s.sizeZ, s.dataVersion, palette, blocks, s.entities);
  }

  /** Set a single cell to {@code blockId} (appended to the palette if absent; clamped to bounds). */
  public static NbtStructure setBlockAt(NbtStructure s, int x, int y, int z, String blockId) {
    return fillRegion(s, x, y, z, x, y, z, blockId);
  }

  /** Remove whatever block occupies a single cell. */
  public static NbtStructure removeBlockAt(NbtStructure s, int x, int y, int z) {
    return deleteRegion(s, x, y, z, x, y, z);
  }

  /**
   * Paste {@code clip} into {@code s} with its origin at (atX, atY, atZ), overwriting the covered
   * cells. {@code clip}'s palette is merged into {@code s}'s; cells landing outside {@code s} are
   * dropped.
   */
  public static NbtStructure pasteRegion(NbtStructure s, NbtStructure clip, int atX, int atY, int atZ) {
    return pasteRegion(s, clip, atX, atY, atZ, false);
  }

  /**
   * Paste {@code clip} into {@code s} at (atX, atY, atZ). When {@code merge} is false the entire clip
   * footprint is overwritten; when true only the cells the clip actually fills are replaced, leaving
   * existing blocks in the gaps. {@code clip}'s palette is merged into {@code s}'s; cells landing
   * outside {@code s} are dropped.
   */
  public static NbtStructure pasteRegion(NbtStructure s, NbtStructure clip,
      int atX, int atY, int atZ, boolean merge) {
    List<PaletteEntry> palette = new ArrayList<>(s.palette);
    int[] map = new int[clip.palette.size()];
    for (int i = 0; i < clip.palette.size(); i++) {
      PaletteEntry entry = clip.palette.get(i);
      int found = -1;
      for (int j = 0; j < palette.size(); j++) {
        if (palette.get(j).canonicalKey().equals(entry.canonicalKey())) {
          found = j;
          break;
        }
      }
      if (found < 0) {
        palette.add(entry);
        found = palette.size() - 1;
      }
      map[i] = found;
    }
    int ex = atX + clip.sizeX - 1;
    int ey = atY + clip.sizeY - 1;
    int ez = atZ + clip.sizeZ - 1;
    Set<Long> clipCells = null;
    if (merge) {
      clipCells = new HashSet<>();
      for (BlockEntry b : clip.blocks) {
        int nx = atX + b.x();
        int ny = atY + b.y();
        int nz = atZ + b.z();
        if (nx >= 0 && nx < s.sizeX && ny >= 0 && ny < s.sizeY && nz >= 0 && nz < s.sizeZ) {
          clipCells.add(cellKey(nx, ny, nz));
        }
      }
    }
    List<BlockEntry> blocks = new ArrayList<>();
    for (BlockEntry b : s.blocks) {
      boolean covered = merge
          ? clipCells.contains(cellKey(b.x(), b.y(), b.z()))
          : inRegion(b, atX, atY, atZ, ex, ey, ez);
      if (!covered) {
        blocks.add(b);
      }
    }
    for (BlockEntry b : clip.blocks) {
      int nx = atX + b.x();
      int ny = atY + b.y();
      int nz = atZ + b.z();
      if (nx < 0 || nx >= s.sizeX || ny < 0 || ny >= s.sizeY || nz < 0 || nz >= s.sizeZ) {
        continue;
      }
      int idx = b.stateIndex();
      int mapped = idx >= 0 && idx < map.length ? map[idx] : 0;
      blocks.add(new BlockEntry(nx, ny, nz, mapped, b.nbt()));
    }
    List<EntityEntry> entities = new ArrayList<>(s.entities);
    entities.addAll(filterEntities(
        translateEntities(clip.entities, atX, atY, atZ), s.sizeX, s.sizeY, s.sizeZ));
    return new NbtStructure(s.sizeX, s.sizeY, s.sizeZ, s.dataVersion, palette, blocks, entities);
  }

  // ---- Entity remapping ----

  /**
   * Remaps every entity through the given coordinate maps: relative position (and the {@code Pos}
   * tag), hanging-entity {@code TileX/Y/Z} cells, and — when {@code yaw} is non-null — the
   * {@code Rotation} yaw.
   */
  private static List<EntityEntry> mapEntities(List<EntityEntry> entities, PosMap pos,
      CellMap cell, @Nullable UnaryOperator<Float> yaw) {
    if (entities.isEmpty()) {
      return entities;
    }
    List<EntityEntry> out = new ArrayList<>(entities.size());
    for (EntityEntry e : entities) {
      double[] p = pos.apply(e.x(), e.y(), e.z());
      CompoundTag nbt = e.nbt().copy();
      writeEntityPos(nbt, p);
      if (yaw != null && nbt.contains("Rotation", Tag.TAG_LIST)) {
        ListTag rotation = nbt.getList("Rotation", Tag.TAG_FLOAT);
        if (rotation.size() >= 2) {
          ListTag mapped = new ListTag();
          mapped.add(FloatTag.valueOf(wrapDegrees(yaw.apply(rotation.getFloat(0)))));
          mapped.add(FloatTag.valueOf(rotation.getFloat(1)));
          nbt.put("Rotation", mapped);
        }
      }
      if (nbt.contains("TileX", Tag.TAG_INT)) {
        int[] tile = cell.apply(nbt.getInt("TileX"), nbt.getInt("TileY"), nbt.getInt("TileZ"));
        nbt.putInt("TileX", tile[0]);
        nbt.putInt("TileY", tile[1]);
        nbt.putInt("TileZ", tile[2]);
      }
      out.add(new EntityEntry(p[0], p[1], p[2], nbt));
    }
    return out;
  }

  /** Translates every entity (position, {@code Pos}, {@code TileX/Y/Z}) by whole blocks. */
  private static List<EntityEntry> translateEntities(List<EntityEntry> entities,
      int dx, int dy, int dz) {
    if (entities.isEmpty() || (dx == 0 && dy == 0 && dz == 0)) {
      return entities;
    }
    return mapEntities(entities,
        (x, y, z) -> new double[] {x + dx, y + dy, z + dz},
        (x, y, z) -> new int[] {x + dx, y + dy, z + dz},
        null);
  }

  /** Keeps only entities inside the structure bounds {@code [0, size)} (with a lenient upper edge). */
  private static List<EntityEntry> filterEntities(List<EntityEntry> entities,
      int sizeX, int sizeY, int sizeZ) {
    if (entities.isEmpty()) {
      return entities;
    }
    List<EntityEntry> out = new ArrayList<>(entities.size());
    for (EntityEntry e : entities) {
      if (e.x() >= 0 && e.x() <= sizeX && e.y() >= 0 && e.y() <= sizeY
          && e.z() >= 0 && e.z() <= sizeZ) {
        out.add(e);
      }
    }
    return out;
  }

  /** Whether the entity's block cell lies inside the inclusive region. */
  private static boolean cellInRegion(EntityEntry e,
      int x0, int y0, int z0, int x1, int y1, int z1) {
    int cx = (int) Math.floor(e.x());
    int cy = (int) Math.floor(e.y());
    int cz = (int) Math.floor(e.z());
    return cx >= x0 && cx <= x1 && cy >= y0 && cy <= y1 && cz >= z0 && cz <= z1;
  }

  private static void writeEntityPos(CompoundTag nbt, double[] p) {
    if (nbt.contains("Pos", Tag.TAG_LIST)) {
      ListTag pos = new ListTag();
      pos.add(DoubleTag.valueOf(p[0]));
      pos.add(DoubleTag.valueOf(p[1]));
      pos.add(DoubleTag.valueOf(p[2]));
      nbt.put("Pos", pos);
    }
  }

  private static float wrapDegrees(float degrees) {
    float wrapped = degrees % 360.0f;
    if (wrapped >= 180.0f) {
      wrapped -= 360.0f;
    }
    if (wrapped < -180.0f) {
      wrapped += 360.0f;
    }
    return wrapped;
  }

  /** Parse {@code name} or {@code name[k=v,...]} into a palette entry. */
  private static PaletteEntry paletteEntryOf(String spec) {
    int bracket = spec.indexOf('[');
    if (bracket < 0 || !spec.endsWith("]")) {
      return new PaletteEntry(spec, null);
    }
    String name = spec.substring(0, bracket);
    String inner = spec.substring(bracket + 1, spec.length() - 1);
    CompoundTag props = new CompoundTag();
    for (String kv : inner.split(",")) {
      int eq = kv.indexOf('=');
      if (eq > 0) {
        props.putString(kv.substring(0, eq).trim(), kv.substring(eq + 1).trim());
      }
    }
    return new PaletteEntry(name, props.isEmpty() ? null : props);
  }

  private static boolean inRegion(BlockEntry b, int x0, int y0, int z0, int x1, int y1, int z1) {
    return b.x() >= x0 && b.x() <= x1 && b.y() >= y0 && b.y() <= y1
        && b.z() >= z0 && b.z() <= z1;
  }

  private static int clamp(int v, int lo, int hi) {
    return v < lo ? lo : Math.min(v, hi);
  }

  private static long cellKey(int x, int y, int z) {
    return ((long) (x & 0x1FFFFF) << 42) | ((long) (y & 0x1FFFFF) << 21) | (z & 0x1FFFFF);
  }

  private static boolean[] airFlags(NbtStructure s) {
    boolean[] air = new boolean[s.palette.size()];
    for (int i = 0; i < air.length; i++) {
      air[i] = s.palette.get(i).isAir();
    }
    return air;
  }

  private static int idx(int x, int y, int z, NbtStructure s) {
    return (y * s.sizeZ + z) * s.sizeX + x;
  }

  private static boolean isAir(BlockEntry b, boolean[] air) {
    return b.stateIndex() < 0 || b.stateIndex() >= air.length || air[b.stateIndex()];
  }

  /** Parses each non-air palette entry into a BlockState, applies the transform, and serializes it back to name+properties. */
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
