package com.github.simcityexpansion.buildpack.network;

import java.util.List;

import com.github.simcityexpansion.buildpack.integration.ActivePackProvider;
import com.github.simcityexpansion.buildpack.network.ActivePacksSyncPacket.Entry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/** Networking for build-pack activation: syncs the active building set from the server to clients. */
public final class BuildPackNetwork {
  private BuildPackNetwork() {}

  /** modEventBus: register the sync payload (optional so clients without this mod can still connect). */
  public static void register(RegisterPayloadHandlersEvent event) {
    PayloadRegistrar registrar = event.registrar("1").optional();
    registrar.playToClient(ActivePacksSyncPacket.TYPE, ActivePacksSyncPacket.STREAM_CODEC,
        ActivePacksSyncPacket::handle);
  }

  /** game bus: push the current active set to a player as they join. */
  public static void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
    if (event.getEntity() instanceof ServerPlayer player) {
      PacketDistributor.sendToPlayer(player, snapshot());
    }
  }

  /** Broadcasts the current active set to all connected players; no-op when no server is running. */
  public static void broadcast() {
    MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
    if (server == null) {
      return;
    }
    server.execute(() -> PacketDistributor.sendToAllPlayers(snapshot()));
  }

  private static ActivePacksSyncPacket snapshot() {
    List<Entry> entries = ActivePackProvider.allActive().stream()
        .map(building -> new Entry(building.category(), building.displayName(), building.size(),
            building.amount(), building.author(), building.metaFileName(),
            building.structureFileName()))
        .toList();
    return new ActivePacksSyncPacket(entries);
  }
}
