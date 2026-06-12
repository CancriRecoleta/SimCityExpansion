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
}
