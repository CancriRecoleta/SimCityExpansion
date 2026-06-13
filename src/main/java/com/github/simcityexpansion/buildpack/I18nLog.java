package com.github.simcityexpansion.buildpack;

import java.util.Arrays;

import net.minecraft.network.chat.Component;
import org.slf4j.Logger;

/**
 * 日志 i18n：消息经翻译键解析后写入日志——客户端按所选语言，
 * 专用服务器按内置英文（NeoForge 在服务端加载各模组的 en_us）。
 * 统一加 {@code "BuildPack: "} 前缀便于在日志中过滤。
 */
public final class I18nLog {
  private I18nLog() {}

  /** warn 级（无异常）。 */
  public static void warn(Logger logger, String key, Object... args) {
    logger.warn(resolve(key, args));
  }

  /** warn 级（附异常堆栈）。 */
  public static void warn(Logger logger, Throwable error, String key, Object... args) {
    logger.warn(resolve(key, args), error);
  }

  private static String resolve(String key, Object[] args) {
    // 非组件参数统一字符串化，避免翻译格式化对任意对象的兼容性问题。
    Object[] safe = Arrays.stream(args)
        .map(arg -> arg instanceof Component ? arg : String.valueOf(arg))
        .toArray();
    return "BuildPack: " + Component.translatable(key, safe).getString();
  }
}
