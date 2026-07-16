package com.github.simcityexpansion.buildpack.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.function.Consumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import org.jetbrains.annotations.Nullable;

/**
 * Modal block-state property editor for the paint block: one row per state property (facing,
 * half, shape, axis, waterlogged, ...), click to cycle through its values. Confirm returns the
 * full {@code name[k=v,...]} spec so the paint brush places the exact state — previously only the
 * eyedropper could produce non-default states.
 */
public final class BlockStatePropertyScreen extends Screen {

  private static final int W = 260;
  private static final int ROW_H = 18;
  private static final int ROW_GAP = 3;
  private static final int LABEL_W = 100;

  private final Screen parent;
  private final Consumer<String> onConfirm;
  private BlockState state;

  private BlockStatePropertyScreen(Screen parent, BlockState state, Consumer<String> onConfirm) {
    super(Component.translatable("buildpack.props.title"));
    this.parent = parent;
    this.state = state;
    this.onConfirm = onConfirm;
  }

  /**
   * Opens the editor for a {@code name} / {@code name[k=v,...]} spec; returns false (without
   * opening) when the block cannot be resolved. On confirm the callback receives the new spec.
   */
  public static boolean open(String spec, Consumer<String> onConfirm) {
    BlockState state = parseSpec(spec);
    if (state == null) {
      return false;
    }
    Minecraft mc = Minecraft.getInstance();
    mc.setScreen(new BlockStatePropertyScreen(mc.screen, state, onConfirm));
    return true;
  }

  /** Parses {@code name[k=v,...]} via the vanilla block-state NBT reader; null when unresolvable. */
  @Nullable
  public static BlockState parseSpec(String spec) {
    try {
      int bracket = spec.indexOf('[');
      CompoundTag tag = new CompoundTag();
      if (bracket < 0) {
        tag.putString("Name", spec.trim());
      } else {
        tag.putString("Name", spec.substring(0, bracket).trim());
        CompoundTag props = new CompoundTag();
        String inner = spec.endsWith("]")
            ? spec.substring(bracket + 1, spec.length() - 1) : spec.substring(bracket + 1);
        for (String pair : inner.split(",")) {
          int eq = pair.indexOf('=');
          if (eq > 0) {
            props.putString(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
          }
        }
        if (!props.isEmpty()) {
          tag.put("Properties", props);
        }
      }
      BlockState state = NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(), tag);
      return state.isAir() && !"minecraft:air".equals(tag.getString("Name")) ? null : state;
    } catch (RuntimeException e) {
      return null;
    }
  }

  /** Serializes a state back to the {@code name[k=v,...]} spec (property keys sorted). */
  public static String formatSpec(BlockState state) {
    CompoundTag tag = NbtUtils.writeBlockState(state);
    String name = tag.getString("Name");
    if (!tag.contains("Properties") || tag.getCompound("Properties").isEmpty()) {
      return name;
    }
    CompoundTag props = tag.getCompound("Properties");
    StringBuilder sb = new StringBuilder(name).append('[');
    boolean first = true;
    for (String key : new TreeSet<>(props.getAllKeys())) {
      if (!first) {
        sb.append(',');
      }
      sb.append(key).append('=').append(props.getString(key));
      first = false;
    }
    return sb.append(']').toString();
  }

  private List<Property<?>> properties() {
    return new ArrayList<>(state.getProperties());
  }

  private int panelH() {
    return 56 + Math.max(1, properties().size()) * (ROW_H + ROW_GAP);
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
    List<Property<?>> properties = properties();
    for (Property<?> property : properties) {
      ThemedButton button = new ThemedButton(x + LABEL_W, y, W - 20 - LABEL_W, ROW_H,
          valueLabel(property), () -> {
            state = cycleProperty(state, property);
            rebuildWidgets();
          });
      addRenderableWidget(button);
      y += ROW_H + ROW_GAP;
    }
    if (properties.isEmpty()) {
      y += ROW_H + ROW_GAP;
    }
    int bw = (W - 30) / 2;
    addRenderableWidget(new ThemedButton(left() + 10, y + 2, bw, ROW_H,
        Component.translatable("buildpack.prompt.confirm"), () -> {
          onConfirm.accept(formatSpec(state));
          onClose();
        }));
    addRenderableWidget(new ThemedButton(left() + 20 + bw, y + 2, bw, ROW_H,
        Component.translatable("buildpack.prompt.cancel"), this::onClose));
  }

  private Component valueLabel(Property<?> property) {
    return Component.literal(valueName(state, property));
  }

  private static <T extends Comparable<T>> String valueName(BlockState state, Property<T> property) {
    return property.getName(state.getValue(property));
  }

  /** Advances the given property to its next possible value. */
  public static <T extends Comparable<T>> BlockState cycleProperty(
      BlockState state, Property<T> property) {
    List<T> values = new ArrayList<>(property.getPossibleValues());
    int index = values.indexOf(state.getValue(property));
    return state.setValue(property, values.get((index + 1) % values.size()));
  }

  @Override
  public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
    // Override vanilla: skip the Gaussian-blur background.
  }

  @Override
  public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
    g.fill(0, 0, width, height, 0xC0000000);
    BuildPackTheme.fillPanel(g, left(), top(), W, panelH(), 0xF0101010);
    g.drawString(font, Component.translatable("buildpack.props.heading",
        BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString()),
        left() + 10, top() + 10, BuildPackTheme.VALUE, true);
    int y = top() + 26;
    List<Property<?>> properties = properties();
    for (Property<?> property : properties) {
      g.drawString(font, property.getName(), left() + 10,
          y + (ROW_H - font.lineHeight) / 2, BuildPackTheme.LABEL, true);
      y += ROW_H + ROW_GAP;
    }
    if (properties.isEmpty()) {
      g.drawString(font, Component.translatable("buildpack.props.none"),
          left() + 10, y + 4, BuildPackTheme.HINT, true);
    }
    super.render(g, mouseX, mouseY, partialTick);
  }

  @Override
  public void onClose() {
    minecraft.setScreen(parent);
  }
}
