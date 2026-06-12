package com.github.simcityexpansion.buildpack.model;

/**
 * zip 拓展包的 {@code pack.json} 清单。
 *
 * @param format 包格式版本（当前为 1）
 * @param id 全局唯一包 id（如 {@code author.pack_name}），用于安装注册表
 * @param name 展示名
 * @param version 包版本号
 * @param author 作者
 * @param description 描述
 */
public record PackManifest(
    int format, String id, String name, String version, String author, String description) {

  /**
   * 当前包格式版本。v2 起：{@code <名>.meta.json} 为本模组元数据，
   * {@code <名>.json} 原样安装为 SimuKraft 的职业/交易定义。
   */
  public static final int CURRENT_FORMAT = 2;

  /** 仍可读取的最旧格式版本。 */
  public static final int MIN_FORMAT = 1;
}
