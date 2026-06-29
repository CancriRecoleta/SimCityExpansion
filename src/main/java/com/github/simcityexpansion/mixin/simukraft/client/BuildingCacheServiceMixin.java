package com.github.simcityexpansion.mixin.simukraft.client;

import java.util.ArrayList;
import java.util.List;

import com.github.simcityexpansion.buildpack.integration.ActiveBuilding;
import com.github.simcityexpansion.buildpack.integration.ActivePackProvider;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.sugar.Local;
import client.cn.kafei.simukraft.client.buildbox.BuildingCacheService;
import client.cn.kafei.simukraft.client.buildbox.BuildingCacheService.BuildingMeta;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Client counterpart of {@link com.github.simcityexpansion.mixin.simukraft.BuildingCatalogMixin}:
 * makes active build-pack buildings show up in SimuKraft's build menu. The client keeps its own
 * directory scan ({@code BuildingCacheService}); appending to {@code getBuildings} on every call
 * reflects activation/deactivation immediately without poking its cache.
 */
@Mixin(BuildingCacheService.class)
abstract class BuildingCacheServiceMixin {

  @ModifyReturnValue(
      method = "getBuildings(Ljava/lang/String;)Ljava/util/List;",
      at = @At("RETURN"))
  private static List<BuildingMeta> simcity_expansion$appendActivePacks(
      List<BuildingMeta> original, @Local(argsOnly = true) String category) {
    List<ActiveBuilding> active = ActivePackProvider.forCategory(category);
    if (active.isEmpty()) {
      return original;
    }
    List<BuildingMeta> merged = new ArrayList<>(original);
    for (ActiveBuilding building : active) {
      boolean clash = original.stream()
          .anyMatch(meta -> meta.metaFileName().equalsIgnoreCase(building.metaFileName()));
      if (!clash) {
        merged.add(new BuildingMeta(
            building.category(), building.displayName(), building.size(), building.amount(),
            building.author(), building.metaFileName(), building.structureFileName()));
      }
    }
    return merged;
  }
}
