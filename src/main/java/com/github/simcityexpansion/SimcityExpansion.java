package com.github.simcityexpansion;

import com.github.simcityexpansion.buildpack.client.BuildPackClientBootstrap;
import com.github.simcityexpansion.buildpack.command.BuildPackCommands;
import com.github.simcityexpansion.buildpack.integration.PackActivationService;
import com.github.simcityexpansion.buildpack.network.BuildPackNetwork;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;

@Mod(SimcityExpansion.MODID)
public class SimcityExpansion {
  public static final String MODID = "simcity_expansion";

  public SimcityExpansion(IEventBus modEventBus, ModContainer modContainer) {
    // The /buildpack admin command is side-neutral (available in both singleplayer and on a dedicated server).
    NeoForge.EVENT_BUS.addListener(BuildPackCommands::onRegisterCommands);

    // Re-apply persisted active build packs when the server (integrated or dedicated) starts, so
    // they are served to SimuKraft without being installed into its building directory.
    NeoForge.EVENT_BUS.addListener(PackActivationService::onServerStarting);

    // Sync the active building set to players (so dedicated-server clients see and can build them).
    modEventBus.addListener(BuildPackNetwork::register);
    NeoForge.EVENT_BUS.addListener(BuildPackNetwork::onPlayerJoin);

    // The management screen and key mappings are client-only features, registered only in a client environment.
    if (FMLEnvironment.dist == Dist.CLIENT) {
      BuildPackClientBootstrap.register(modEventBus);
    }
  }
}
