package com.github.simcityexpansion.buildpack.client;

import com.github.simcityexpansion.buildpack.ui.BuildPackScreen;
import net.minecraft.client.Minecraft;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * 客户端引导：在模组构造期把按键注册（模组总线）与按键轮询（游戏总线）挂到对应的事件总线上。
 *
 * <p>本类仅在客户端被引用（由主模组类在 {@code Dist.CLIENT} 下调用 {@link #register}），因此其
 * 客户端专属导入不会在专用服务器上被加载。相较 {@code @EventBusSubscriber}，这种方式能把不同事件
 * 精确路由到各自的总线，且不依赖已废弃的 {@code bus()} 参数。
 */
public final class BuildPackClientBootstrap {
  private BuildPackClientBootstrap() {}

  /** 由主模组类在客户端环境下调用，传入模组事件总线。 */
  public static void register(IEventBus modEventBus) {
    modEventBus.addListener(BuildPackClientBootstrap::onRegisterKeyMappings);
    NeoForge.EVENT_BUS.addListener(BuildPackClientBootstrap::onClientTick);
    NeoForge.EVENT_BUS.addListener(WorldSelection::onRenderLevelStage);
  }

  private static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
    event.register(BuildPackKeyMappings.OPEN_BUILDPACK);
    event.register(BuildPackKeyMappings.SET_CORNER_A);
    event.register(BuildPackKeyMappings.SET_CORNER_B);
    event.register(BuildPackKeyMappings.CAPTURE_SELECTION);
  }

  private static void onClientTick(ClientTickEvent.Post event) {
    Minecraft minecraft = Minecraft.getInstance();
    if (minecraft.player == null) {
      return;
    }
    while (BuildPackKeyMappings.OPEN_BUILDPACK.consumeClick()) {
      BuildPackScreen.open();
    }
    while (BuildPackKeyMappings.SET_CORNER_A.consumeClick()) {
      WorldSelection.setCornerA();
    }
    while (BuildPackKeyMappings.SET_CORNER_B.consumeClick()) {
      WorldSelection.setCornerB();
    }
    while (BuildPackKeyMappings.CAPTURE_SELECTION.consumeClick()) {
      minecraft.player.displayClientMessage(WorldSelection.capture().message(), false);
    }
  }
}
