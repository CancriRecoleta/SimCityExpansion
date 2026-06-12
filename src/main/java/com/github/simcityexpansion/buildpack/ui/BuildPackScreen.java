package com.github.simcityexpansion.buildpack.ui;

import com.github.simcityexpansion.buildpack.BuildPack;
import com.lowdragmc.lowdraglib2.gui.holder.ModularUIScreen;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * 建筑拓展包管理器界面入口：构建 {@link BuildPackView}，包装为 {@link ModularUI}
 * 并作为纯客户端 {@link ModularUIScreen} 打开（无需容器菜单）。
 */
public final class BuildPackScreen {
  private BuildPackScreen() {}

  /** 打开建筑拓展包管理器界面。 */
  public static void open() {
    BuildPackView view = new BuildPackView();
    UI ui = UI.of(view.root(), BuildPack.STYLESHEET);
    ModularUI modularUI = ModularUI.of(ui);
    Minecraft.getInstance().setScreen(
        new ModularUIScreen(modularUI, Component.translatable("buildpack.title")));
  }
}
