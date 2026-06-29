package com.github.simcityexpansion.buildpack.client;

import java.util.List;

import com.github.simcityexpansion.buildpack.integration.ActiveBuilding;
import com.github.simcityexpansion.buildpack.integration.ActivePackProvider;
import com.github.simcityexpansion.buildpack.network.ActivePacksSyncPacket;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/** Applies a server's active-pack sync to the client's {@link ActivePackProvider} (remote layer). */
@OnlyIn(Dist.CLIENT)
public final class ActivePacksClientSync {
  private ActivePacksClientSync() {}

  public static void apply(ActivePacksSyncPacket packet) {
    // On a host (integrated server) the local provider already has the real entries (with cache
    // paths); do not overwrite them with path-less remote copies.
    if (Minecraft.getInstance().getSingleplayerServer() != null) {
      return;
    }
    List<ActiveBuilding> remote = packet.buildings().stream()
        .map(entry -> new ActiveBuilding(entry.category(), entry.displayName(), entry.size(),
            entry.amount(), entry.author(), entry.metaFileName(), entry.structureFileName(),
            null, null))
        .toList();
    ActivePackProvider.replaceRemote(remote);
  }
}
