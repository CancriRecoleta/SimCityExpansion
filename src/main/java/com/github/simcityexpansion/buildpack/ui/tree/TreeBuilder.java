package com.github.simcityexpansion.buildpack.ui.tree;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer;

/**
 * {@link TreeNode} 的流式构建器（替代原先 ldlib2 的 TreeBuilder）：维护一个「当前分支」
 * 栈，既支持有状态写法（{@link #startBranch}/{@link #content}/{@link #endBranch}），
 * 也支持函数式写法（{@link #branch}/{@link #diveBranch}/{@link #leaf}）。
 *
 * @param <K> 节点键
 * @param <V> 节点内容类型
 */
public final class TreeBuilder<K, V> {
  private final TreeNode<K, V> root;
  private final Deque<TreeNode<K, V>> stack = new ArrayDeque<>();

  private TreeBuilder(K rootKey) {
    root = new TreeNode<>(rootKey, null);
    stack.push(root);
  }

  /** 以给定根键开始构建。 */
  public static <K, V> TreeBuilder<K, V> start(K rootKey) {
    return new TreeBuilder<>(rootKey);
  }

  /** 在当前分支下追加一个叶子。 */
  public TreeBuilder<K, V> leaf(K key, V value) {
    current().addChild(new TreeNode<>(key, value));
    return this;
  }

  /** 开启一个新分支并进入它（content 默认 {@code null}）。 */
  public TreeBuilder<K, V> startBranch(K key) {
    TreeNode<K, V> branch = new TreeNode<>(key, null);
    current().addChild(branch);
    stack.push(branch);
    return this;
  }

  /** 给当前分支设置内容（如把整包对象挂到包分支上）。 */
  public TreeBuilder<K, V> content(V value) {
    current().setContent(value);
    return this;
  }

  /** 结束当前分支，回到上一层。 */
  public TreeBuilder<K, V> endBranch() {
    stack.pop();
    return this;
  }

  /** 创建一个分支并在其中执行 {@code populate}。 */
  public TreeBuilder<K, V> branch(K key, Consumer<TreeBuilder<K, V>> populate) {
    startBranch(key);
    populate.accept(this);
    return endBranch();
  }

  /** 沿 {@code keys} 路径逐级查找或创建分支，在最深层执行 {@code populate}（同名分支复用）。 */
  public TreeBuilder<K, V> diveBranch(List<K> keys, Consumer<TreeBuilder<K, V>> populate) {
    int pushed = 0;
    for (K key : keys) {
      TreeNode<K, V> child = current().findChild(key);
      if (child == null) {
        child = new TreeNode<>(key, null);
        current().addChild(child);
      }
      stack.push(child);
      pushed++;
    }
    populate.accept(this);
    for (int i = 0; i < pushed; i++) {
      stack.pop();
    }
    return this;
  }

  /** 完成构建，返回根节点。 */
  public TreeNode<K, V> build() {
    return root;
  }

  private TreeNode<K, V> current() {
    return stack.peek();
  }
}
