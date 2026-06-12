package com.github.simcityexpansion.buildpack.convert;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.network.chat.Component;

/** litematic → 原版 NBT 结构 的转换门面。 */
public final class LitematicConverter {
  private LitematicConverter() {}

  /** 体积超过该值时给出「结构过大」提醒（仍允许安装）。 */
  private static final long LARGE_VOLUME_WARNING = 2_000_000L;

  /** 读取 .litematic 并合并全部区域为单个原版结构。 */
  public static NbtStructure convert(Path litematic) throws IOException {
    return LitematicReader.readAndMerge(litematic);
  }

  /**
   * 安装前校验，返回需要展示给用户的警告（可为空）。
   * 旧版本 DataVersion 不再在这里告警——安装链路会用 {@link StructureUpgrader} 实际升级。
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
