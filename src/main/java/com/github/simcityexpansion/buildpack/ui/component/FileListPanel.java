package com.github.simcityexpansion.buildpack.ui.component;

import java.util.Set;
import java.util.function.Consumer;

import com.github.simcityexpansion.buildpack.BuildPack;
import com.github.simcityexpansion.buildpack.ui.DirectoryTree;
import com.github.simcityexpansion.buildpack.ui.UiIcons;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TreeList;
import com.lowdragmc.lowdraglib2.gui.util.TreeBuilder;
import com.lowdragmc.lowdraglib2.gui.util.TreeNode;
import net.minecraft.network.chat.Component;

/**
 * 文件列表面板（Litematica 浏览器左栏）：包装一个 {@link TreeList}，
 * 叶子内容为 ImportFile / PackArchive / InstalledBuilding，选中时回调宿主视图。
 */
public final class FileListPanel {

  private final TreeList<TreeNode<String, Object>> treeList;

  /**
   * @param onSelect 选中叶子时回调其内容；选中分支或清空选择时回调 {@code null}
   */
  public FileListPanel(Consumer<Object> onSelect) {
    treeList = new TreeList<>(
        TreeBuilder.<String, Object>start(DirectoryTree.ROOT_KEY).build(), true);
    treeList.addClass(BuildPack.cls("tree"));
    treeList.setNodeUISupplier(TreeList.iconTextTemplate(
        node -> UiIcons.node(node.getContent()),
        node -> Component.literal(node.getKey())));
    treeList.setSupportMultipleSelection(false);
    treeList.setOnSelectedChanged(selected -> onSelect.accept(firstContent(selected)));
    treeList.layout(layout -> layout.widthStretch().flexGrow(1.0f));
  }

  /** 替换整棵树并刷新。 */
  public void setRoot(TreeNode<String, Object> root) {
    treeList.setRoot(root);
    treeList.reloadList();
  }

  /** 返回内部的 {@link TreeList} 元素，供布局挂载。 */
  public TreeList<TreeNode<String, Object>> element() {
    return treeList;
  }

  private static Object firstContent(Set<TreeNode<String, Object>> selected) {
    return selected.stream().findFirst().map(TreeNode::getContent).orElse(null);
  }
}
