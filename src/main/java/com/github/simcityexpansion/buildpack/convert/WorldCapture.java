package com.github.simcityexpansion.buildpack.convert;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.github.simcityexpansion.buildpack.convert.NbtStructure.BlockEntry;
import com.github.simcityexpansion.buildpack.convert.NbtStructure.EntityEntry;
import com.github.simcityexpansion.buildpack.convert.NbtStructure.PaletteEntry;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * Captures a bounding box region from the current world (save) into an {@link NbtStructure}:
 * reads block states cell by cell, deduplicates them into a palette (air is always at index 0),
 * and produces a complete grid blocks list.
 *
 * <p>Block entity data (chest contents, sign text, etc.) is captured when the caller opts in via
 * {@code includeBlockEntities}; non-player entities are captured alongside when present. Note that
 * SimuKraft's builder ignores block entity data when constructing — it matters for litematic /
 * Create round-trips, not for in-game building.
 */
public final class WorldCapture {
  private WorldCapture() {}

  /** Maximum volume (in blocks) allowed for a single world capture. */
  public static final long MAX_CAPTURE_VOLUME = 2_000_000L;

  /** True if every chunk overlapping the inclusive [min, max] region is currently loaded. */
  public static boolean regionLoaded(Level level, BlockPos min, BlockPos max) {
    for (int cx = min.getX() >> 4; cx <= (max.getX() >> 4); cx++) {
      for (int cz = min.getZ() >> 4; cz <= (max.getZ() >> 4); cz++) {
        if (!level.hasChunk(cx, cz)) {
          return false;
        }
      }
    }
    return true;
  }

  /** Captures all blocks (including air) within the inclusive range [min, max] as a structure. */
  public static NbtStructure capture(Level level, BlockPos min, BlockPos max) {
    return capture(level, min, max, false);
  }

  /**
   * Captures the inclusive range [min, max] as a structure. When {@code includeBlockEntities} is
   * true, block-entity data (chest contents, sign text, etc.) is stored in each block's nbt.
   */
  public static NbtStructure capture(Level level, BlockPos min, BlockPos max,
      boolean includeBlockEntities) {
    int sizeX = max.getX() - min.getX() + 1;
    int sizeY = max.getY() - min.getY() + 1;
    int sizeZ = max.getZ() - min.getZ() + 1;

    List<PaletteEntry> palette = new ArrayList<>();
    Map<String, Integer> paletteIndex = new LinkedHashMap<>();
    palette.add(PaletteEntry.AIR);
    paletteIndex.put(PaletteEntry.AIR.canonicalKey(), 0);

    List<BlockEntry> blocks = new ArrayList<>(sizeX * sizeY * sizeZ);
    BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
    for (int y = 0; y < sizeY; y++) {
      for (int z = 0; z < sizeZ; z++) {
        for (int x = 0; x < sizeX; x++) {
          cursor.set(min.getX() + x, min.getY() + y, min.getZ() + z);
          BlockState state = level.getBlockState(cursor);
          int index;
          if (state.isAir()) {
            index = 0;
          } else {
            PaletteEntry entry = toEntry(state);
            index = paletteIndex.computeIfAbsent(entry.canonicalKey(), key -> {
              palette.add(entry);
              return palette.size() - 1;
            });
          }
          CompoundTag blockNbt = includeBlockEntities && index != 0
              ? blockEntityNbt(level, cursor) : null;
          blocks.add(new BlockEntry(x, y, z, index, blockNbt));
        }
      }
    }
    return new NbtStructure(sizeX, sizeY, sizeZ,
        SharedConstants.getCurrentVersion().getDataVersion().getVersion(), palette, blocks);
  }

  private static PaletteEntry toEntry(BlockState state) {
    CompoundTag tag = NbtUtils.writeBlockState(state);
    CompoundTag props = tag.contains("Properties") ? tag.getCompound("Properties") : null;
    return new PaletteEntry(tag.getString("Name"), props);
  }

  /** Block-entity data at a position (id + contents, without world coordinates), or null. */
  private static CompoundTag blockEntityNbt(Level level, BlockPos pos) {
    BlockEntity be = level.getBlockEntity(pos);
    if (be == null) {
      return null;
    }
    CompoundTag tag = be.saveWithId(level.registryAccess());
    tag.remove("x");
    tag.remove("y");
    tag.remove("z");
    return tag.isEmpty() ? null : tag;
  }

