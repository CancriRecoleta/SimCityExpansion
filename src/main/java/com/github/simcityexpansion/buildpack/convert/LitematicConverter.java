package com.github.simcityexpansion.buildpack.convert;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.network.chat.Component;

/** Facade for litematic-to-vanilla-NBT-structure conversion. */
public final class LitematicConverter {
  private LitematicConverter() {}

  /** Volume threshold above which a "structure too large" warning is shown (install is still allowed). */
  private static final long LARGE_VOLUME_WARNING = 2_000_000L;

  /** Reads a .litematic file and merges all regions into a single vanilla structure. */
  public static NbtStructure convert(Path litematic) throws IOException {
    return LitematicReader.readAndMerge(litematic);
  }

  /**
   * Pre-install validation; returns warnings to display to the user (may be empty).
   * Outdated DataVersion is no longer warned here — the install pipeline upgrades via {@link StructureUpgrader}.
   */
  public static List<Component> validate(NbtStructure structure) {
    List<Component> warnings = new ArrayList<>();
    if (structure.volume() > LARGE_VOLUME_WARNING) {
      warnings.add(Component.translatable("buildpack.msg.large_structure",
          structure.sizeX + " x " + structure.sizeY + " x " + structure.sizeZ));
    }
    return warnings;
  }
}
