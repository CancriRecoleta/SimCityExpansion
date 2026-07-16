package com.github.simcityexpansion.buildpack.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.github.simcityexpansion.buildpack.model.PoiLine;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * Modal editor for a building's {@code poi:} metadata lines ({@code poi: TYPE, capacity[, id]}).
 * Each row: type (click cycles through SimuKraft's known POI types), integer capacity, and the
 * optional id upstream matches block registry names against. RESIDENTIAL is special upstream —
 * homes come from red beds, not from the line — which the hint text explains.
 *
 * <p>Read-only when the underlying form is (installed/pack views); "Done" writes the rows back
 * into the bound {@link com.github.simcityexpansion.buildpack.model.BuildingMetadata}'s list.
 */
public final class PoiEditScreen extends Screen {

  private static final int W = 320;
  private static final int ROW_H = 18;
  private static final int ROW_GAP = 3;
  private static final int TYPE_W = 110;
  private static final int CAPACITY_W = 46;
  private static final int REMOVE_W = 16;
  private static final int MAX_ROWS = 9;

  /** One editable row (kept as raw strings so intermediate input is preserved). */
  private static final class Row {
    String type;
    String capacity;
    String id;

    Row(PoiLine line) {
      type = line.type();
      capacity = line.capacity();
      id = line.id();
    }
  }

  private final Screen parent;
  private final List<PoiLine> target;
  private final boolean editable;
  private final Runnable onChanged;
  private final List<Row> rows = new ArrayList<>();

  private PoiEditScreen(Screen parent, List<PoiLine> target, boolean editable, Runnable onChanged) {
    super(Component.translatable("buildpack.poi.title"));
    this.parent = parent;
    this.target = target;
    this.editable = editable;
    this.onChanged = onChanged;
    for (PoiLine line : target) {
      rows.add(new Row(line));
    }
  }

  /** Opens the POI editor over the current screen; {@code onChanged} runs after a confirmed edit. */
  public static void open(List<PoiLine> target, boolean editable, Runnable onChanged) {
    Minecraft mc = Minecraft.getInstance();
    mc.setScreen(new PoiEditScreen(mc.screen, target, editable, onChanged));
  }

  private int panelH() {
    int rowCount = Math.max(1, rows.size());
    return 58 + rowCount * (ROW_H + ROW_GAP) + (editable ? ROW_H + ROW_GAP : 0);
  }

  private int left() {
    return (width - W) / 2;
  }

  private int top() {
    return Math.max(8, (height - panelH()) / 2);
  }

  @Override
  protected void init() {
    int x = left() + 10;
    int y = top() + 26;
    int idW = W - 20 - TYPE_W - CAPACITY_W - (editable ? REMOVE_W + 3 : 0) - 6;
    for (int i = 0; i < rows.size(); i++) {
      Row row = rows.get(i);
      ThemedButton typeButton = new ThemedButton(x, y, TYPE_W, ROW_H,
          typeLabel(row.type), () -> cycleType(row));
      typeButton.active = editable;
      addRenderableWidget(typeButton);

      EditBox capacity = new EditBox(font, x + TYPE_W + 3, y, CAPACITY_W, ROW_H, Component.empty());
      capacity.setMaxLength(8);
      capacity.setValue(row.capacity);
      capacity.setResponder(value -> row.capacity = value);
      capacity.setEditable(editable);
      addRenderableWidget(capacity);

      EditBox id = new EditBox(font, x + TYPE_W + CAPACITY_W + 6, y, idW, ROW_H,
          Component.translatable("buildpack.poi.id_hint"));
      id.setHint(Component.translatable("buildpack.poi.id_hint"));
      id.setMaxLength(64);
      id.setValue(row.id);
      id.setResponder(value -> row.id = value);
      id.setEditable(editable);
      addRenderableWidget(id);

      if (editable) {
        int index = i;
        addRenderableWidget(new ThemedButton(x + W - 20 - REMOVE_W, y, REMOVE_W, ROW_H,
            Component.literal("×"), () -> {
              rows.remove(index);
              rebuildWidgets();
            }));
      }
      y += ROW_H + ROW_GAP;
    }
    if (rows.isEmpty()) {
      // Room for the empty-state hint drawn in render().
      y += ROW_H + ROW_GAP;
    }

    if (editable && rows.size() < MAX_ROWS) {
      addRenderableWidget(new ThemedButton(x, y, 80, ROW_H,
          Component.translatable("buildpack.poi.add"), () -> {
            rows.add(new Row(new PoiLine("OTHER", "1", "")));
            rebuildWidgets();
          }));
      y += ROW_H + ROW_GAP;
    }

    int bw = (W - 30) / 2;
    addRenderableWidget(new ThemedButton(left() + 10, y + 4, bw, ROW_H,
        Component.translatable(editable ? "buildpack.prompt.confirm" : "buildpack.action.back"),
        this::confirm));
    if (editable) {
      addRenderableWidget(new ThemedButton(left() + 20 + bw, y + 4, bw, ROW_H,
          Component.translatable("buildpack.prompt.cancel"), this::onClose));
    }
  }

  private MutableComponent typeLabel(String type) {
    return Component.literal(type.trim().toUpperCase(Locale.ROOT));
  }

  private void cycleType(Row row) {
    List<String> types = PoiLine.KNOWN_TYPES;
    int index = types.indexOf(row.type.trim().toUpperCase(Locale.ROOT));
    row.type = types.get((index + 1) % types.size());
    rebuildWidgets();
  }

  private void confirm() {
    if (editable) {
      target.clear();
      for (Row row : rows) {
        if (!row.type.isBlank()) {
          target.add(new PoiLine(row.type.trim(), row.capacity.trim(), row.id.trim()));
        }
      }
      onChanged.run();
    }
    onClose();
  }

  @Override
  public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
    // Override vanilla: skip the Gaussian-blur background.
  }

  @Override
  public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
    g.fill(0, 0, width, height, 0xC0000000);
    BuildPackTheme.fillPanel(g, left(), top(), W, panelH(), 0xF0101010);
    g.drawString(font, Component.translatable("buildpack.poi.heading"),
        left() + 10, top() + 10, BuildPackTheme.VALUE, true);
    if (rows.isEmpty()) {
      g.drawString(font, Component.translatable("buildpack.poi.empty"),
          left() + 10, top() + 28, BuildPackTheme.HINT, true);
    }
    super.render(g, mouseX, mouseY, partialTick);
  }

  @Override
  public void onClose() {
    minecraft.setScreen(parent);
  }
}
