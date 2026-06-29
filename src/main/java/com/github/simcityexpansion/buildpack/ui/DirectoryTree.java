package com.github.simcityexpansion.buildpack.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.github.simcityexpansion.buildpack.integration.ActivePackProvider;
import com.github.simcityexpansion.buildpack.model.BuildingCategory;
import com.github.simcityexpansion.buildpack.model.ImportFile;
import com.github.simcityexpansion.buildpack.model.ImportIndex;
import com.github.simcityexpansion.buildpack.model.InstalledBuilding;
import com.github.simcityexpansion.buildpack.model.PackArchive;
import com.github.simcityexpansion.buildpack.model.PackBuildingEntry;
import com.github.simcityexpansion.buildpack.ui.tree.TreeBuilder;
import com.github.simcityexpansion.buildpack.ui.tree.TreeNode;
import net.minecraft.network.chat.Component;

/**
 * Organizes data from three sources into a {@link TreeNode} hierarchy: branch node content is
 * {@code null}, and leaf content is the corresponding data object ({@link ImportFile} /
 * {@link PackArchive} / {@link PackBuildingSelection} / {@link InstalledBuilding}).
 */
public final class DirectoryTree {
  private DirectoryTree() {}

  /** Key for the synthetic tree root (hidden by TreeList in flattenRoot mode). */
  public static final String ROOT_KEY = "buildpack";

  /** Imported loose files: builds a hierarchy from subdirectories relative to the import root; favorites are prefixed with ★ and duplicates are suffixed with a marker. */
  public static TreeNode<String, Object> buildImport(
      Path importRoot, List<ImportFile> files, Set<Path> duplicates) {
    TreeBuilder<String, Object> builder = TreeBuilder.start(ROOT_KEY);
    for (ImportFile file : files) {
      String label = importLabel(file, duplicates);
      Path relative = importRoot.relativize(file.path());
      List<String> folders = new ArrayList<>();
      for (int i = 0; i < relative.getNameCount() - 1; i++) {
        folders.add(relative.getName(i).toString());
      }
      if (folders.isEmpty()) {
        builder.leaf(label, file);
      } else {
        builder.diveBranch(folders, branch -> branch.leaf(label, file));
      }
    }
    return builder.build();
  }

  /** Display label: favorites prefixed with ★, duplicates suffixed with a marker. */
  private static String importLabel(ImportFile file, Set<Path> duplicates) {
    String label = file.fileName();
    if (ImportIndex.favorite(file.path())) {
      label = "★ " + label;
    }
    if (duplicates.contains(file.path())) {
      label = label + " " + Component.translatable("buildpack.tree.duplicate_mark").getString();
    }
    return label;
  }

  /**
   * Zip-based build pack: the pack itself is a branch node (content is {@link PackArchive};
   * selecting it installs the entire pack). Expanding it shows the buildings inside, organized
   * as category -> building, with leaves as {@link PackBuildingSelection}.
   */
  public static TreeNode<String, Object> buildPacks(List<PackArchive> packs) {
    TreeBuilder<String, Object> builder = TreeBuilder.start(ROOT_KEY);
    for (PackArchive pack : packs) {
      String label = pack.manifest().name() + " (" + pack.fileName() + ")";
      if (ActivePackProvider.isActive(pack.manifest().id())) {
        label = Component.translatable("buildpack.tree.active_mark").getString() + " " + label;
      }
      builder.startBranch(label);
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

  /** Installed buildings: organized as category -> building. */
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
