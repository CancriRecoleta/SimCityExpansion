package com.github.simcityexpansion;

import com.github.simcityexpansion.buildpack.client.BuildPackClientBootstrap;
import com.github.simcityexpansion.buildpack.command.BuildPackCommands;
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
    // /buildpack 管理命令为端中立功能（单人与专用服务器皆可用）。
    NeoForge.EVENT_BUS.addListener(BuildPackCommands::onRegisterCommands);

    // 管理界面与按键为纯客户端功能，仅在客户端环境下注册。
    if (FMLEnvironment.dist == Dist.CLIENT) {
      BuildPackClientBootstrap.register(modEventBus);
    }
  }
}
