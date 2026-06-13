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
 * 底部双行（Litematica 式按钮区在左下，按钮高 20）：
 * 第一行为上下文操作 [安装][全部安装][卸载][删除文件][导出]；
 * 第二行为工具与状态 [刷新][打开目录] 计数 消息 提示 [关闭]。
 */
public final class BottomBar {

  private final UIElement root;
  private final Button installButton;
  private final Button batchButton;
  private final Button uninstallButton;
  private final Button deleteButton;
  private final Button exportButton;
  private final Label countLabel;
  private final Label messageLabel;

  public BottomBar(BuildPackView view) {
    root = new UIElement();
    root.addClass(BuildPack.cls("status"));
    root.layout(layout -> layout.flexDirection(FlexDirection.COLUMN)
        .gapRow(2.0f).widthStretch());

    installButton = action("install", view::runInstall);
    batchButton = action("batch", view::runBatchInstall);
    uninstallButton = action("uninstall", view::runUninstall);
    deleteButton = action("delete", view::runDelete);
    exportButton = action("export", view::runExport);
    Button captureButton = action("capture", view::runCaptureSelection);

    UIElement actionsRow = new UIElement();
    actionsRow.layout(layout -> layout.flexDirection(FlexDirection.ROW)
        .alignItems(AlignItems.CENTER).gapColumn(4.0f).widthStretch().height(22.0f));
    actionsRow.addChildren(
        installButton, batchButton, uninstallButton, deleteButton, exportButton, captureButton);

    Button refresh = action("refresh", view::refresh);
    Button openFolder = action("open_folder", view::openFolder);
    Button close = action("close", view::close);

    // LDLib2 的 Label 默认文本是字符串 "Label"，不清空会在界面上露出来。
    countLabel = new Label();
    countLabel.addClass(BuildPack.cls("status-count"));
    countLabel.setValue(Component.empty());

    messageLabel = new Label();
    messageLabel.addClass(BuildPack.cls("status-message"));
    messageLabel.setValue(Component.empty());
    messageLabel.layout(layout -> layout.flexGrow(1.0f));

    Label hint = new Label();
    hint.addClass(BuildPack.cls("status-hint"));
    hint.setValue(Component.translatable("buildpack.status.hint"));

    UIElement utilityRow = new UIElement();
    utilityRow.layout(layout -> layout.flexDirection(FlexDirection.ROW)
        .alignItems(AlignItems.CENTER).gapColumn(4.0f).widthStretch().height(22.0f));
    utilityRow.addChildren(refresh, openFolder, countLabel, messageLabel, hint, close);

    root.addChildren(actionsRow, utilityRow);
    setActions(false, false);
    setBatchEnabled(false);
    setDeleteEnabled(false);
    setExportEnabled(false);
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

  /** 设置「全部安装」可用状态（仅导入页签且列表非空）。 */
  public void setBatchEnabled(boolean enabled) {
    batchButton.setActive(enabled);
  }

  /** 设置「删除文件」可用状态（仅导入页签且选中了文件）。 */
  public void setDeleteEnabled(boolean enabled) {
    deleteButton.setActive(enabled);
  }

  /** 设置导出按钮可用状态（仅「已安装」页签且有建筑时可用）。 */
  public void setExportEnabled(boolean enabled) {
    exportButton.setActive(enabled);
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

  /** 构建一个带本地化文本与悬停提示的操作按钮。 */
  private Button action(String key, Runnable onClick) {
    Button button = new Button().setText(Component.translatable("buildpack.action." + key));
    button.addClass(BuildPack.cls("action"));
    button.style(style -> style.tooltips(Component.translatable("buildpack.tooltip." + key)));
    button.setOnClick(event -> onClick.run());
    return button;
  }
}
