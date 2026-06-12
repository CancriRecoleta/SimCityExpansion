package com.github.simcityexpansion.buildpack.model;

import org.jetbrains.annotations.Nullable;

/**
 * 结构文件的只读解析摘要，供详情面板展示与表单预填。
 *
 * @param name 结构内嵌名称（litematic 的 Metadata.Name；.nbt 无内嵌名时为 null）
 * @param author 作者（litematic 的 Metadata.Author；可为 null）
 * @param sizeX 包围尺寸 X
 * @param sizeY 包围尺寸 Y
 * @param sizeZ 包围尺寸 Z
 * @param totalBlocks 非空气方块总数
 * @param totalVolume 包围体积
 * @param regionCount 区域数（原版 .nbt 恒为 1）
 * @param dataVersion 结构保存时的 Minecraft DataVersion
 * @param timeCreated 结构创建时间（epoch 毫秒；litematic 元数据自带，其余格式为 0 表示未知）
 * @param previewArgb litematic 内嵌预览图（方形 ARGB 像素，无则为 null）
 * @param previewSize 预览图边长（无预览时为 0）
 */
public record StructureInfo(
    @Nullable String name,
    @Nullable String author,
    int sizeX,
    int sizeY,
    int sizeZ,
    long totalBlocks,
    long totalVolume,
    int regionCount,
    int dataVersion,
    long timeCreated,
    @Nullable int[] previewArgb,
    int previewSize) {

  /** 尺寸展示串："X x Y x Z"。 */
  public String sizeString() {
    return sizeX + " x " + sizeY + " x " + sizeZ;
  }
}
