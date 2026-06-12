package com.github.simcityexpansion.buildpack;

import java.io.IOException;

import net.minecraft.network.chat.Component;

/**
 * 携带可本地化消息的 IO 异常：解析/安装失败的原因要显示在状态栏，
 * 必须走翻译键而非硬编码文本。
 */
public class LocalizedIOException extends IOException {

  private final Component component;

  public LocalizedIOException(Component component) {
    super(component.getString());
    this.component = component;
  }

  /** 本地化的失败原因。 */
  public Component component() {
    return component;
  }

  /** 提取任意异常的展示消息：可本地化异常用其翻译组件，其余回退为原始消息文本。 */
  public static Component messageOf(Throwable e) {
    if (e instanceof LocalizedIOException localized) {
      return localized.component();
    }
    return Component.literal(String.valueOf(e.getMessage()));
  }
}
