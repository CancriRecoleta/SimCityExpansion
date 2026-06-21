package com.github.simcityexpansion.buildpack.ui;

import net.minecraft.client.gui.GuiGraphics;

/**
 * BuildPack 界面的配色与面板绘制（取代原先的 {@code buildpack.lss} 样式表）。
 *
 * <p>所有数值逐项对照 Litematica / malilib 源码：整屏遮罩 {@code #b0000000}、
 * 列表/详情面板 {@code drawOutlinedBox}（底 {@code #a0000000} + 1px 边 {@code #999999}）、
 * 预览框底色更透（{@code #30000000}），文字标签灰 {@code #c0c0c0}、值白。
 */
public final class BuildPackTheme {
  private BuildPackTheme() {}

  /** 整屏半透明遮罩底色（GuiBase.TOOLTIP_BACKGROUND）。 */
  public static final int ROOT_BG = 0xB0000000;
  /** 列表/详情面板底色。 */
  public static final int PANEL_BG = 0xA0000000;
  /** 预览框底色（更透，凸显方块模型）。 */
  public static final int PREVIEW_BG = 0x30000000;
  /** 面板/预览框 1px 描边色。 */
  public static final int BORDER = 0xFF999999;

  /** 标题文字色。 */
  public static final int TITLE = 0xFFFFFFFF;
  /** 信息行/表单标签灰。 */
  public static final int LABEL = 0xFFC0C0C0;
  /** 信息值白。 */
  public static final int VALUE = 0xFFFFFFFF;
  /** 计数文本灰。 */
  public static final int COUNT = 0xFFA0A0A0;
  /** 提示文本灰。 */
  public static final int HINT = 0xFF808080;
  /** 正常状态消息（绿）。 */
  public static final int MESSAGE_OK = 0xFF55FF55;
  /** 错误状态消息（红）。 */
  public static final int MESSAGE_ERROR = 0xFFFF6060;
  /** 空态/占位文本灰。 */
  public static final int NONE = 0xFF808080;

  /** 按钮常态底色。 */
  public static final int BUTTON_BG = 0xC0303030;
  /** 按钮悬停底色。 */
  public static final int BUTTON_BG_HOVER = 0xD0505050;
  /** 按钮禁用底色。 */
  public static final int BUTTON_BG_DISABLED = 0x90202020;
  /** 按钮常态边框色。 */
  public static final int BUTTON_BORDER = 0xFF999999;
  /** 按钮悬停边框色。 */
  public static final int BUTTON_BORDER_HOVER = 0xFFFFFFFF;
  /** 按钮常态文字色。 */
  public static final int BUTTON_TEXT = 0xFFE0E0E0;
  /** 按钮禁用文字色。 */
  public static final int BUTTON_TEXT_DISABLED = 0xFF808080;

  /** 画一个带 1px 描边的面板（Litematica 的 drawOutlinedBox）。 */
  public static void panel(GuiGraphics g, int x, int y, int w, int h) {
    fillPanel(g, x, y, w, h, PANEL_BG);
  }

  /** 画预览框（更透底 + 描边）。 */
  public static void previewPanel(GuiGraphics g, int x, int y, int w, int h) {
    fillPanel(g, x, y, w, h, PREVIEW_BG);
  }

  /** 自定义底色的描边面板。 */
  public static void fillPanel(GuiGraphics g, int x, int y, int w, int h, int fill) {
    if (w <= 0 || h <= 0) {
      return;
    }
    g.fill(x, y, x + w, y + h, fill);
    border(g, x, y, w, h, BORDER);
  }

  /** 仅画 1px 边框。 */
  public static void border(GuiGraphics g, int x, int y, int w, int h, int color) {
    if (w <= 0 || h <= 0) {
      return;
    }
    g.fill(x, y, x + w, y + 1, color);
    g.fill(x, y + h - 1, x + w, y + h, color);
    g.fill(x, y + 1, x + 1, y + h - 1, color);
    g.fill(x + w - 1, y + 1, x + w, y + h - 1, color);
  }
}
