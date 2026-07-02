package com.github.simcityexpansion.buildpack;

import java.util.Arrays;

import net.minecraft.network.chat.Component;
import org.slf4j.Logger;

/**
 * Log i18n: resolves messages via translation keys before writing to the log — on the client in
 * the selected language, and on a dedicated server in the built-in English (NeoForge loads each
 * mod's en_us on the server). All messages are prefixed with {@code "BuildPack: "} for easy
 * log filtering.
 */
public final class I18nLog {
  private I18nLog() {}

  /** Logs at INFO level. */
  public static void info(Logger logger, String key, Object... args) {
    logger.info(resolve(key, args));
  }

  /** Logs at WARN level (no exception). */
  public static void warn(Logger logger, String key, Object... args) {
    logger.warn(resolve(key, args));
  }

  /** Logs at WARN level with an exception stack trace. */
  public static void warn(Logger logger, Throwable error, String key, Object... args) {
    logger.warn(resolve(key, args), error);
  }

  private static String resolve(String key, Object[] args) {
    // Stringify non-Component arguments to avoid compatibility issues with translation formatting for arbitrary objects.
    Object[] safe = Arrays.stream(args)
        .map(arg -> arg instanceof Component ? arg : String.valueOf(arg))
        .toArray();
    return "BuildPack: " + Component.translatable(key, safe).getString();
  }
}
