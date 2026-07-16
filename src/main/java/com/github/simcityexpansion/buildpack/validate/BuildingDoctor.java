package com.github.simcityexpansion.buildpack.validate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.github.simcityexpansion.buildpack.convert.NbtStructure;
import com.github.simcityexpansion.buildpack.convert.StructureAnalysis;
import com.github.simcityexpansion.buildpack.model.BuildingCategory;
import com.github.simcityexpansion.buildpack.model.BuildingMetadata;
import com.github.simcityexpansion.buildpack.model.PoiLine;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

/**
 * Pre-flight checks ("pack doctor") that mirror SimuKraft's silent-failure paths, so pack authors
 * see problems before installing or exporting instead of after building in-game:
 *
 * <ul>
 *   <li>Housing needs {@code minecraft:red_bed} heads — other bed colors are not counted upstream.</li>
 *   <li>{@code amount} is parsed leniently upstream ({@code ','} becomes {@code '.'}, everything
 *       but digits/dots is dropped): {@code 1,000} silently turns into {@code 1.0}, garbage into 0.</li>
 *   <li>Explicit air entries clear terrain when built; {@code structure_void} is placed as a real
 *       block (the client preview filters both, so authors can't see it there).</li>
 *   <li>Block entity NBT (chest contents, sign text) is discarded by the upstream builder.</li>
 *   <li>{@code poi:} lines: unknown types silently become OTHER, a non-integer capacity drops the
 *       whole line, and positions are derived from placed blocks by id-substring match.</li>
 *   <li>Blocks missing from the current environment are silently skipped (holes in the building).</li>
 * </ul>
 */
public final class BuildingDoctor {
  private BuildingDoctor() {}

  /** Finding severity, in report order. */
  public enum Severity { ERROR, WARN, INFO }

  /** One report line. */
  public record Finding(Severity severity, Component text) {}

  /** How many blocks with missing ids are listed by name before the list is elided. */
  private static final int MISSING_LIST_LIMIT = 5;

  /**
   * Runs all checks.
   *
   * @param skSizeField the raw {@code size:} field from an existing .sk entry, or null when the
   *     size is generated from the structure (then it cannot disagree)
   * @param hasDefinitionJson whether a native definition .json exists next to the building, or
   *     null when unknown (not yet installed)
   */
  public static List<Finding> examine(NbtStructure structure, BuildingMetadata meta,
      @Nullable String skSizeField, @Nullable Boolean hasDefinitionJson) {
    List<Finding> findings = new ArrayList<>();
    checkBeds(structure, meta, findings);
    checkAmount(meta, findings);
    checkSize(structure, skSizeField, findings);
    checkAirAndVoid(structure, findings);
    checkBlockEntities(structure, findings);
    checkMissingBlocks(structure, findings);
    checkPoiLines(structure, meta, findings);
    if (hasDefinitionJson != null && !hasDefinitionJson
        && (meta.category == BuildingCategory.COMMERCIAL
            || meta.category == BuildingCategory.INDUSTRY)) {
      add(findings, Severity.INFO, "no_definition");
    }
    findings.sort(Comparator.comparing(Finding::severity));
    return findings;
  }

  /** {@code {errors, warnings}} counts for a compact status-bar summary. */
  public static int[] counts(List<Finding> findings) {
    int errors = 0;
    int warns = 0;
    for (Finding finding : findings) {
      if (finding.severity() == Severity.ERROR) {
        errors++;
      } else if (finding.severity() == Severity.WARN) {
        warns++;
      }
    }
    return new int[] {errors, warns};
  }

  // ---- Housing (red beds) ----

