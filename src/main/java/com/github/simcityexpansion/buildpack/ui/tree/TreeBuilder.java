package com.github.simcityexpansion.buildpack.ui.tree;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer;

/**
 * Fluent builder for {@link TreeNode} (replaces the original ldlib2 TreeBuilder):
 * maintains a "current branch" stack and supports both a stateful style
 * ({@link #startBranch}/{@link #content}/{@link #endBranch}) and a functional
 * style ({@link #branch}/{@link #diveBranch}/{@link #leaf}).
 *
 * @param <K> node key type
 * @param <V> node content type
 */
public final class TreeBuilder<K, V> {
  private final TreeNode<K, V> root;
  private final Deque<TreeNode<K, V>> stack = new ArrayDeque<>();

  private TreeBuilder(K rootKey) {
    root = new TreeNode<>(rootKey, null);
    stack.push(root);
  }

  /** Starts building with the given root key. */
  public static <K, V> TreeBuilder<K, V> start(K rootKey) {
    return new TreeBuilder<>(rootKey);
  }

  /** Appends a leaf node under the current branch. */
  public TreeBuilder<K, V> leaf(K key, V value) {
    current().addChild(new TreeNode<>(key, value));
    return this;
  }

  /** Opens a new branch and enters it (content defaults to {@code null}). */
  public TreeBuilder<K, V> startBranch(K key) {
    TreeNode<K, V> branch = new TreeNode<>(key, null);
    current().addChild(branch);
    stack.push(branch);
    return this;
  }

  /** Sets content on the current branch (e.g., attaches a pack object to a pack branch node). */
  public TreeBuilder<K, V> content(V value) {
    current().setContent(value);
    return this;
  }

  /** Ends the current branch and returns to the parent level. */
  public TreeBuilder<K, V> endBranch() {
    stack.pop();
    return this;
  }

  /** Creates a branch and executes {@code populate} inside it. */
  public TreeBuilder<K, V> branch(K key, Consumer<TreeBuilder<K, V>> populate) {
    startBranch(key);
    populate.accept(this);
    return endBranch();
  }

  /** Traverses the {@code keys} path level by level, finding or creating each branch, then executes {@code populate} at the deepest level (reusing an existing branch if a matching key is found). */
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

  /** Finalizes the build and returns the root node. */
  public TreeNode<K, V> build() {
    return root;
  }

  private TreeNode<K, V> current() {
    return stack.peek();
  }
}
