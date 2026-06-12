package com.github.simcityexpansion.buildpack.ui.component;

import com.github.simcityexpansion.buildpack.BuildPack;
import com.github.simcityexpansion.buildpack.ui.BuildPackView;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import dev.vfyjxf.taffy.style.AlignItems;
import dev.vfyjxf.taffy.style.FlexDirection;
import net.minecraft.network.chat.Component;

/**
 * 底部按钮行（Litematica 式：主操作按钮排在左下，按钮高 20）：
 * [安装][卸载][刷新][打开目录] + 计数/消息 + 右端 [关闭]。
 */
public final class BottomBar {

  private final UIElement root;
  private final Button installButton;
  private final Button uninstallButton;
  private final Label countLabel;
  private final Label messageLabel;

  public BottomBar(BuildPackView view) {
    root = new UIElement();
    root.addClass(BuildPack.cls("status"));
    root.layout(layout -> layout.flexDirection(FlexDirection.ROW).alignItems(AlignItems.CENTER)
        .gapColumn(4.0f).widthStretch().height(22.0f));

    installButton = action("buildpack.action.install", view::runInstall);
    uninstallButton = action("buildpack.action.uninstall", view::runUninstall);
    Button refresh = action("buildpack.action.refresh", view::refresh);
    Button openFolder = action("buildpack.action.open_folder", view::openImportFolder);
    Button close = action("buildpack.action.close", view::close);

    // LDLib2 的 Label 默认文本是字符串 "Label"，不清空会在界面上露出来。
    countLabel = new Label();
    countLabel.addClass(BuildPack.cls("status-count"));
    countLabel.setValue(Component.empty());

    messageLabel = new Label();
    messageLabel.addClass(BuildPack.cls("status-message"));
    messageLabel.setValue(Component.empty());
    messageLabel.layout(layout -> layout.flexGrow(1.0f));

    root.addChildren(installButton, uninstallButton, refresh, openFolder,
        countLabel, messageLabel, close);
    setActions(false, false);
  }

  /** 返回栏根元素。 */
  public UIElement root() {
    return root;
  }

  /** 设置安装/卸载按钮可用状态。 */
  public void setActions(boolean installEnabled, boolean uninstallEnabled) {
    installButton.setActive(installEnabled);
    uninstallButton.setActive(uninstallEnabled);
  }

  /** 更新计数文本。 */
  public void setCount(Component count) {
    countLabel.setValue(count);
  }

  /** 显示一条状态消息；{@code error} 为 true 时套用错误配色。 */
  public void setMessage(Component message, boolean error) {
    messageLabel.setValue(message);
    messageLabel.removeClass(BuildPack.cls("status-error"));
    if (error) {
      messageLabel.addClass(BuildPack.cls("status-error"));
    }
  }

  private Button action(String translationKey, Runnable onClick) {
    Button button = new Button().setText(Component.translatable(translationKey));
    button.addClass(BuildPack.cls("action"));
    button.setOnClick(event -> onClick.run());
    return button;
  }
}
