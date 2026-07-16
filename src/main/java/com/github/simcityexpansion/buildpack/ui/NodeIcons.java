package com.github.simcityexpansion.buildpack.ui;

import com.github.simcityexpansion.buildpack.model.BuildingCategory;
import com.github.simcityexpansion.buildpack.model.ImportFile;
import com.github.simcityexpansion.buildpack.model.InstalledBuilding;
import com.github.simcityexpansion.buildpack.model.PackArchive;
import com.github.simcityexpansion.buildpack.model.StructureFormat;
import com.github.simcityexpansion.buildpack.ui.definition.VisualDefinitionEditor;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Tree node icons: procedurally drawn pixel icons differentiated by content type
 * (folder / structure file / building / build pack). Each has a distinct shape, dark outline,
 * and highlight, replacing the previous flat solid-color squares. Fully procedural; no external
 * textures required.
 */
public final class NodeIcons {
  private NodeIcons() {}

  private static final int FOLDER = 0xFFE6CE84;
  private static final int NBT = 0xFFFFB74D;
  private static final int LITEMATIC = 0xFF4FC3F7;
  private static final int PACK = 0xFFBA68C8;

  /** Draws a {@code size}-square node icon at (x, y). */
  public static void draw(GuiGraphics g, int x, int y, int size, Object content,
      boolean branch, boolean expanded) {
    if (content instanceof ImportFile file) {
      file(g, x, y, size, formatColor(file.format()));
    } else if (content instanceof PackBuildingSelection selection) {
      file(g, x, y, size, formatColor(selection.entry().format()));
    } else if (content instanceof PackArchive) {
      pack(g, x, y, size, PACK);
    } else if (content instanceof InstalledBuilding building) {
      building(g, x, y, size, categoryColor(building.category()));
    } else if (content instanceof VisualDefinitionEditor.DefinitionNode node) {
      chip(g, x, y, size, node.iconColor());
    } else {
      folder(g, x, y, size, FOLDER, expanded);
    }
  }

  /** Definition-editor node: a small rounded chip colored by node kind. */
  private static void chip(GuiGraphics g, int x, int y, int s, int c) {
    int pad = Math.max(1, s / 5);
    int d = shade(c, 0.55f);
    fill(g, x + pad, y + pad, s - pad * 2, s - pad * 2, d);
    fill(g, x + pad + 1, y + pad + 1, s - pad * 2 - 2, s - pad * 2 - 2, c);
    fill(g, x + pad + 1, y + pad + 1, s - pad * 2 - 2, 1, shade(c, 1.25f));
  }

  // ---- Icon types ----

  /** Folder: tab + body; when expanded, a light-colored front flap is revealed. */
  private static void folder(GuiGraphics g, int x, int y, int s, int c, boolean open) {
    int d = shade(c, 0.55f);
    int tabW = Math.round(s * 0.55f);
    int tabH = Math.max(1, Math.round(s * 0.16f));
    int by = y + Math.round(s * 0.30f);
    int bh = Math.max(3, y + s - by);
    fill(g, x, by - tabH, tabW, tabH, d);
    fill(g, x + 1, by - tabH + 1, tabW - 2, tabH, c);
    fill(g, x, by, s, bh, d);
    fill(g, x + 1, by + 1, s - 2, bh - 2, c);
    if (open) {
      fill(g, x + 1, by + bh - Math.max(2, Math.round(s * 0.30f)), s - 2, 1, shade(c, 1.25f));
    } else {
      fill(g, x + 1, by + 1, s - 2, 1, shade(c, 1.2f));
    }
  }

  /** Structure file: vertical page with a top-right dog-ear fold and a few text lines. */
  private static void file(GuiGraphics g, int x, int y, int s, int c) {
    int d = shade(c, 0.55f);
    int pw = Math.round(s * 0.74f);
    int px = x + (s - pw) / 2;
    int fold = Math.max(2, Math.round(s * 0.30f));
    fill(g, px, y, pw, s, d);
    fill(g, px + 1, y + 1, pw - 2, s - 2, c);
    // Top-right dog-ear fold (dark stepped triangle).
    for (int i = 0; i < fold; i++) {
      fill(g, px + pw - fold + i, y, fold - i, 1, d);
    }
    fill(g, px + pw - fold, y, 1, fold, d);
    // Text lines.
    int lx = px + 2;
    int lw = pw - 4;
    fill(g, lx, y + Math.round(s * 0.46f), lw, 1, d);
    fill(g, lx, y + Math.round(s * 0.62f), lw, 1, d);
    fill(g, lx, y + Math.round(s * 0.78f), Math.max(1, lw - 2), 1, d);
  }

  /** Build pack: crate body with a lid seam and a central strap. */
  private static void pack(GuiGraphics g, int x, int y, int s, int c) {
    int d = shade(c, 0.55f);
    int by = y + Math.round(s * 0.08f);
    int bh = Math.round(s * 0.84f);
    fill(g, x, by, s, bh, d);
    fill(g, x + 1, by + 1, s - 2, bh - 2, c);
    fill(g, x, by + Math.round(s * 0.32f), s, 1, d);
    fill(g, x + s / 2, by, 1, bh, d);
    fill(g, x + 1, by + 1, s - 2, 1, shade(c, 1.2f));
  }

  /** Building: main body with a roof cap and window panes. */
  private static void building(GuiGraphics g, int x, int y, int s, int c) {
    int d = shade(c, 0.5f);
    int bw = Math.round(s * 0.78f);
    int bx = x + (s - bw) / 2;
    int by = y + Math.round(s * 0.14f);
    int bh = y + s - by;
    fill(g, bx, by, bw, bh, d);
    fill(g, bx + 1, by + 1, bw - 2, bh - 2, c);
    fill(g, bx, by, bw, Math.max(1, Math.round(s * 0.14f)), d);
    // Window panes: 2 columns x 2 rows of small dark windows.
    int win = Math.max(1, Math.round(s * 0.16f));
    int gap = Math.max(1, Math.round(s * 0.14f));
    int startX = bx + gap;
    int startY = by + Math.round(s * 0.26f);
    for (int row = 0; row < 2; row++) {
      for (int col = 0; col < 2; col++) {
        fill(g, startX + col * (win + gap), startY + row * (win + gap), win, win, d);
      }
    }
  }

  // ---- Utilities ----

  private static void fill(GuiGraphics g, int x, int y, int w, int h, int c) {
    if (w > 0 && h > 0) {
      g.fill(x, y, x + w, y + h, c);
    }
  }

  private static int formatColor(StructureFormat format) {
    return format == StructureFormat.LITEMATIC ? LITEMATIC : NBT;
  }

  private static int categoryColor(BuildingCategory category) {
    return switch (category) {
      case RESIDENTIAL -> 0xFFAED581;
      case COMMERCIAL -> 0xFF4DB6AC;
      case INDUSTRY -> 0xFFF06292;
      case PUBLIC -> 0xFF7986CB;
      case OTHER -> 0xFF90A4AE;
    };
  }

  private static int shade(int argb, float factor) {
    int r = clamp((int) (((argb >> 16) & 0xFF) * factor));
    int g = clamp((int) (((argb >> 8) & 0xFF) * factor));
    int b = clamp((int) ((argb & 0xFF) * factor));
    return (argb & 0xFF000000) | (r << 16) | (g << 8) | b;
  }

  private static int clamp(int v) {
    return v < 0 ? 0 : Math.min(v, 255);
  }
}
