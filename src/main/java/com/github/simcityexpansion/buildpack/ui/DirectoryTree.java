package com.github.simcityexpansion.buildpack.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.github.simcityexpansion.buildpack.model.BuildingCategory;
import com.github.simcityexpansion.buildpack.model.ImportFile;
import com.github.simcityexpansion.buildpack.model.InstalledBuilding;
import com.github.simcityexpansion.buildpack.model.PackArchive;
import com.github.simcityexpansion.buildpack.model.PackBuildingEntry;
import com.github.simcityexpansion.buildpack.ui.tree.TreeBuilder;
import com.github.simcityexpansion.buildpack.ui.tree.TreeNode;

/**
 * 把三种来源的数据组织成 {@link TreeNode} 层级树：分支节点内容为 {@code null}，
 * 叶子内容为对应的数据对象（{@link ImportFile} / {@link PackArchive} /
 * {@link PackBuildingSelection} / {@link InstalledBuilding}）。
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

  /**
   * zip 拓展包：包为分支节点（content 为 {@link PackArchive}，选中可整包安装），
   * 展开后直接浏览包内建筑（分类 → 建筑，叶子为 {@link PackBuildingSelection}）。
   */
  public static TreeNode<String, Object> buildPacks(List<PackArchive> packs) {
    TreeBuilder<String, Object> builder = TreeBuilder.start(ROOT_KEY);
    for (PackArchive pack : packs) {
      builder.startBranch(pack.manifest().name() + " (" + pack.fileName() + ")");
      builder.content(pack);
      for (BuildingCategory category : BuildingCategory.values()) {
        List<PackBuildingEntry> inCategory = pack.buildings().stream()
            .filter(entry -> entry.category() == category)
            .toList();
        if (inCategory.isEmpty()) {
          continue;
        }
        builder.startBranch(category.displayName().getString());
        for (PackBuildingEntry entry : inCategory) {
          builder.leaf(entry.name(), new PackBuildingSelection(pack, entry));
        }
        builder.endBranch();
      }
      builder.endBranch();
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
