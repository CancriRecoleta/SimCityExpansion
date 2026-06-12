package com.github.simcityexpansion.buildpack.ui;

import net.minecraft.network.chat.Component;

/** 左栏来源页签。 */
public enum SourceTab {
  /** 导入目录中的 .nbt / .litematic 散文件。 */
  IMPORT("import"),
  /** 导入目录中的 zip 拓展包。 */
  PACKS("packs"),
  /** SimuKraft 建筑目录中已安装的建筑。 */
  INSTALLED("installed");

  private final String key;

  SourceTab(String key) {
    this.key = key;
  }

  /** 本地化页签名。 */
  public Component displayName() {
    return Component.translatable("buildpack.tab." + key);
  }
}
