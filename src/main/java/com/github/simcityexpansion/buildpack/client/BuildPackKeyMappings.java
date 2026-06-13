package com.github.simcityexpansion.buildpack.client;

import com.github.simcityexpansion.SimcityExpansion;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

/** 持有用于打开「建筑拓展包管理器」界面的按键绑定常量；注册逻辑见 {@link BuildPackClientBootstrap}。 */
public final class BuildPackKeyMappings {
  private BuildPackKeyMappings() {}

  /** 按键设置界面中的分组名。 */
  public static final String CATEGORY = "key.categories." + SimcityExpansion.MODID;

  /** 打开建筑拓展包管理器的按键，默认绑定到反斜杠（{@code \}）键。 */
  public static final KeyMapping OPEN_BUILDPACK = new KeyMapping(
      "key." + SimcityExpansion.MODID + ".buildpack", GLFW.GLFW_KEY_BACKSLASH, CATEGORY);

  /** 设置世界选区角点 A（默认 {@code [}）。 */
  public static final KeyMapping SET_CORNER_A = new KeyMapping(
      "key." + SimcityExpansion.MODID + ".corner_a", GLFW.GLFW_KEY_LEFT_BRACKET, CATEGORY);

  /** 设置世界选区角点 B（默认 {@code ]}）。 */
  public static final KeyMapping SET_CORNER_B = new KeyMapping(
      "key." + SimcityExpansion.MODID + ".corner_b", GLFW.GLFW_KEY_RIGHT_BRACKET, CATEGORY);

  /** 捕获选区导出为蓝图（默认 {@code '}）。 */
  public static final KeyMapping CAPTURE_SELECTION = new KeyMapping(
      "key." + SimcityExpansion.MODID + ".capture", GLFW.GLFW_KEY_APOSTROPHE, CATEGORY);
}
