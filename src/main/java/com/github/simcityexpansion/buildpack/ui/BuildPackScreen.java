package com.github.simcityexpansion.buildpack.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import com.github.simcityexpansion.buildpack.BuildPack;
import com.github.simcityexpansion.buildpack.model.ImportScanner;
import com.github.simcityexpansion.buildpack.model.StructureFormat;
import com.lowdragmc.lowdraglib2.gui.holder.ModularUIScreen;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 建筑拓展包管理器界面：包装 {@link BuildPackView} 为 {@link ModularUIScreen}，
 * 并支持把结构文件 / zip 拓展包直接拖进窗口导入。
 */
public final class BuildPackScreen extends ModularUIScreen {

  private static final Logger LOGGER = LoggerFactory.getLogger(BuildPackScreen.class);

  private final BuildPackView view;

  private BuildPackScreen(BuildPackView view, ModularUI modularUI) {
    super(modularUI, Component.translatable("buildpack.title"));
    this.view = view;
  }

  /** 打开建筑拓展包管理器界面。 */
  public static void open() {
    BuildPackView view = new BuildPackView();
    UI ui = UI.of(view.root(), BuildPack.STYLESHEET);
    Minecraft.getInstance().setScreen(new BuildPackScreen(view, ModularUI.of(ui)));
  }

  /** 系统拖入文件：复制结构文件 / zip 到导入目录并刷新。 */
  @Override
  public void onFilesDrop(List<Path> paths) {
    Path importDir = ImportScanner.ensureImportDir();
    int copied = 0;
    for (Path path : paths) {
      if (!Files.isRegularFile(path) || !isImportable(path)) {
        continue;
      }
      try {
        Files.copy(path, uniqueTarget(importDir, path.getFileName().toString()));
        copied++;
      } catch (IOException e) {
        LOGGER.warn("BuildPack: 拖入文件复制失败 {}", path, e);
      }
    }
    if (copied > 0) {
      view.refresh();
      view.showMessage(Component.translatable("buildpack.msg.files_dropped", copied), false);
    }
  }

  private static boolean isImportable(Path path) {
    String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
    return StructureFormat.byFileName(name).isPresent() || name.endsWith(".zip");
  }

  /** 目标重名时追加 _2/_3…。 */
  private static Path uniqueTarget(Path dir, String fileName) {
    Path target = dir.resolve(fileName);
    if (!Files.exists(target)) {
      return target;
    }
    int dot = fileName.lastIndexOf('.');
    String base = dot > 0 ? fileName.substring(0, dot) : fileName;
    String extension = dot > 0 ? fileName.substring(dot) : "";
    int suffix = 2;
    while (Files.exists(target)) {
      target = dir.resolve(base + "_" + suffix++ + extension);
    }
    return target;
  }
}
