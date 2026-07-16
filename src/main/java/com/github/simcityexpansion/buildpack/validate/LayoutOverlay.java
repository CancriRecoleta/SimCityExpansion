package com.github.simcityexpansion.buildpack.validate;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.github.simcityexpansion.buildpack.convert.NbtStructure;
import com.github.simcityexpansion.buildpack.model.PoiLine;
import com.github.simcityexpansion.buildpack.ui.preview.StructureScene;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

/**
 * Computes the functional-layout overlay for a building: which cells SimuKraft will treat as homes
 * (red bed heads), where each {@code poi:} line will land (simulated with
 * {@link BuildingDoctor}'s rules), the control box, and the coordinates declared in the
 * commercial/industrial definition JSON (points, containers, output containers).
 *
 * <p>Each group carries a distinct color and a legend line; the 3D scene renders the cells as
 * see-through boxes via {@link StructureScene#setColoredMarkers}.
 */
public final class LayoutOverlay {
  private LayoutOverlay() {}

  /** One legend group: translation key suffix, marker color, and the highlighted cells. */
  public record Group(String key, int color, List<BlockPos> cells) {}

  private static final int COLOR_BEDS = 0xB0FF3C50;
  private static final int COLOR_OTHER_BEDS = 0x90FF9090;
  private static final int COLOR_CONTROL = 0xB0FFE040;
  private static final int COLOR_POI = 0xB0D060FF;
  private static final int COLOR_POINTS = 0xB04080FF;
  private static final int COLOR_CONTAINERS = 0xB0FF9030;
  private static final int COLOR_OUTPUT = 0xB040FF70;

  /**
   * Builds all overlay groups; empty groups are omitted.
   *
   * @param definitionJson the building's native definition .json text, or null when absent
   */
  public static List<Group> compute(NbtStructure structure, List<PoiLine> poiLines,
      @Nullable String definitionJson) {
    List<Group> groups = new ArrayList<>();
    addBeds(structure, groups);
    addBlocksContaining(structure, "control_box", "control", COLOR_CONTROL, groups);
    addPoiLandings(structure, poiLines, groups);
    addDefinition(structure, definitionJson, groups);
    return groups;
  }

  /** Flattens the groups into scene markers. */
  public static List<StructureScene.Marker> markers(List<Group> groups) {
    List<StructureScene.Marker> markers = new ArrayList<>();
    for (Group group : groups) {
      for (BlockPos pos : group.cells()) {
        markers.add(new StructureScene.Marker(pos, group.color()));
      }
    }
    return markers;
  }

  private static void addBeds(NbtStructure structure, List<Group> groups) {
    List<BlockPos> red = new ArrayList<>();
    List<BlockPos> other = new ArrayList<>();
    for (NbtStructure.BlockEntry block : structure.blocks) {
      NbtStructure.PaletteEntry entry = palette(structure, block.stateIndex());
      if (entry == null || !entry.blockName().endsWith("_bed") || entry.properties() == null
          || !"head".equals(entry.properties().getString("part"))) {
        continue;
      }
      ("minecraft:red_bed".equals(entry.blockName()) ? red : other)
          .add(new BlockPos(block.x(), block.y(), block.z()));
    }
    addGroup(groups, "beds", COLOR_BEDS, red);
    addGroup(groups, "other_beds", COLOR_OTHER_BEDS, other);
  }

  /** Adds every block whose registry name contains {@code needle} as one group. */
  private static void addBlocksContaining(NbtStructure structure, String needle, String key,
      int color, List<Group> groups) {
    List<BlockPos> cells = new ArrayList<>();
    String lower = needle.toLowerCase(Locale.ROOT);
    for (NbtStructure.BlockEntry block : structure.blocks) {
      NbtStructure.PaletteEntry entry = palette(structure, block.stateIndex());
      if (entry != null && !entry.isAir()
          && entry.blockName().toLowerCase(Locale.ROOT).contains(lower)) {
        cells.add(new BlockPos(block.x(), block.y(), block.z()));
      }
    }
    addGroup(groups, key, color, cells);
  }

