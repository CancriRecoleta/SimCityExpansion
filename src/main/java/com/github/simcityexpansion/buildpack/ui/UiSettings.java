package com.github.simcityexpansion.buildpack.ui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import com.github.simcityexpansion.buildpack.BuildPack;
import com.github.simcityexpansion.buildpack.I18nLog;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.util.Mth;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client-side preferences edited on the {@link SettingsScreen}, persisted to
 * {@code <game dir>/simcity_expansion/ui.json}. Loaded lazily on first access; every setter writes
 * the file immediately. Unknown or malformed values fall back to their defaults, and a file from an
 * older version (which only stored {@code scale}) keeps working.
 */
public final class UiSettings {
  private UiSettings() {}

  private static final Logger LOGGER = LoggerFactory.getLogger(UiSettings.class);

  /** Panel opacity choices offered on the settings screen (percent). */
  public static final int[] OPACITY_STEPS = {60, 75, 85, 100};
  /** Krypton's default panel opacity. */
  public static final int DEFAULT_OPACITY = 85;

  private static boolean loaded;
  private static float scale = 1.0f;
  private static BuildPackTheme.Mode theme = BuildPackTheme.Mode.DARK;
  private static int panelOpacity = DEFAULT_OPACITY;
  private static boolean captureBlockEntities = true;
  private static boolean selectionHud = true;

  // ---- Accessors ----

  /** Manager UI scale (clamped to [{@link UiScale#MIN}, {@link UiScale#MAX}]). */
  public static float scale() {
    ensureLoaded();
    return scale;
  }

  /** Sets and persists the scale (snapped to {@link UiScale#STEP}); returns the applied value. */
  public static float setScale(float value) {
    ensureLoaded();
    scale = snapScale(value);
    save();
    return scale;
  }

  public static BuildPackTheme.Mode theme() {
    ensureLoaded();
    return theme;
  }

  public static void setTheme(BuildPackTheme.Mode value) {
    ensureLoaded();
    theme = value;
    save();
  }

  /** Floating-panel opacity in percent (40..100). */
  public static int panelOpacity() {
    ensureLoaded();
    return panelOpacity;
  }

  public static void setPanelOpacity(int percent) {
    ensureLoaded();
    panelOpacity = Mth.clamp(percent, 40, 100);
    save();
  }

  /** Whether world captures include block-entity contents (chest items, sign text, ...). */
  public static boolean captureBlockEntities() {
    ensureLoaded();
    return captureBlockEntities;
  }

  public static void setCaptureBlockEntities(boolean value) {
    ensureLoaded();
    captureBlockEntities = value;
    save();
  }

  /** Whether the world-selection readout is drawn on the HUD. */
  public static boolean selectionHud() {
    ensureLoaded();
    return selectionHud;
  }

  public static void setSelectionHud(boolean value) {
    ensureLoaded();
    selectionHud = value;
    save();
  }

  // ---- Persistence ----

  private static float snapScale(float value) {
    return Mth.clamp(Math.round(value / UiScale.STEP) * UiScale.STEP, UiScale.MIN, UiScale.MAX);
  }

  private static Path file() {
    return BuildPack.gameDir().resolve("simcity_expansion").resolve("ui.json");
  }

  private static void ensureLoaded() {
    if (loaded) {
      return;
    }
    loaded = true;
    Path file = file();
    if (!Files.isRegularFile(file)) {
      return;
    }
    try {
      JsonObject root =
          JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
      if (root.has("scale")) {
        scale = snapScale(root.get("scale").getAsFloat());
      }
      if (root.has("theme")) {
        theme = "light".equals(root.get("theme").getAsString().toLowerCase(Locale.ROOT))
            ? BuildPackTheme.Mode.LIGHT : BuildPackTheme.Mode.DARK;
      }
      if (root.has("panelOpacity")) {
        panelOpacity = Mth.clamp(root.get("panelOpacity").getAsInt(), 40, 100);
      }
      if (root.has("captureBlockEntities")) {
        captureBlockEntities = root.get("captureBlockEntities").getAsBoolean();
      }
      if (root.has("selectionHud")) {
        selectionHud = root.get("selectionHud").getAsBoolean();
      }
    } catch (IOException | RuntimeException e) {
      I18nLog.warn(LOGGER, e, "buildpack.log.ui_read_failed", file);
    }
  }

  private static void save() {
    JsonObject root = new JsonObject();
    root.addProperty("scale", scale);
    root.addProperty("theme", theme.name().toLowerCase(Locale.ROOT));
    root.addProperty("panelOpacity", panelOpacity);
    root.addProperty("captureBlockEntities", captureBlockEntities);
    root.addProperty("selectionHud", selectionHud);
    String json = new GsonBuilder().setPrettyPrinting().create().toJson(root);
    try {
      BuildPack.writeAtomically(file(), json.getBytes(StandardCharsets.UTF_8));
    } catch (IOException e) {
      I18nLog.warn(LOGGER, e, "buildpack.log.ui_write_failed", file());
    }
  }
}
