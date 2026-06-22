package com.github.simcityexpansion.buildpack.ui;

import net.minecraft.network.chat.Component;

/** Source tabs shown in the left panel. */
public enum SourceTab {
  /** Loose .nbt / .litematic files in the import directory. */
  IMPORT("import"),
  /** Zip build packs in the import directory. */
  PACKS("packs"),
  /** Buildings installed in the SimuKraft building directory. */
  INSTALLED("installed");

  private final String key;

  SourceTab(String key) {
    this.key = key;
  }

  /** Returns the localized tab name. */
  public Component displayName() {
    return Component.translatable("buildpack.tab." + key);
  }

  /** Returns the tab hover tooltip. */
  public Component tooltip() {
    return Component.translatable("buildpack.tooltip.tab." + key);
  }
}
