package com.github.simcityexpansion.buildpack.ui;

import com.github.simcityexpansion.buildpack.model.PackArchive;
import com.github.simcityexpansion.buildpack.model.PackBuildingEntry;

/**
 * 「拓展包」页签中选中的<b>包内建筑</b>（直接从 zip 读取，未解压）。
 *
 * @param pack 所属拓展包
 * @param entry 包内建筑条目
 */
public record PackBuildingSelection(PackArchive pack, PackBuildingEntry entry) {}
