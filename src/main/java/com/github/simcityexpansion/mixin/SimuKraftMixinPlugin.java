package com.github.simcityexpansion.mixin;

import java.util.List;
import java.util.Set;

import org.objectweb.asm.tree.ClassNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * Gates every SimuKraft mixin on SimuKraft actually being installed, so this mod keeps <b>no hard
 * dependency</b>: when SimuKraft is absent, all mixins are skipped and the mod still loads (its
 * file-system install path keeps working); when SimuKraft is present, the catalog mixins apply and
 * build packs can be served virtually.
 *
 * <p>Detection uses a class-resource probe (no class initialization, no dependency on FML's mod list
 * being ready) at mixin-config load time.
 */
public final class SimuKraftMixinPlugin implements IMixinConfigPlugin {
  private static final Logger LOGGER = LoggerFactory.getLogger("simcity_expansion/mixin");

  // SimuKraft's package is literally common.cn.kafei.simukraft.*, so the class resource path keeps
  // that "common/" prefix. BuildingPackageCatalog exists since SimuKraft 2.0 (the zip package
  // architecture this mod targets); older SimuKraft versions therefore leave the mixins disabled.
  private static final String PROBE_CLASS =
      "common/cn/kafei/simukraft/building/BuildingPackageCatalog.class";

  private boolean simukraftPresent;

  @Override
  public void onLoad(String mixinPackage) {
    simukraftPresent =
        SimuKraftMixinPlugin.class.getClassLoader().getResource(PROBE_CLASS) != null;
    LOGGER.info("SimCity Expansion: SimuKraft 2.0+ {}; building-package mixins {}.",
        simukraftPresent ? "detected" : "not found",
        simukraftPresent ? "enabled" : "disabled (zip install only)");
  }

  @Override
  public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
    return simukraftPresent;
  }

  @Override
  public String getRefMapperConfig() {
    return null;
  }

  @Override
  public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    // No-op.
  }

  @Override
  public List<String> getMixins() {
    return null;
  }

  @Override
  public void preApply(String targetClassName, ClassNode targetClass,
      String mixinClassName, IMixinInfo mixinInfo) {
    // No-op.
  }

  @Override
  public void postApply(String targetClassName, ClassNode targetClass,
      String mixinClassName, IMixinInfo mixinInfo) {
    // No-op.
  }
}
