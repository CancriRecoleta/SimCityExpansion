package com.github.simcityexpansion.buildpack.model;

/**
 * 一个建筑的 .sk 元数据表单模型（可变，供界面双向绑定）。
 *
 * <p>字段与 SimuKraft 的 .sk 键一一对应：name / size / amount / author /
 * description / tags / job_type。尺寸由结构文件自动计算，界面上只读。
 */
public final class BuildingMetadata {
  public String name = "";
  public String amount = "";
  public String author = "";
  public String description = "";
  /** 逗号分隔的标签串，例如 {@code price_low,material_low,stage_early}。 */
  public String tags = "";
  public String jobType = "";
  public BuildingCategory category = BuildingCategory.OTHER;

  public int sizeX;
  public int sizeY;
  public int sizeZ;

  /** .sk 的 size 字段格式："X x Y x Z"。 */
  public String sizeString() {
    return sizeX + " x " + sizeY + " x " + sizeZ;
  }

  /** 用解析出的结构摘要预填表单（名称/作者仅在为空时填充，避免覆盖用户输入）。 */
  public void prefill(StructureInfo info, String fallbackName) {
    if (name.isBlank()) {
      name = info.name() == null || info.name().isBlank() ? fallbackName : info.name();
    }
    if (author.isBlank() && info.author() != null) {
      author = info.author();
    }
    sizeX = info.sizeX();
    sizeY = info.sizeY();
    sizeZ = info.sizeZ();
  }
}
