package com.github.simcityexpansion.buildpack.ui;

import com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib2.gui.texture.Icons;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Toggle;

/**
 * 把 {@link Toggle} 统一渲染成「带勾的勾选框」：未选中显示空框（{@link Icons#CHECKBOX_BLANK}），
 * 选中显示带勾框（{@link Icons#CHECKBOX_MARKED}），并去掉默认的方块按钮底，
 * 只保留复选框图标本身。
 *
 * <p>来源页签（导入文件 / 拓展包 / 已安装，互斥单选）与表单里的「覆盖同名建筑」开关都用它，
 * 外观一致。贴图直接在代码侧设置，避免依赖样式表里对内置贴图的引用。
 */
public final class UiCheckbox {
  private UiCheckbox() {}

  /** 复选框（含文字）的统一行高，与表单输入行协调。 */
  public static final float HEIGHT = 14.0f;

  /** 应用复选框样式，返回原 {@code toggle} 以便链式调用。 */
  public static Toggle style(Toggle toggle) {
    return toggle.toggleStyle(style -> style
        .markTexture(Icons.CHECKBOX_MARKED)
        .unmarkTexture(Icons.CHECKBOX_BLANK)
        .baseTexture(IGuiTexture.EMPTY)
        .hoverTexture(IGuiTexture.EMPTY));
  }
}
