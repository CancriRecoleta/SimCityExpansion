package com.github.simcityexpansion.buildpack.model;

import java.nio.file.Path;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

/**
 * SimuKraft 建筑目录中已存在的一个建筑（来自 .sk 扫描）。
 *
 * @param category 所属分类
 * @param name .sk 的 name 字段（缺失时回退为文件基础名）
 * @param skPath .sk 元数据文件路径
 * @param structurePath 同基础名的结构文件路径（可能为 null，表示元数据缺结构）
 * @param skFields .sk 解析出的全部键值对
 * @param managed 是否由本模组生成/安装（.sk 带生成标记，或被拓展包注册表记录）
 * @param packId 若由 zip 拓展包安装，则为包 id；否则为 null
 */
public record InstalledBuilding(
    BuildingCategory category,
    String name,
    Path skPath,
    @Nullable Path structurePath,
    Map<String, String> skFields,
    boolean managed,
    @Nullable String packId) {

  /** .sk 文件基础名。 */
  public String baseName() {
    String fileName = skPath.getFileName().toString();
    int dot = fileName.lastIndexOf('.');
    return dot > 0 ? fileName.substring(0, dot) : fileName;
  }
}
