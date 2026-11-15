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

    NeoForge.EVENT_BUS.addListener(BuildPackCommands::onRegisterCommands);

    NeoForge.EVENT_BUS.addListener(PackActivationService::onServerStarting);

    modEventBus.addListener(BuildPackNetwork::register);
    NeoForge.EVENT_BUS.addListener(BuildPackNetwork::onPlayerJoin);

    if (FMLEnvironment.dist == Dist.CLIENT) {
      BuildPackClientBootstrap.register(modEventBus, modContainer);
    }
  }
}
