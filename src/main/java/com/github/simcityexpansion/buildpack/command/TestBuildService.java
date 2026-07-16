package com.github.simcityexpansion.buildpack.command;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.github.simcityexpansion.buildpack.convert.NbtStructure;
import com.github.simcityexpansion.buildpack.convert.WorldCapture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * Test placement of a structure directly into the world with a one-slot undo per player: the
 * fastest way to eyeball a building exactly as SimuKraft would construct it, without the
 * build-box / hire-a-builder / wait loop.
 *
 * <p>Placement mirrors SimuKraft's construction semantics: explicit air entries clear terrain,
 * unresolvable (missing-mod) blocks are skipped, and block entity NBT is discarded. Every touched
 * cell's previous state (including block entity data) is snapshotted so {@code undo} restores the
 * terrain exactly.
 */
public final class TestBuildService {
  private TestBuildService() {}

  /** Volume cap for a single test placement (placed in one tick — keep hitches bounded). */
  public static final long MAX_VOLUME = 1_000_000L;

  private record SavedBlock(BlockPos pos, BlockState state, @Nullable CompoundTag blockEntity) {}

  private record Snapshot(ResourceKey<Level> dimension, List<SavedBlock> blocks) {}

  /** One undo slot per player; a new test placement replaces the previous snapshot. */
  private static final Map<UUID, Snapshot> SNAPSHOTS = new HashMap<>();

  /** Placement result counters for the command feedback. */
  public record PlaceResult(int placed, int skippedMissing, int skippedOutOfWorld,
      boolean replacedSnapshot) {}

  /**
   * Places the structure with its origin at {@code origin}, snapshotting every touched cell.
   * Callers must have verified the volume cap and chunk load state.
   */
  public static PlaceResult place(ServerLevel level, UUID player, NbtStructure structure,
      BlockPos origin) {
    HolderGetter<Block> lookup = BuiltInRegistries.BLOCK.asLookup();
    BlockState[] palette = new BlockState[structure.palette.size()];
    boolean[] resolved = new boolean[structure.palette.size()];
    for (int i = 0; i < structure.palette.size(); i++) {
      NbtStructure.PaletteEntry entry = structure.palette.get(i);
      try {
        CompoundTag tag = new CompoundTag();
        tag.putString("Name", entry.blockName());
        if (entry.properties() != null) {
          tag.put("Properties", entry.properties());
        }
        BlockState state = NbtUtils.readBlockState(lookup, tag);
        // readBlockState falls back to air for unknown blocks; mirror SimuKraft's "skip missing".
        if (!state.isAir() || entry.isAir()) {
          palette[i] = state;
          resolved[i] = true;
        }
      } catch (RuntimeException ignored) {
        // Unresolvable palette entry: skipped like SimuKraft does.
      }
    }

    boolean replaced = SNAPSHOTS.containsKey(player);
    List<SavedBlock> saved = new ArrayList<>(structure.blocks.size());
    int placed = 0;
    int skippedMissing = 0;
    int skippedOutOfWorld = 0;
    for (NbtStructure.BlockEntry block : structure.blocks) {
      int index = block.stateIndex();
      if (index < 0 || index >= palette.length || !resolved[index]) {
        skippedMissing++;
        continue;
      }
      BlockPos pos = origin.offset(block.x(), block.y(), block.z());
      if (level.isOutsideBuildHeight(pos)) {
        skippedOutOfWorld++;
        continue;
      }
      BlockEntity previousEntity = level.getBlockEntity(pos);
      saved.add(new SavedBlock(pos, level.getBlockState(pos),
          previousEntity != null ? previousEntity.saveWithId(level.registryAccess()) : null));
      // UPDATE_CLIENTS without neighbor updates: place exactly what the file contains (no
      // physics cascades) — the same end state SimuKraft's builder converges to.
      level.setBlock(pos, palette[index], Block.UPDATE_CLIENTS);
      placed++;
    }
    SNAPSHOTS.put(player, new Snapshot(level.dimension(), saved));
    return new PlaceResult(placed, skippedMissing, skippedOutOfWorld, replaced);
  }

  /**
   * Restores the player's last test placement; returns the number of restored cells, or -1 when
   * there is no snapshot (or it belongs to another dimension).
   */
  public static int undo(ServerLevel level, UUID player) {
    Snapshot snapshot = SNAPSHOTS.get(player);
    if (snapshot == null || !snapshot.dimension().equals(level.dimension())) {
      return -1;
    }
    SNAPSHOTS.remove(player);
    int restored = 0;
    for (SavedBlock saved : snapshot.blocks()) {
      level.setBlock(saved.pos(), saved.state(), Block.UPDATE_CLIENTS);
      if (saved.blockEntity() != null) {
        BlockEntity entity = level.getBlockEntity(saved.pos());
        if (entity != null) {
          entity.loadWithComponents(saved.blockEntity(), level.registryAccess());
          entity.setChanged();
        }
      }
      restored++;
    }
    return restored;
  }

  /** Whether the player has a pending test placement to undo. */
  public static boolean hasSnapshot(UUID player) {
    return SNAPSHOTS.containsKey(player);
  }

  /** Whether the structure's target region is fully loaded (delegates to the capture helper). */
  public static boolean regionLoaded(ServerLevel level, BlockPos origin, NbtStructure structure) {
    return WorldCapture.regionLoaded(level, origin,
        origin.offset(Math.max(0, structure.sizeX - 1), Math.max(0, structure.sizeY - 1),
            Math.max(0, structure.sizeZ - 1)));
  }
}
