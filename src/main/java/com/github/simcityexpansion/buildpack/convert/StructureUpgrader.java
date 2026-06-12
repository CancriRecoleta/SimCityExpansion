package com.github.simcityexpansion.buildpack.convert;

import java.util.List;

import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.util.datafix.DataFixers;

/**
 * 用 Minecraft 自带的 DataFixer 把旧版本结构升级到当前 DataVersion。
 *
 * <p>方块状态的属性名/值在版本间会变化，不升级的话 SimuKraft 解析不了旧状态、
 * 建造时会退化成空气；这里走与原版 {@code StructureTemplateManager} 相同的
 * {@code DataFixTypes.STRUCTURE} 升级路径。
 */
public final class StructureUpgrader {
  private StructureUpgrader() {}

  /** 当前游戏的 DataVersion。 */
  public static int currentDataVersion() {
    return SharedConstants.getCurrentVersion().getDataVersion().getVersion();
  }

  /**
   * 升级一个原版格式的结构标签；旧版本会被实际升级并追加提示，
   * 来自更新版本时无法降级、仅追加警告并原样返回。
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

  /** 缺失方块警告（最多列出前 5 个 id）。 */
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
