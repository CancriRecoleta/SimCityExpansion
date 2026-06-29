package com.github.simcityexpansion.buildpack.ui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.github.simcityexpansion.buildpack.BuildPack;
import com.github.simcityexpansion.buildpack.I18nLog;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manager-UI scale support. The manager renders inside a {@code pose().scale(s, s, 1)} transform so
 * the whole screen can be zoomed; this holds the scale currently being rendered with (so scissor
 * rects, which {@link GuiGraphics#enableScissor} applies in raw screen space ignoring the pose, can
 * be corrected) plus the user's persisted preference.
 */
public final class UiScale {
  private UiScale() {}

  private static final Logger LOGGER = LoggerFactory.getLogger(UiScale.class);

  public static final float MIN = 0.5f;
  public static final float MAX = 2.0f;
  public static final float STEP = 0.1f;

  /** Scale of the frame currently being rendered (1.0 outside the manager). */
  private static float current = 1.0f;
  /** Persisted user preference; {@code < 0} until first loaded from disk. */
  private static float preference = -1.0f;

  public static void set(float scale) {
    current = scale;
  }

  public static float current() {
    return current;
  }

  /** The user's chosen manager scale (loaded from disk on first use). */
  public static float preference() {
    if (preference < 0.0f) {
      preference = load();
    }
    return preference;
  }

  /** Sets and persists the preference (snapped to {@link #STEP} and clamped to [{@link #MIN}, {@link #MAX}]); returns the applied value. */
  public static float setPreference(float scale) {
    preference = Mth.clamp(Math.round(scale / STEP) * STEP, MIN, MAX);
    save(preference);
    return preference;
  }

  /**
   * Scissor that accounts for the active UI pose scale. {@link GuiGraphics#enableScissor} clips in
   * screen space and ignores the pose matrix, so when the manager is zoomed the rect must be scaled
   * by the same factor the content is drawn with.
   */
  public static void enableScissor(GuiGraphics g, int x1, int y1, int x2, int y2) {
    if (current == 1.0f) {
      g.enableScissor(x1, y1, x2, y2);
    } else {
      g.enableScissor(Math.round(x1 * current), Math.round(y1 * current),
          Math.round(x2 * current), Math.round(y2 * current));
    }
  }

  private static Path file() {
    return BuildPack.gameDir().resolve("simcity_expansion").resolve("ui.json");
  }

  private static float load() {
    Path file = file();
    if (!Files.isRegularFile(file)) {
      return 1.0f;
    }
    try {
      JsonObject root =
          JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
      return root.has("scale") ? Mth.clamp(root.get("scale").getAsFloat(), MIN, MAX) : 1.0f;
    } catch (IOException | RuntimeException e) {
      I18nLog.warn(LOGGER, e, "buildpack.log.ui_read_failed", file);
      return 1.0f;
    }
  }

  private static void save(float scale) {
    JsonObject root = new JsonObject();
    root.addProperty("scale", scale);
    try {
      BuildPack.writeAtomically(file(), root.toString().getBytes(StandardCharsets.UTF_8));
    } catch (IOException e) {
      I18nLog.warn(LOGGER, e, "buildpack.log.ui_write_failed", file());
    }
  }
}
