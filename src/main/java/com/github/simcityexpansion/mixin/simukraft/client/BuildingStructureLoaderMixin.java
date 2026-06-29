package com.github.simcityexpansion.mixin.simukraft.client;

import java.nio.file.Path;

import com.github.simcityexpansion.buildpack.integration.ActiveBuilding;
import com.github.simcityexpansion.buildpack.integration.ActivePackProvider;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import client.cn.kafei.simukraft.client.buildbox.BuildingCacheService.BuildingMeta;
import client.cn.kafei.simukraft.client.buildbox.BuildingStructureLoader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Makes the client-side preview of an active build-pack building read from this mod's cache instead
 * of {@code simukraftbuilding/}. SimuKraft computes the structure path as
 * {@code categoryDirectory(meta.category()).resolve(meta.structureFileName())}; for an active
 * building we swap that resolved {@link Path} for our cache path and let SimuKraft's own parsing
 * proceed unchanged. This is the only {@code Path.resolve(String)} call in {@code load(...)}.
 */
@Mixin(BuildingStructureLoader.class)
abstract class BuildingStructureLoaderMixin {

  @ModifyExpressionValue(
      method = "load",
      at = @At(
          value = "INVOKE",
          target = "Ljava/nio/file/Path;resolve(Ljava/lang/String;)Ljava/nio/file/Path;"),
      require = 1)
  private static Path simcity_expansion$redirectToCache(
      Path original, @Local(argsOnly = true) BuildingMeta meta) {
    return ActivePackProvider.findByStructureFile(meta.category(), meta.structureFileName())
        .map(ActiveBuilding::structurePath)
        .orElse(original);
  }
}