  /** Incremental capture: reads a budget of cells per call so large captures can spread across ticks. */
  public static final class Job {
    private final Level level;
    private final BlockPos min;
    private final int sizeX;
    private final int sizeY;
    private final int sizeZ;
    private final boolean includeBlockEntities;
    private final List<PaletteEntry> palette = new ArrayList<>();
    private final Map<String, Integer> paletteIndex = new LinkedHashMap<>();
    private final List<BlockEntry> blocks;
    private final int total;
    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
    private int index;

    private Job(Level level, BlockPos min, BlockPos max, boolean includeBlockEntities) {
      this.level = level;
      this.min = min.immutable();
      this.sizeX = max.getX() - min.getX() + 1;
      this.sizeY = max.getY() - min.getY() + 1;
      this.sizeZ = max.getZ() - min.getZ() + 1;
      this.includeBlockEntities = includeBlockEntities;
      this.total = sizeX * sizeY * sizeZ;
      this.blocks = new ArrayList<>(total);
      palette.add(PaletteEntry.AIR);
      paletteIndex.put(PaletteEntry.AIR.canonicalKey(), 0);
    }

    /** Processes up to {@code budget} cells; returns true once the whole region has been read. */
    public boolean advance(int budget) {
      int end = Math.min(total, index + Math.max(1, budget));
      for (; index < end; index++) {
        int x = index % sizeX;
        int rem = index / sizeX;
        int z = rem % sizeZ;
        int y = rem / sizeZ;
        cursor.set(min.getX() + x, min.getY() + y, min.getZ() + z);
        BlockState state = level.getBlockState(cursor);
        int idx;
        if (state.isAir()) {
          idx = 0;
        } else {
          PaletteEntry entry = toEntry(state);
          idx = paletteIndex.computeIfAbsent(entry.canonicalKey(), key -> {
            palette.add(entry);
            return palette.size() - 1;
          });
        }
        CompoundTag nbt = includeBlockEntities && idx != 0 ? blockEntityNbt(level, cursor) : null;
        blocks.add(new BlockEntry(x, y, z, idx, nbt));
      }
      return index >= total;
    }

    /** Capture progress as a percentage in [0, 100]. */
    public int progress() {
      return total <= 0 ? 100 : (int) (100L * index / total);
    }

    /** Builds the captured structure; call after {@link #advance} reports completion. */
    public NbtStructure build() {
      return new NbtStructure(sizeX, sizeY, sizeZ,
          SharedConstants.getCurrentVersion().getDataVersion().getVersion(), palette, blocks,
          includeBlockEntities ? collectEntities() : List.of());
    }

    /** Non-player entities whose position is inside the region, with positions relative to the origin. */
    private List<EntityEntry> collectEntities() {
      AABB area = new AABB(min.getX(), min.getY(), min.getZ(),
          min.getX() + sizeX, min.getY() + sizeY, min.getZ() + sizeZ);
      List<EntityEntry> result = new ArrayList<>();
      for (Entity entity : level.getEntitiesOfClass(Entity.class, area)) {
        if (entity instanceof Player) {
          continue;
        }
        double rx = entity.getX() - min.getX();
        double ry = entity.getY() - min.getY();
        double rz = entity.getZ() - min.getZ();
        if (rx < 0 || rx >= sizeX || ry < 0 || ry >= sizeY || rz < 0 || rz >= sizeZ) {
          continue;
        }
        CompoundTag tag = new CompoundTag();
        if (!entity.save(tag)) {
          continue;
        }
        tag.remove("UUID");
        ListTag pos = new ListTag();
        pos.add(DoubleTag.valueOf(rx));
        pos.add(DoubleTag.valueOf(ry));
        pos.add(DoubleTag.valueOf(rz));
        tag.put("Pos", pos);
        result.add(new EntityEntry(rx, ry, rz, tag));
      }
      return result;
    }
  }

  /** Creates an incremental capture job for the inclusive region [min, max]. */
  public static Job job(Level level, BlockPos min, BlockPos max, boolean includeBlockEntities) {
    return new Job(level, min, max, includeBlockEntities);
  }
}
