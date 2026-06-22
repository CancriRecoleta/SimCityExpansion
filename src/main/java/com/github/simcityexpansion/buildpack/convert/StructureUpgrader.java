package com.github.simcityexpansion.buildpack.convert;

import java.util.List;

import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.util.datafix.DataFixers;

/**
 * Upgrades old-version structures to the current DataVersion using Minecraft's built-in DataFixer.
 *
 * <p>Block state property names and values change across versions; without upgrading, SimuKraft
 * cannot parse old states and placement degrades to air. This follows the same
 * {@code DataFixTypes.STRUCTURE} upgrade path used by the vanilla {@code StructureTemplateManager}.
 */
public final class StructureUpgrader {
  private StructureUpgrader() {}

  /** Returns the current game's DataVersion. */
  public static int currentDataVersion() {
    return SharedConstants.getCurrentVersion().getDataVersion().getVersion();
  }

  /**
   * Upgrades a vanilla-format structure tag; older versions are actually upgraded and a message is
   * appended. Structures from a newer version cannot be downgraded — a warning is appended and the
   * original tag is returned unchanged.
   */
  public static CompoundTag upgradeToCurrent(
      CompoundTag vanillaTag, int dataVersion, List<Component> messages) {
    int current = currentDataVersion();
    if (dataVersion > 0 && dataVersion < current) {
      CompoundTag upgraded = DataFixTypes.STRUCTURE.updateToCurrentVersion(
          DataFixers.getDataFixer(), vanillaTag, dataVersion);
      upgraded.putInt("DataVersion", current);
      messages.add(Component.translatable("buildpack.msg.upgraded", dataVersion, current));
      return upgraded;
    }
    if (dataVersion > current) {
      messages.add(Component.translatable("buildpack.msg.newer_version", dataVersion, current));
    }
    return vanillaTag;
  }

  /** Warns about missing blocks (lists at most the first 5 ids). */
  public static void warnMissingBlocks(NbtStructure structure, List<Component> messages) {
    List<String> missing = StructureAnalysis.findMissingBlocks(structure);
    if (missing.isEmpty()) {
      return;
    }
    String shown = String.join(", ", missing.subList(0, Math.min(5, missing.size())));
    if (missing.size() > 5) {
      shown += ", …";
    }
    messages.add(Component.translatable("buildpack.msg.missing_blocks", missing.size(), shown));
  }
}
