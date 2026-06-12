package com.github.simcityexpansion.buildpack.model;

import org.jetbrains.annotations.Nullable;

/**
 * zip 拓展包内的一个建筑条目（{@code buildings/<分类>/<名>.*}）。
 *
 * @param category 分类（取自 zip 内路径）
 * @param name 基础名（不含扩展名）
 * @param structureEntry 结构文件在 zip 内的条目路径
 * @param format 结构格式
 * @param skEntry 同基础名 .sk 条目路径（可选；存在时原样安装，优先于 metaJsonEntry）
 * @param metaJsonEntry 同基础名 .json 元数据条目路径（可选）
 */
public record PackBuildingEntry(
    BuildingCategory category,
    String name,
    String structureEntry,
    StructureFormat format,
    @Nullable String skEntry,
    @Nullable String metaJsonEntry) {}