  private static void checkBeds(NbtStructure structure, BuildingMetadata meta,
      List<Finding> findings) {
    boolean housing = meta.category == BuildingCategory.RESIDENTIAL
        || meta.poiLines.stream().anyMatch(PoiLine::isResidential);
    if (!housing) {
      return;
    }
    long redBedHeads = 0;
    long otherBedHeads = 0;
    for (NbtStructure.BlockEntry block : structure.blocks) {
      NbtStructure.PaletteEntry entry = palette(structure, block.stateIndex());
      if (entry == null || !entry.blockName().endsWith("_bed")) {
        continue;
      }
      boolean head = entry.properties() != null
          && "head".equals(entry.properties().getString("part"));
      if (!head) {
        continue;
      }
      if ("minecraft:red_bed".equals(entry.blockName())) {
        redBedHeads++;
      } else {
        otherBedHeads++;
      }
    }
    if (redBedHeads == 0) {
      add(findings, Severity.ERROR, "no_red_bed");
    } else {
      add(findings, Severity.INFO, "homes", redBedHeads);
    }
    if (otherBedHeads > 0) {
      add(findings, Severity.WARN, "non_red_bed", otherBedHeads);
    }
  }

  // ---- amount (construction fee) ----

  private static void checkAmount(BuildingMetadata meta, List<Finding> findings) {
    String raw = meta.amount == null ? "" : meta.amount.trim();
    if (raw.isEmpty() || "-".equals(raw)) {
      add(findings, Severity.INFO, "amount_free");
      return;
    }
    // Mirror SimuKraft's parseAmount: ',' -> '.', keep only digits and dots, then parseDouble.
    String cleaned = raw.replace(',', '.').replaceAll("[^0-9.]", "");
    Double parsed = null;
    if (!cleaned.isEmpty()) {
      try {
        parsed = Math.max(0.0, Math.round(Double.parseDouble(cleaned) * 100.0) / 100.0);
      } catch (NumberFormatException ignored) {
        // Falls through to the "parses as zero" warning.
      }
    }
    if (parsed == null) {
      add(findings, Severity.WARN, "amount_zero", raw);
    } else if (raw.indexOf(',') >= 0) {
      add(findings, Severity.WARN, "amount_comma", raw, format(parsed));
    } else if (!cleaned.equals(raw)) {
      add(findings, Severity.INFO, "amount_parsed", raw, format(parsed));
    }
  }

  private static String format(double value) {
    return String.format(Locale.ROOT, "%.2f", value);
  }

  // ---- size field vs actual bounding box ----

  private static void checkSize(NbtStructure structure, @Nullable String skSizeField,
      List<Finding> findings) {
    if (skSizeField == null || skSizeField.isBlank() || "-".equals(skSizeField.trim())) {
      return;
    }
    // Mirror SimuKraft's parseSize: '×' and spaces are separators; exactly 3 numbers required.
    String[] parts = skSizeField.trim().toLowerCase(Locale.ROOT).split("[x×\\s]+");
    int[] dims = new int[3];
    boolean valid = parts.length == 3;
    for (int i = 0; valid && i < 3; i++) {
      try {
        dims[i] = Integer.parseInt(parts[i]);
      } catch (NumberFormatException e) {
        valid = false;
      }
    }
    if (!valid) {
      add(findings, Severity.WARN, "size_invalid", skSizeField);
    } else if (dims[0] != structure.sizeX || dims[1] != structure.sizeY
        || dims[2] != structure.sizeZ) {
      add(findings, Severity.WARN, "size_mismatch", skSizeField,
          structure.sizeX + " x " + structure.sizeY + " x " + structure.sizeZ);
    }
  }

  // ---- explicit air / structure_void ----

  private static void checkAirAndVoid(NbtStructure structure, List<Finding> findings) {
    long air = 0;
    long structureVoid = 0;
    for (NbtStructure.BlockEntry block : structure.blocks) {
      NbtStructure.PaletteEntry entry = palette(structure, block.stateIndex());
      if (entry == null) {
        continue;
      }
      if (entry.isAir()) {
        air++;
      } else if ("minecraft:structure_void".equals(entry.blockName())) {
        structureVoid++;
      }
    }
    if (structureVoid > 0) {
      add(findings, Severity.WARN, "structure_void", structureVoid);
    }
    if (air > 0) {
      add(findings, Severity.INFO, "air_entries", air);
    }
  }

  // ---- block entity NBT (discarded upstream) ----

