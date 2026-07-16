package com.github.simcityexpansion.buildpack.client;

import com.github.simcityexpansion.buildpack.ui.BuildPackScreen;
import net.minecraft.client.Minecraft;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
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
  public static void register(IEventBus modEventBus, ModContainer modContainer) {
    modEventBus.addListener(BuildPackClientBootstrap::onRegisterKeyMappings);
    NeoForge.EVENT_BUS.addListener(BuildPackClientBootstrap::onClientTick);
    NeoForge.EVENT_BUS.addListener(WorldSelection::onRenderLevelStage);
    NeoForge.EVENT_BUS.addListener(BuildPackClientBootstrap::onRenderGui);
    // Mods-list "Config" entry: opens the pack manager from the title screen (and the pause
    // menu), so pack developers can build and edit packs without entering a world.
    modContainer.registerExtensionPoint(IConfigScreenFactory.class,
        (container, modListScreen) -> new BuildPackScreen(modListScreen));
  }

  private static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
    event.register(BuildPackKeyMappings.OPEN_BUILDPACK);
    event.register(BuildPackKeyMappings.SET_CORNER_A);
    event.register(BuildPackKeyMappings.SET_CORNER_B);
    event.register(BuildPackKeyMappings.CAPTURE_SELECTION);
    event.register(BuildPackKeyMappings.TOGGLE_CONTENTS);
    event.register(BuildPackKeyMappings.CLEAR_SELECTION);
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
    while (BuildPackKeyMappings.TOGGLE_CONTENTS.consumeClick()) {
      WorldSelection.toggleBlockEntities();
    }
    while (BuildPackKeyMappings.CLEAR_SELECTION.consumeClick()) {
      WorldSelection.clearSelection();
    }
    WorldSelection.tickCapture();
  }

  private static void onRenderGui(RenderGuiEvent.Post event) {
    WorldSelection.renderHud(event.getGuiGraphics());
  }
}
