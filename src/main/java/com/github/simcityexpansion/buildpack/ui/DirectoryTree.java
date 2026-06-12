package com.github.simcityexpansion.buildpack.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.github.simcityexpansion.buildpack.model.BuildingCategory;
import com.github.simcityexpansion.buildpack.model.ImportFile;
import com.github.simcityexpansion.buildpack.model.InstalledBuilding;
import com.github.simcityexpansion.buildpack.model.PackArchive;
import com.lowdragmc.lowdraglib2.gui.util.TreeBuilder;
import com.lowdragmc.lowdraglib2.gui.util.TreeNode;

/**
 * 把三种来源的数据组织成 {@link com.lowdragmc.lowdraglib2.gui.ui.elements.TreeList}
 * 可用的层级树：分支节点内容为 {@code null}，叶子内容为对应的数据对象。
 */
public final class DirectoryTree {
  private DirectoryTree() {}

  /** 合成树根的键（TreeList 以 flattenRoot 模式隐藏该根）。 */
  public static final String ROOT_KEY = "buildpack";

  /** 导入散文件：按相对导入目录的子目录建层级。 */
  public static TreeNode<String, Object> buildImport(Path importRoot, List<ImportFile> files) {
    TreeBuilder<String, Object> builder = TreeBuilder.start(ROOT_KEY);
    for (ImportFile file : files) {
      Path relative = importRoot.relativize(file.path());
      List<String> folders = new ArrayList<>();
      for (int i = 0; i < relative.getNameCount() - 1; i++) {
        folders.add(relative.getName(i).toString());
      }
      if (folders.isEmpty()) {
        builder.leaf(file.fileName(), file);
      } else {
        builder.diveBranch(folders, branch -> branch.leaf(file.fileName(), file));
      }
    }
    return builder.build();
  }

  /** zip 拓展包：平铺为根下叶子。 */
  public static TreeNode<String, Object> buildPacks(List<PackArchive> packs) {
    TreeBuilder<String, Object> builder = TreeBuilder.start(ROOT_KEY);
    for (PackArchive pack : packs) {
      builder.leaf(pack.manifest().name() + " (" + pack.fileName() + ")", pack);
    }
    return builder.build();
  }

  /** 已安装建筑：按「分类 → 建筑」建层级。 */
  public static TreeNode<String, Object> buildInstalled(List<InstalledBuilding> buildings) {
    TreeBuilder<String, Object> builder = TreeBuilder.start(ROOT_KEY);
    for (BuildingCategory category : BuildingCategory.values()) {
      List<InstalledBuilding> inCategory = buildings.stream()
          .filter(building -> building.category() == category)
          .toList();
      if (inCategory.isEmpty()) {
        continue;
      }
      builder.branch(category.displayName().getString(), branch -> {
        for (InstalledBuilding building : inCategory) {
          branch.leaf(building.name(), building);
        }
      });
    }
    return builder.build();
  }
}
