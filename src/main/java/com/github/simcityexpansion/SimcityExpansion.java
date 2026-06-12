package com.github.simcityexpansion;

import com.github.simcityexpansion.buildpack.client.BuildPackClientBootstrap;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;

@Mod(SimcityExpansion.MODID)
public class SimcityExpansion {
  public static final String MODID = "simcity_expansion";

  public SimcityExpansion(IEventBus modEventBus, ModContainer modContainer) {

    if (FMLEnvironment.dist == Dist.CLIENT) {
      BuildPackClientBootstrap.register(modEventBus);
    }
  }
}
