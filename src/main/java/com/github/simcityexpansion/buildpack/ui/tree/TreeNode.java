package com.github.simcityexpansion.buildpack.ui.tree;

import java.util.ArrayList;
import java.util.List;

/**
 * Lightweight generic tree node (replaces the original ldlib2 TreeNode): branch
 * nodes have {@code null} content; leaf nodes carry the corresponding data object.
 * Pure data structure with no dependency on any UI framework.
 *
 * @param <K> node key (display string)
 * @param <V> node content type
 */
public final class TreeNode<K, V> {
  private final K key;
  private V content;
  private final List<TreeNode<K, V>> children = new ArrayList<>();

  public TreeNode(K key, V content) {
    this.key = key;
    this.content = content;
  }

  /** Returns the node key (display text). */
  public K getKey() {
    return key;
  }

  /** Returns the node content ({@code null} for branches, a data object for leaves). */
  public V getContent() {
    return content;
  }

  /** Sets the node content (used to attach optional data to a branch, such as a pack object). */
  public void setContent(V content) {
    this.content = content;
  }

  /** Returns the mutable child node list. */
  public List<TreeNode<K, V>> getChildren() {
    return children;
  }

  /** Appends a child node. */
  public void addChild(TreeNode<K, V> child) {
    children.add(child);
  }

  /** Finds a direct child node by key; returns {@code null} if not found. */
  public TreeNode<K, V> findChild(K childKey) {
    for (TreeNode<K, V> child : children) {
      if (child.key.equals(childKey)) {
        return child;
      }
    }
    return null;
  }
}