  /** Simulated landing cell of each non-residential poi line (id match → control box → origin). */
  private static void addPoiLandings(NbtStructure structure, List<PoiLine> poiLines,
      List<Group> groups) {
    Set<BlockPos> cells = new LinkedHashSet<>();
    for (PoiLine poi : poiLines) {
      if (poi.isResidential() || poi.capacityValue() == null) {
        continue;
      }
      BlockPos pos = firstBlockContaining(structure, poi.effectiveId());
      if (pos == null) {
        pos = firstBlockContaining(structure, "control_box");
      }
      cells.add(pos != null ? pos : BlockPos.ZERO);
    }
    addGroup(groups, "poi", COLOR_POI, new ArrayList<>(cells));
  }

  @Nullable
  private static BlockPos firstBlockContaining(NbtStructure structure, String needle) {
    String lower = needle.toLowerCase(Locale.ROOT);
    for (NbtStructure.BlockEntry block : structure.blocks) {
      NbtStructure.PaletteEntry entry = palette(structure, block.stateIndex());
      if (entry != null && !entry.isAir()
          && entry.blockName().toLowerCase(Locale.ROOT).contains(lower)) {
        return new BlockPos(block.x(), block.y(), block.z());
      }
    }
    return null;
  }

  /** Points/containers coordinates from the definition JSON (output containers separated). */
  private static void addDefinition(NbtStructure structure, @Nullable String definitionJson,
      List<Group> groups) {
    if (definitionJson == null || definitionJson.isBlank()) {
      return;
    }
    JsonObject root;
    try {
      JsonElement parsed = JsonParser.parseString(definitionJson);
      if (!parsed.isJsonObject()) {
        return;
      }
      root = parsed.getAsJsonObject();
    } catch (RuntimeException e) {
      return;
    }
    addGroup(groups, "points", COLOR_POINTS, positionsOf(root, "points", null));
    addGroup(groups, "containers", COLOR_CONTAINERS, positionsOf(root, "containers", false));
    addGroup(groups, "output", COLOR_OUTPUT, positionsOf(root, "containers", true));
  }

  /**
   * Collects {@code pos}/{@code positions} coordinates from the given map object.
   *
   * @param outputOnly for containers: true = only ids containing "output", false = the rest,
   *     null = all entries
   */
  private static List<BlockPos> positionsOf(JsonObject root, String mapKey,
      @Nullable Boolean outputOnly) {
    List<BlockPos> cells = new ArrayList<>();
    if (!root.has(mapKey) || !root.get(mapKey).isJsonObject()) {
      return cells;
    }
    for (var entry : root.getAsJsonObject(mapKey).entrySet()) {
      if (!entry.getValue().isJsonObject()) {
        continue;
      }
      boolean isOutput = entry.getKey().toLowerCase(Locale.ROOT).contains("output");
      if (outputOnly != null && outputOnly != isOutput) {
        continue;
      }
      JsonObject holder = entry.getValue().getAsJsonObject();
      if (holder.has("pos")) {
        addPos(cells, holder.get("pos"));
      }
      if (holder.has("positions") && holder.get("positions").isJsonArray()) {
        for (JsonElement element : holder.getAsJsonArray("positions")) {
          addPos(cells, element);
        }
      }
    }
    return cells;
  }

  private static void addPos(List<BlockPos> cells, JsonElement element) {
    if (!element.isJsonArray()) {
      return;
    }
    JsonArray array = element.getAsJsonArray();
    if (array.size() < 3) {
      return;
    }
    try {
      cells.add(new BlockPos(array.get(0).getAsInt(), array.get(1).getAsInt(),
          array.get(2).getAsInt()));
    } catch (RuntimeException ignored) {
      // Non-numeric coordinates are reported by the definition validator, not here.
    }
  }

  private static void addGroup(List<Group> groups, String key, int color, List<BlockPos> cells) {
    if (!cells.isEmpty()) {
      groups.add(new Group(key, color, cells));
    }
  }

  @Nullable
  private static NbtStructure.PaletteEntry palette(NbtStructure structure, int index) {
    return index >= 0 && index < structure.palette.size() ? structure.palette.get(index) : null;
  }
}
