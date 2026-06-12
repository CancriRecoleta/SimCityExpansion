package com.github.simcityexpansion.buildpack.model;

import java.nio.file.Path;
import java.util.Optional;

import com.github.simcityexpansion.buildpack.BuildPack;
import net.minecraft.network.chat.Component;

/**
 * SimuKraft 的五个固定建筑分类，对应 {@code simukraftbuilding/} 下的子目录名。
 *
 * <p>目录名为 SimuKraft 端硬编码（其 BuildingBuiltinResourceService 仅复制/扫描这五个），
 * 因此本枚举不可随意增删。
 */
public enum BuildingCategory {
  RESIDENTIAL("residential"),
  COMMERCIAL("commercial"),
  INDUSTRY("industry"),
  PUBLIC("public"),
  OTHER("other");

  private final String dirName;

  BuildingCategory(String dirName) {
    this.dirName = dirName;
  }

  /** SimuKraft 建筑根目录下的子目录名。 */
  public String dirName() {
    return dirName;
  }

  /** 本分类的目标安装目录（{@code <游戏目录>/simukraftbuilding/<dirName>}）。 */
  public Path dir() {
    return BuildPack.simukraftDir().resolve(dirName);
  }

  /** 本地化显示名。 */
  public Component displayName() {
    return Component.translatable("buildpack.category." + dirName);
  }

  /** 按目录名（忽略大小写）解析分类。 */
  public static Optional<BuildingCategory> byDirName(String name) {
    for (BuildingCategory category : values()) {
      if (category.dirName.equalsIgnoreCase(name)) {
        return Optional.of(category);
      }
    }
    return Optional.empty();
  }
}
