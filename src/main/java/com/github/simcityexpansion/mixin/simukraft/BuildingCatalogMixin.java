package com.github.simcityexpansion.mixin.simukraft;

import java.util.ArrayList;
import java.util.List;

import com.github.simcityexpansion.buildpack.integration.ActiveBuilding;
import com.github.simcityexpansion.buildpack.integration.ActivePackProvider;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.sugar.Local;
import common.cn.kafei.simukraft.building.BuildingCatalog;
import common.cn.kafei.simukraft.building.BuildingCatalog.BuildingDefinition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Splices this mod's <b>active</b> build-pack buildings into SimuKraft's catalog without writing any
 * file into {@code simukraftbuilding/}. {@code listBuildings} is the single chokepoint —
 * {@code findBuilding}/{@code findBuildingByStructureFile} both delegate to it, and the construction,
 * economy, rent and task-recovery paths all resolve through {@code findBuilding} — so appending here
 * makes active buildings listable, resolvable, and buildable in one shot.
 *
 * <p>Why {@code @ModifyReturnValue} (not editing the cache): SimuKraft caches {@code listBuildings}
 * in a never-cleared map, but the handler runs on <em>every</em> call after that cache returns, so
 * activation/deactivation takes effect immediately without touching SimuKraft's cache. The returned
 * {@link BuildingDefinition} carries our cache paths, and SimuKraft's loader reads
 * {@code definition.structurePath()} directly — so the structure need not live in its directory.
 */
@Mixin(BuildingCatalog.class)
abstract class BuildingCatalogMixin {

  @ModifyReturnValue(
      method = "listBuildings(Ljava/lang/String;)Ljava/util/List;",
      at = @At("RETURN"))
  private static List<BuildingDefinition> simcity_expansion$appendActivePacks(
      List<BuildingDefinition> original, @Local(argsOnly = true) String category) {
    List<ActiveBuilding> active = ActivePackProvider.forCategory(category);
    if (active.isEmpty()) {
      return original;
    }
    List<BuildingDefinition> merged = new ArrayList<>(original);
    for (ActiveBuilding building : active) {
      // Skip remote (server-pushed) entries that carry no local file: they are display-only on the
      // client and would not be loadable here. The server's own entries always have a cache path.
      if (building.structurePath() == null) {
        continue;
      }
      // Disk buildings stay authoritative on a name clash; our names are pack-namespaced anyway.
      boolean clash = original.stream()
          .anyMatch(definition -> definition.metaFileName().equalsIgnoreCase(building.metaFileName()));
      if (!clash) {
        merged.add(new BuildingDefinition(
            building.category(), building.displayName(), building.size(), building.amount(),
            building.author(), building.metaFileName(), building.structureFileName(),
            building.metaPath(), building.structurePath()));
      }
    }
    return merged;
  }
}
