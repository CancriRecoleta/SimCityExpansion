package com.github.simcityexpansion.buildpack.ui;

import java.text.NumberFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Locale;

import net.minecraft.client.Minecraft;

/**
 * Localized formatting for the info panel: integer thousands-separators, file sizes, and date/times all follow the
 * <b>game language</b> (not the system locale), keeping them consistent with the UI text language.
 */
public final class UiFormats {
  private UiFormats() {}

  /** Formats an integer with thousands separators per the game locale (e.g., 12,345 or 12 345). */
  public static String integer(long value) {
    return NumberFormat.getIntegerInstance(gameLocale()).format(value);
  }

  /** Formats a file size with an adaptive unit: B, KB, or MB. */
  public static String fileSize(long bytes) {
    if (bytes < 1024L) {
      return bytes + " B";
    }
    if (bytes < 1024L * 1024L) {
      return String.format(gameLocale(), "%.1f KB", bytes / 1024.0);
    }
    return String.format(gameLocale(), "%.1f MB", bytes / (1024.0 * 1024.0));
  }

  /** Returns a localized short date-time string; returns an empty string when epoch millis is 0 (meaning unknown). */
  public static String dateTime(long epochMillis) {
    if (epochMillis <= 0L) {
      return "";
    }
    return DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
        .withLocale(gameLocale())
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(epochMillis));
  }

  /** Converts a game language code (e.g., {@code zh_cn}) to a {@link Locale}. */
  public static Locale gameLocale() {
    String code = Minecraft.getInstance().getLanguageManager().getSelected();
    String[] parts = code.split("_");
    return parts.length > 1
        ? Locale.of(parts[0], parts[1].toUpperCase(Locale.ROOT))
        : Locale.of(code);
  }
}
