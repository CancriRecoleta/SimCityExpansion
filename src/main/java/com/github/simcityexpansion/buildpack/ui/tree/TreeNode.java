package com.github.simcityexpansion.buildpack.ui.tree;

import java.util.ArrayList;
import java.util.List;

/**
 * 轻量泛型树节点（替代原先 ldlib2 的 TreeNode）：分支节点 content 为 {@code null}，
 * 叶子 content 为对应数据对象。纯数据结构，不依赖任何 UI 框架。
 *
 * @param <K> 节点键（显示用字符串）
 * @param <V> 节点内容类型
 */
public final class TreeNode<K, V> {
  private final K key;
  private V content;
  private final List<TreeNode<K, V>> children = new ArrayList<>();

  public TreeNode(K key, V content) {
    this.key = key;
    this.content = content;
  }

  /** 节点键（显示文本）。 */
  public K getKey() {
    return key;
  }

  /** 节点内容（分支为 {@code null}，叶子为数据对象）。 */
  public V getContent() {
    return content;
  }

  /** 设置节点内容（用于给分支挂载可选数据，如整包对象）。 */
  public void setContent(V content) {
    this.content = content;
  }

  /** 子节点列表（可变）。 */
  public List<TreeNode<K, V>> getChildren() {
    return children;
  }

  /** 追加一个子节点。 */
  public void addChild(TreeNode<K, V> child) {
    children.add(child);
  }

  /** 按键查找直接子节点；不存在返回 {@code null}。 */
  public TreeNode<K, V> findChild(K childKey) {
    for (TreeNode<K, V> child : children) {
      if (child.key.equals(childKey)) {
        return child;
      }
    }
    return null;
  }
}
