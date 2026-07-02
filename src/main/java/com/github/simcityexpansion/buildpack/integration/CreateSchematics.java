package com.github.simcityexpansion.buildpack.integration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.github.simcityexpansion.buildpack.BuildPack;
import com.github.simcityexpansion.buildpack.convert.StructureNbtWriter;
import com.github.simcityexpansion.buildpack.model.FileNames;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.neoforged.fml.ModList;

/**
 * File-system integration with the Create mod's schematics ("机械动力蓝图"). Create schematics are
 * plain gzip-compressed vanilla {@code StructureTemplate} NBT saved as {@code .nbt} under
 * {@code <game dir>/schematics/} (blueprints uploaded to a server land in
 * {@code schematics/uploaded/<player>/}) — the same structure format SimuKraft builds from, so the
 * two directions are:
 *
 * <ul>
 *   <li><b>Import</b>: files under {@code schematics/} feed straight into the normal install
 *       pipeline (they parse as {@code VANILLA_NBT}).</li>
 *   <li><b>Export</b>: a converted structure tag is written into {@code schematics/} so Create's
 *       schematic table / schematicannon can use it. Mirroring Create's own save path
 *       ({@code SchematicExport}), structure voids are replaced with air first — Create does this
 *       for schematics it saves, and the schematicannon would otherwise place void blocks.</li>
 * </ul>
 *
 * <p>Pure file-system convention: no compile-time or runtime dependency on Create.
 */
public final class CreateSchematics {
  private CreateSchematics() {}

  /** Create's mod id (presence check only). */
  public static final String MODID = "create";

  /** Create's schematic directory ({@code <game dir>/schematics/}). */
  public static Path schematicsDir() {
    return BuildPack.gameDir().resolve("schematics");
  }

  /**
   * Whether the Create integration should surface in the UI: the mod is installed, or a
   * {@code schematics/} directory already exists (e.g. files copied over from another instance).
   */
  public static boolean available() {
    return createLoaded() || Files.isDirectory(schematicsDir());
  }

  /** Whether the Create mod is loaded. */
  public static boolean createLoaded() {
    ModList modList = ModList.get();
    return modList != null && modList.isLoaded(MODID);
  }

  /**
   * Writes a vanilla structure tag as a Create schematic into {@code schematics/} and returns the
   * created file. The name is sanitized and de-duplicated ({@code _2/_3...}); the tag is copied,
   * so the caller's instance is not modified by the structure-void replacement.
   */
  public static Path export(CompoundTag structureTag, String baseName) throws IOException {
    CompoundTag tag = structureTag.copy();
    replaceStructureVoidWithAir(tag);
    Files.createDirectories(schematicsDir());
    Path target = FileNames.unique(
        schematicsDir(), FileNames.sanitize(baseName, "schematic"), ".nbt");
    StructureNbtWriter.writeTag(tag, target);
    return target;
  }

  /**
   * Replaces {@code minecraft:structure_void} palette entries with air, matching what Create's own
   * schematic export does. Handles both the single {@code palette} list and the rarer
   * multi-palette {@code palettes} form of vanilla structure templates.
   */
  public static void replaceStructureVoidWithAir(CompoundTag root) {
    replaceInPalette(root.getList("palette", Tag.TAG_COMPOUND));
    ListTag palettes = root.getList("palettes", Tag.TAG_LIST);
    for (int i = 0; i < palettes.size(); i++) {
      replaceInPalette(palettes.getList(i));
    }
  }

  private static void replaceInPalette(ListTag palette) {
    for (int i = 0; i < palette.size(); i++) {
      CompoundTag state = palette.getCompound(i);
      if ("minecraft:structure_void".equals(state.getString("Name"))) {
        state.putString("Name", "minecraft:air");
        state.remove("Properties");
      }
    }
  }
}
