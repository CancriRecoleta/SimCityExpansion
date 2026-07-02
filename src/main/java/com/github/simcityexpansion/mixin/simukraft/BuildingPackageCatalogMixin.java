package com.github.simcityexpansion.mixin.simukraft;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.github.simcityexpansion.buildpack.integration.ActivePackProvider;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.sugar.Local;
import common.cn.kafei.simukraft.building.BuildingPackageCatalog;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Splices this mod's <b>active</b> build-pack cache zips into SimuKraft 2.0's package scan without
 * writing anything into {@code simukraftbuilding/}. {@code listPackages} is the single chokepoint:
 * every scanned package flows into the catalog snapshot that {@code listBuildings}/{@code
 * findBuilding} serve, and the construction, preview, and commercial/industrial definition paths
 * all read entries through the resulting {@code PackageSource} — so appending the cache zips here
 * makes active buildings listable, resolvable, buildable, and definition-complete in one shot.
 *
 * <p>The cache zips are <b>prepended</b>: SimuKraft merges packages in scan order with
 * later-scanned packages overwriting earlier ones on a base-name clash, so putting the cache
 * packages first keeps installed packages authoritative (activation never hides installed
 * buildings). Activation changes take effect via {@code BuildingCatalog.reload()}, which this mod
 * triggers through its integration bridge.
 */
@Mixin(BuildingPackageCatalog.class)
abstract class BuildingPackageCatalogMixin {

  @ModifyReturnValue(method = "listPackages", at = @At("RETURN"))
  private static List<Path> simcity_expansion$appendActivePacks(
      List<Path> original, @Local(argsOnly = true) Path rootDirectory) {
    // Only splice into the real game root; scanPackages() may target arbitrary directories.
    Path gameRoot = BuildingPackageCatalog.rootDirectory().toAbsolutePath().normalize();
    if (!rootDirectory.toAbsolutePath().normalize().equals(gameRoot)) {
      return original;
    }
    List<Path> cacheZips = ActivePackProvider.activeCacheZips();
    if (cacheZips.isEmpty()) {
      return original;
    }
    List<Path> merged = new ArrayList<>(cacheZips.size() + original.size());
    for (Path zip : cacheZips) {
      if (!merged.contains(zip) && !original.contains(zip)) {
        merged.add(zip);
      }
    }
    merged.addAll(original);
    return merged;
  }
}
