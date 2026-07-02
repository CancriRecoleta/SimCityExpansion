package com.github.simcityexpansion.buildpack.integration;

import net.minecraft.server.MinecraftServer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The only place this mod calls into SimuKraft 2.0 directly. Whenever the zip packages under
 * {@code simukraftbuilding/} change (install, uninstall, migration, pack activation), SimuKraft's
 * caches must be refreshed the same way its own {@code /simukraft reload} command does:
 * re-scan the package catalog, drop the commercial/industrial definition caches, and tell connected
 * clients to rebuild their build-menu cache.
 *
 * <p>SimuKraft is an <b>optional</b> dependency: the SimuKraft imports live in nested holder
 * classes that are only class-loaded after the presence probe succeeds, so this class itself is
 * always safe to load. All calls are best-effort — a failure is logged, never thrown.
 */
public final class SimukraftBridge {
  private SimukraftBridge() {}

  private static final Logger LOGGER = LoggerFactory.getLogger(SimukraftBridge.class);

  /** Class-resource probe for SimuKraft 2.0's zip package catalog (also used by the mixin plugin). */
  public static final String PROBE_CLASS =
      "common/cn/kafei/simukraft/building/BuildingPackageCatalog.class";

  private static final boolean PRESENT =
      SimukraftBridge.class.getClassLoader().getResource(PROBE_CLASS) != null;

  /** Whether SimuKraft 2.0 (zip package architecture) is installed. */
  public static boolean present() {
    return PRESENT;
  }

  /**
   * Asks SimuKraft to rescan {@code simukraftbuilding/*.zip} and refresh every dependent cache.
   * Safe to call from any thread and on both dists; a no-op when SimuKraft is absent.
   */
  public static void requestCatalogReload() {
    if (!PRESENT) {
      return;
    }
    try {
      CommonHolder.reload();
    } catch (Throwable t) {
      LOGGER.warn("SimCity Expansion: failed to trigger a SimuKraft catalog reload.", t);
    }
    if (FMLEnvironment.dist == Dist.CLIENT) {
      try {
        ClientHolder.reload();
      } catch (Throwable t) {
        LOGGER.warn("SimCity Expansion: failed to reload the SimuKraft client building cache.", t);
      }
    }
  }

  /** Server/common side: catalog + definition caches, then notify connected clients. */
  private static final class CommonHolder {
    static void reload() {
      common.cn.kafei.simukraft.building.BuildingCatalog.reload();
      common.cn.kafei.simukraft.commercial.CommercialDefinitionLoader.clearCache();
      common.cn.kafei.simukraft.industrial.IndustrialDefinitionLoader.clearCache();
      MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
      if (server != null) {
        server.execute(() -> PacketDistributor.sendToAllPlayers(
            new common.cn.kafei.simukraft.network.building.BuildingCacheReloadPacket()));
      }
    }
  }

  /**
   * Client side: rebuild the build-menu cache directly. Covers the cases the server packet cannot
   * reach — the integrated-server GUI before any packet round-trip, and a client that edits its
   * local packages while connected to a remote server.
   */
  private static final class ClientHolder {
    static void reload() {
      net.minecraft.client.Minecraft.getInstance().execute(
          client.cn.kafei.simukraft.client.buildbox.BuildingCacheService::reload);
    }
  }
}
