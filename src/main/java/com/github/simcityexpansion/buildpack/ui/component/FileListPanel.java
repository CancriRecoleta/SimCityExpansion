package com.github.simcityexpansion.buildpack.ui.component;

import java.util.function.Consumer;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.util.TreeNode;

/**
 * 文件列表面板（Litematica 浏览器左栏）：包装自绘的 {@link TreeView}，
 * 叶子内容为 ImportFile / PackArchive / InstalledBuilding，选中时回调宿主视图。
 *
 * <p>不再使用 ldlib2 的 TreeList + ScrollerView（其节点宽度塌缩导致文字不显示），
 * 改用 {@link TreeView} 原生自绘并自管理滚动。
 */
public final class FileListPanel {

  private final TreeView treeView;

  /**
   * @param onSelect 选中叶子时回调其内容；选中分支或清空选择时回调 {@code null}
   */
  public FileListPanel(Consumer<Object> onSelect) {
    treeView = new TreeView(onSelect);
  }

  /** 替换整棵树并刷新。 */
  public void setRoot(TreeNode<String, Object> root) {
    treeView.setRoot(root);
  }

  /** 返回内部的自绘树元素，供布局挂载。 */
  public UIElement element() {
    return treeView;
  }
}
