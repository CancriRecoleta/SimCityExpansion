package com.github.simcityexpansion.buildpack.ui;

import java.util.ArrayList;
import java.util.List;

import com.github.simcityexpansion.buildpack.convert.NbtStructure;
import com.github.simcityexpansion.buildpack.model.PoiLine;
import com.github.simcityexpansion.buildpack.validate.BuildingDoctor;
import com.github.simcityexpansion.buildpack.validate.BuildingDoctor.Finding;
import com.github.simcityexpansion.buildpack.validate.BuildingDoctor.Severity;
import com.github.simcityexpansion.buildpack.validate.LayoutOverlay;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.jetbrains.annotations.Nullable;

/**
 * Pack-doctor report page: findings from {@link BuildingDoctor} as a scrollable list, severity
 * color-coded ([error] red, [warning] yellow, [info] grey); "Back" returns to the opener.
 */
public final class DoctorReportScreen extends Screen {

  private static final int PAD = 10;
  private static final int TITLE_H = 12;
  private static final int GAP = 4;
  private static final int BUTTON_H = 20;
  private static final int LINE_GAP = 2;
  private static final int FINDING_GAP = 5;

  /** One pre-wrapped rendered line. */
  private record Line(FormattedCharSequence text, int color, boolean findingStart) {}

  private final Screen previous;
  private final String buildingName;
  private final List<Finding> findings;
  @Nullable
  private final NbtStructure structure;
  private final List<PoiLine> poiLines;
  @Nullable
  private final String definitionJson;

  private final List<Line> lines = new ArrayList<>();
  private double scroll;
  private int contentHeight;

  private DoctorReportScreen(Screen previous, String buildingName, List<Finding> findings,
      @Nullable NbtStructure structure, List<PoiLine> poiLines, @Nullable String definitionJson) {
    super(Component.translatable("buildpack.doctor.screen_title"));
    this.previous = previous;
    this.buildingName = buildingName;
    this.findings = findings;
    this.structure = structure;
    this.poiLines = poiLines;
    this.definitionJson = definitionJson;
  }

  /** Opens the report screen (returns to the screen that opened it on close). */
  public static void open(String buildingName, List<Finding> findings) {
    open(buildingName, findings, null, List.of(), null);
  }

  /**
   * Opens the report screen with layout-overlay context: when a structure is given, a "layout
   * preview" button shows homes/POI landings/definition coordinates in 3D.
   */
  public static void open(String buildingName, List<Finding> findings,
      @Nullable NbtStructure structure, List<PoiLine> poiLines, @Nullable String definitionJson) {
    Minecraft mc = Minecraft.getInstance();
    mc.setScreen(new DoctorReportScreen(
        mc.screen, buildingName, findings, structure, poiLines, definitionJson));
  }

  private int boxY() {
    return PAD + TITLE_H + GAP;
  }

  private int boxHeight() {
    return height - PAD - BUTTON_H - GAP - boxY();
  }

  @Override
  protected void init() {
    addRenderableWidget(new ThemedButton(PAD, height - PAD - BUTTON_H, 80, BUTTON_H,
        Component.translatable("buildpack.action.back"), this::onClose));
    if (structure != null) {
      addRenderableWidget(new ThemedButton(PAD + 80 + GAP, height - PAD - BUTTON_H, 100, BUTTON_H,
          Component.translatable("buildpack.overlay.open"),
          () -> StructurePreviewScreen.open(structure,
              LayoutOverlay.compute(structure, poiLines, definitionJson))));
    }
    buildLines();
  }

  private void buildLines() {
    lines.clear();
    int wrapWidth = width - PAD * 2 - 16;
    if (findings.isEmpty()) {
      for (FormattedCharSequence seq
          : font.split(Component.translatable("buildpack.doctor.ok"), wrapWidth)) {
        lines.add(new Line(seq, BuildPackTheme.MESSAGE_OK, lines.isEmpty()));
      }
    }
    for (Finding finding : findings) {
      Component text = Component.translatable(
              "buildpack.doctor.sev." + finding.severity().name().toLowerCase(java.util.Locale.ROOT))
          .append(" ")
          .append(finding.text());
      boolean first = true;
      for (FormattedCharSequence seq : font.split(text, wrapWidth)) {
        lines.add(new Line(seq, colorOf(finding.severity()), first));
        first = false;
      }
    }
    contentHeight = 0;
    for (Line line : lines) {
      contentHeight += (line.findingStart() && contentHeight > 0 ? FINDING_GAP : 0)
          + font.lineHeight + LINE_GAP;
    }
  }

  private static int colorOf(Severity severity) {
    return switch (severity) {
      case ERROR -> BuildPackTheme.MESSAGE_ERROR;
      case WARN -> BuildPackTheme.MESSAGE_WARN;
      case INFO -> BuildPackTheme.LABEL;
    };
  }

  @Override
  public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
    // Override vanilla: skip the Gaussian-blur background.
  }

  @Override
  public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
    g.fill(0, 0, width, height, BuildPackTheme.ROOT_BG);
    BuildPackTheme.panel(g, PAD, boxY(), width - PAD * 2, boxHeight());
    int[] counts = BuildingDoctor.counts(findings);
    g.drawString(font, Component.translatable(
            "buildpack.doctor.screen_heading", buildingName, counts[0], counts[1]),
        PAD + 2, PAD, BuildPackTheme.TITLE, true);

    g.enableScissor(PAD + 1, boxY() + 1, width - PAD - 1, boxY() + boxHeight() - 1);
    int y = boxY() + 4 - (int) scroll;
    boolean firstLine = true;
    for (Line line : lines) {
      if (line.findingStart() && !firstLine) {
        y += FINDING_GAP;
      }
      firstLine = false;
      if (y > boxY() - font.lineHeight && y < boxY() + boxHeight()) {
        g.drawString(font, line.text(), PAD + 8, y, line.color(), true);
      }
      y += font.lineHeight + LINE_GAP;
    }
    g.disableScissor();

    super.render(g, mouseX, mouseY, partialTick);
  }

  @Override
  public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
    int max = Math.max(0, contentHeight + 8 - boxHeight());
    scroll = Math.max(0, Math.min(max, scroll - scrollY * font.lineHeight * 3));
    return true;
  }

  @Override
  public void onClose() {
    if (previous != null) {
      minecraft.setScreen(previous);
    } else {
      BuildPackScreen.open();
    }
  }
}
