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
 * Makes <b>remote</b> (server-pushed) active build-pack buildings show up in SimuKraft's build menu
 * on a dedicated-server client. Locally activated packs need no help here — their cache zips are
 * part of the client-side package scan (via {@code BuildingPackageCatalogMixin}) — but a client
 * connected to a dedicated server has no local copy of the server's cache package, so the entries
 * synced by {@code ActivePacksSyncPacket} are appended for display; building resolves server-side.
 */
@Mixin(BuildingCacheService.class)
abstract class BuildingCacheServiceMixin {

  @ModifyReturnValue(
      method = "getBuildings(Ljava/lang/String;)Ljava/util/List;",
      at = @At("RETURN"))
  private static List<BuildingMeta> simcity_expansion$appendRemoteActivePacks(
      List<BuildingMeta> original, @Local(argsOnly = true) String category) {
    List<ActiveBuilding> remote = ActivePackProvider.remoteForCategory(category);
    if (remote.isEmpty()) {
      return original;
    }
    List<BuildingMeta> merged = new ArrayList<>(original);
    for (ActiveBuilding building : remote) {
      boolean clash = original.stream()
          .anyMatch(meta -> meta.metaFileName().equalsIgnoreCase(building.metaFileName()));
      if (!clash) {
        merged.add(new BuildingMeta(
            building.category(), building.displayName(), building.size(), building.amount(),
            building.author(), building.description(), building.metaFileName(),
            building.structureFileName(), building.packId()));
      }
    }
    return merged;
  }
}
