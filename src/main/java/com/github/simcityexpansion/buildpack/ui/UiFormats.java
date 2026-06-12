package com.github.simcityexpansion.buildpack.ui;

import java.text.NumberFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Locale;

import net.minecraft.client.Minecraft;

/**
 * 信息面板的本地化格式化：数字千分位、文件大小、日期时间均跟随<b>游戏语言</b>
 * （而非系统区域），与界面文案语言保持一致。
 */
public final class UiFormats {
  private UiFormats() {}

  /** 整数千分位（如 12,345 / 12 345，按语言习惯）。 */
  public static String integer(long value) {
    return NumberFormat.getIntegerInstance(gameLocale()).format(value);
  }

  /** 文件大小自适应单位：B / KB / MB。 */
  public static String fileSize(long bytes) {
    if (bytes < 1024L) {
      return bytes + " B";
    }
    if (bytes < 1024L * 1024L) {
      return String.format(gameLocale(), "%.1f KB", bytes / 1024.0);
    }
    return String.format(gameLocale(), "%.1f MB", bytes / (1024.0 * 1024.0));
  }

  /** 本地化短日期时间；epoch 毫秒为 0 时返回空串（表示未知）。 */
  public static String dateTime(long epochMillis) {
    if (epochMillis <= 0L) {
      return "";
    }
    return DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
        .withLocale(gameLocale())
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(epochMillis));
  }

  /** 把游戏语言代码（如 {@code zh_cn}）转换为 {@link Locale}。 */
  public static Locale gameLocale() {
    String code = Minecraft.getInstance().getLanguageManager().getSelected();
    String[] parts = code.split("_");
    return parts.length > 1
        ? Locale.of(parts[0], parts[1].toUpperCase(Locale.ROOT))
        : Locale.of(code);
  }
}