  private static void checkBlockEntities(NbtStructure structure, List<Finding> findings) {
    long withNbt = structure.blocks.stream().filter(block -> block.nbt() != null).count();
    if (withNbt > 0) {
      add(findings, Severity.INFO, "block_entities", withNbt);
    }
  }

  // ---- missing blocks / mod dependencies ----

  private static void checkMissingBlocks(NbtStructure structure, List<Finding> findings) {
    List<String> missing = StructureAnalysis.findMissingBlocks(structure);
    if (!missing.isEmpty()) {
      String shown = String.join(", ", missing.subList(0, Math.min(MISSING_LIST_LIMIT, missing.size())))
          + (missing.size() > MISSING_LIST_LIMIT ? ", …" : "");
      add(findings, Severity.WARN, "missing_blocks", missing.size(), shown);
    }
    Set<String> namespaces = new LinkedHashSet<>();
    for (NbtStructure.PaletteEntry entry : structure.palette) {
      if (entry.isAir()) {
        continue;
      }
      int colon = entry.blockName().indexOf(':');
      String namespace = colon > 0 ? entry.blockName().substring(0, colon) : "minecraft";
      if (!"minecraft".equals(namespace)) {
        namespaces.add(namespace);
      }
    }
    if (!namespaces.isEmpty()) {
      add(findings, Severity.INFO, "mod_deps", String.join(", ", namespaces));
    }
  }

  // ---- poi: lines ----

  private static void checkPoiLines(NbtStructure structure, BuildingMetadata meta,
      List<Finding> findings) {
    for (PoiLine poi : meta.poiLines) {
      if (poi.capacityValue() == null) {
        add(findings, Severity.WARN, "poi_bad_capacity", poi.format());
        continue;
      }
      if (!poi.typeKnown()) {
        add(findings, Severity.WARN, "poi_unknown_type", poi.type());
      }
      if (poi.isResidential()) {
        add(findings, Severity.INFO, "poi_residential_note");
        continue;
      }
      String idBlock = landingBlock(structure, poi.effectiveId());
      if (idBlock != null) {
        add(findings, Severity.INFO, "poi_landing", poi.effectiveId(), idBlock);
      } else if (landingBlock(structure, "control_box") != null) {
        add(findings, Severity.INFO, "poi_landing_control", poi.effectiveId());
      } else {
        add(findings, Severity.WARN, "poi_landing_origin", poi.effectiveId());
      }
    }
  }

  /** Where a non-residential POI would land, mirroring SimuKraft's resolvePoiPosition. */
  public enum Landing { ID_MATCH, CONTROL_BOX, ORIGIN }

  /**
   * Simulates SimuKraft's POI position resolution: the first placed block whose registry name
   * contains the POI id as a substring wins; otherwise any {@code control_box} block; otherwise
   * the building origin.
   */
  public static Landing simulateLanding(NbtStructure structure, String poiId) {
    if (landingBlock(structure, poiId) != null) {
      return Landing.ID_MATCH;
    }
    return landingBlock(structure, "control_box") != null ? Landing.CONTROL_BOX : Landing.ORIGIN;
  }

  /** The registry name of the first placed block containing {@code needle}, or null. */
  @Nullable
  public static String landingBlock(NbtStructure structure, String needle) {
    String lower = needle.toLowerCase(Locale.ROOT);
    for (NbtStructure.BlockEntry block : structure.blocks) {
      NbtStructure.PaletteEntry entry = palette(structure, block.stateIndex());
      if (entry != null && !entry.isAir()
          && entry.blockName().toLowerCase(Locale.ROOT).contains(lower)) {
        return entry.blockName();
      }
    }
    return null;
  }

  @Nullable
  private static NbtStructure.PaletteEntry palette(NbtStructure structure, int index) {
    return index >= 0 && index < structure.palette.size() ? structure.palette.get(index) : null;
  }

  private static void add(List<Finding> findings, Severity severity, String key, Object... args) {
    findings.add(new Finding(severity, Component.translatable("buildpack.doctor." + key, args)));
  }
}
