package com.github.simcityexpansion.buildpack.client;

import com.github.simcityexpansion.SimcityExpansion;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

/** Holds key binding constants for opening the "build pack manager" screen; registration is handled by {@link BuildPackClientBootstrap}. */
public final class BuildPackKeyMappings {
  private BuildPackKeyMappings() {}

  /** Category name displayed in the key bindings settings screen. */
  public static final String CATEGORY = "key.categories." + SimcityExpansion.MODID;

  /** Key mapping to open the build pack manager, bound to the backslash ({@code \}) key by default. */
  public static final KeyMapping OPEN_BUILDPACK = new KeyMapping(
      "key." + SimcityExpansion.MODID + ".buildpack", GLFW.GLFW_KEY_BACKSLASH, CATEGORY);

  /** Key mapping to set world selection corner A (default: {@code [}). */
  public static final KeyMapping SET_CORNER_A = new KeyMapping(
      "key." + SimcityExpansion.MODID + ".corner_a", GLFW.GLFW_KEY_LEFT_BRACKET, CATEGORY);

  /** Key mapping to set world selection corner B (default: {@code ]}). */
  public static final KeyMapping SET_CORNER_B = new KeyMapping(
      "key." + SimcityExpansion.MODID + ".corner_b", GLFW.GLFW_KEY_RIGHT_BRACKET, CATEGORY);

  /** Key mapping to capture the selection and export it as a blueprint (default: {@code '}). */
  public static final KeyMapping CAPTURE_SELECTION = new KeyMapping(
      "key." + SimcityExpansion.MODID + ".capture", GLFW.GLFW_KEY_APOSTROPHE, CATEGORY);
}
