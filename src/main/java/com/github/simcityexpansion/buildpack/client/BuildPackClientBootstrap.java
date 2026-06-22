package com.github.simcityexpansion.buildpack.client;

import com.github.simcityexpansion.buildpack.ui.BuildPackScreen;
import net.minecraft.client.Minecraft;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Client bootstrap: registers key mappings (mod bus) and polls key input (game bus) during mod
 * construction.
 *
 * <p>This class is only referenced on the client (the main mod class calls {@link #register} under
 * {@code Dist.CLIENT}), so its client-only imports are never loaded on a dedicated server. Compared
 * to {@code @EventBusSubscriber}, this approach routes different events precisely to their
 * respective buses without relying on the deprecated {@code bus()} parameter.
 */
public final class BuildPackClientBootstrap {
  private BuildPackClientBootstrap() {}

  /** Called by the main mod class in the client environment, passing in the mod event bus. */
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
