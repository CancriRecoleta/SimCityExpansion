package com.github.simcityexpansion.buildpack.ui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

import com.github.simcityexpansion.buildpack.ui.component.BlockGridView;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * Full block palette: a searchable, inventory-style icon grid of every registered block, grouped
 * into category tabs (by creative-mode tab). Clicking a cell returns that block's id to the caller.
 */
public final class BlockPaletteScreen extends Screen {

  private static final int PAD = 10;
  private static final int TITLE_H = 12;
  private static final int GAP = 4;
  private static final int TAB_H = 20;
  private static final int TAB_CELL = 20;
  private static final int SEARCH_H = 16;
  private static final int BUTTON_H = 20;

  private record Tab(Component name, ItemStack icon, List<BlockGridView.Entry> entries) {}

  private final Screen previous;
  private final Consumer<String> onPick;
  private final List<Tab> tabs = new ArrayList<>();

  private int activeTab;
  private Component tabHover;
  private EditBox search;
  private BlockGridView grid;

  private BlockPaletteScreen(Screen previous, Consumer<String> onPick) {
    super(Component.translatable("buildpack.palette.title"));
    this.previous = previous;
    this.onPick = onPick;
  }

  /** Open the palette; the picked block id is delivered to {@code onPick}. */
  public static void open(Consumer<String> onPick) {
    Minecraft mc = Minecraft.getInstance();
    mc.setScreen(new BlockPaletteScreen(mc.screen, onPick));
  }

  private int tabRowY() {
    return PAD + TITLE_H;
  }

  private int searchY() {
    return tabRowY() + TAB_H + GAP;
  }

  private int boxY() {
    return searchY() + SEARCH_H + GAP;
  }

  private int boxHeight() {
    return height - PAD - BUTTON_H - GAP - boxY();
  }

  @Override
  protected void init() {
    if (tabs.isEmpty()) {
      buildTabs();
    }

    search = new EditBox(font, PAD + 2, searchY(), width - PAD * 2 - 4, SEARCH_H, Component.empty());
    search.setHint(Component.translatable("buildpack.search.placeholder"));
    search.setResponder(this::filter);
    addRenderableWidget(search);
    setInitialFocus(search);

    grid = new BlockGridView(PAD + 2, boxY() + 2, width - PAD * 2 - 4, boxHeight() - 4);
    grid.setOnSelect(entry -> onPick.accept(entry.id()));
    addRenderableWidget(grid);
    filter(search.getValue());

    addRenderableWidget(new ThemedButton(PAD, height - PAD - BUTTON_H, 80, BUTTON_H,
        Component.translatable("buildpack.action.back"), this::onClose));
  }

  /** Build category tabs: All + one per creative-mode category that holds blocks + Other. */
  private void buildTabs() {
    Map<Block, CreativeModeTab> blockTab = new LinkedHashMap<>();
    Map<CreativeModeTab, List<BlockGridView.Entry>> byTab = new LinkedHashMap<>();
    Minecraft mc = Minecraft.getInstance();
    if (mc.level != null) {
      try {
        CreativeModeTab.ItemDisplayParameters params = new CreativeModeTab.ItemDisplayParameters(
            mc.level.enabledFeatures(), true, mc.level.registryAccess());
        for (CreativeModeTab tab : CreativeModeTabs.allTabs()) {
          if (tab.getType() != CreativeModeTab.Type.CATEGORY) {
            continue;
          }
          tab.buildContents(params);
          boolean any = false;
          for (ItemStack stack : tab.getDisplayItems()) {
            if (stack.getItem() instanceof BlockItem item) {
              blockTab.putIfAbsent(item.getBlock(), tab);
              any = true;
            }
          }
          if (any) {
            byTab.put(tab, new ArrayList<>());
          }
        }
      } catch (RuntimeException ignored) {
        // creative tabs unavailable → fall back to a single "All" tab
      }
    }

    List<BlockGridView.Entry> all = new ArrayList<>();
    List<BlockGridView.Entry> other = new ArrayList<>();
    for (Block block : BuiltInRegistries.BLOCK) {
      if (block == Blocks.AIR) {
        continue;
      }
      BlockGridView.Entry entry = new BlockGridView.Entry(
          BuiltInRegistries.BLOCK.getKey(block).toString(),
          new ItemStack(block), block.defaultBlockState(), block.getName());
      all.add(entry);
      CreativeModeTab tab = blockTab.get(block);
      List<BlockGridView.Entry> bucket = tab == null ? null : byTab.get(tab);
      (bucket != null ? bucket : other).add(entry);
    }

    all.sort(Comparator.comparing(BlockGridView.Entry::id));
    tabs.add(new Tab(Component.translatable("buildpack.palette.all"),
        new ItemStack(Items.COMPASS), all));
    for (Map.Entry<CreativeModeTab, List<BlockGridView.Entry>> en : byTab.entrySet()) {
      if (!en.getValue().isEmpty()) {
        en.getValue().sort(Comparator.comparing(BlockGridView.Entry::id));
        tabs.add(new Tab(en.getKey().getDisplayName(), en.getKey().getIconItem(), en.getValue()));
      }
    }
    if (!other.isEmpty()) {
      other.sort(Comparator.comparing(BlockGridView.Entry::id));
      tabs.add(new Tab(Component.translatable("buildpack.palette.other"),
          new ItemStack(Items.BARRIER), other));
    }
  }

  private void filter(String query) {
    if (tabs.isEmpty()) {
      return;
    }
    activeTab = Math.max(0, Math.min(tabs.size() - 1, activeTab));
    List<BlockGridView.Entry> base = tabs.get(activeTab).entries();
    String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
    if (q.isEmpty()) {
      grid.setEntries(base);
      return;
    }
    List<BlockGridView.Entry> filtered = new ArrayList<>();
    for (BlockGridView.Entry entry : base) {
      if (entry.id().contains(q)
          || entry.name().getString().toLowerCase(Locale.ROOT).contains(q)) {
        filtered.add(entry);
      }
    }
    grid.setEntries(filtered);
  }

  @Override
  public boolean mouseClicked(double mouseX, double mouseY, int button) {
    if (button == 0 && mouseY >= tabRowY() && mouseY < tabRowY() + 18) {
      int i = (int) ((mouseX - PAD) / TAB_CELL);
      if (mouseX >= PAD && i >= 0 && i < tabs.size()) {
        activeTab = i;
        filter(search.getValue());
        return true;
      }
    }
    return super.mouseClicked(mouseX, mouseY, button);
  }

  @Override
  public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
    // No vanilla Gaussian blur.
  }

  @Override
  public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
    g.fill(0, 0, width, height, BuildPackTheme.ROOT_BG);
    BuildPackTheme.panel(g, PAD, boxY(), width - PAD * 2, boxHeight());
    g.drawString(font, Component.translatable("buildpack.palette.title"),
        PAD + 10, PAD, BuildPackTheme.TITLE, true);

    tabHover = null;
    int ty = tabRowY();
    for (int i = 0; i < tabs.size(); i++) {
      int tx = PAD + i * TAB_CELL;
      boolean active = i == activeTab;
      boolean hover = mouseX >= tx && mouseX < tx + 18 && mouseY >= ty && mouseY < ty + 18;
      g.fill(tx, ty, tx + 18, ty + 18, active ? 0x60FFFFFF : 0x20FFFFFF);
      if (active || hover) {
        BuildPackTheme.border(g, tx, ty, 18, 18, active ? 0xFFFFCC55 : 0xFFFFFFFF);
      }
      g.renderItem(tabs.get(i).icon(), tx + 1, ty + 1);
      if (hover) {
        tabHover = tabs.get(i).name();
      }
    }

    super.render(g, mouseX, mouseY, partialTick);

    if (grid != null && grid.hovered() != null) {
      BlockGridView.Entry h = grid.hovered();
      g.renderComponentTooltip(font,
          List.of(h.name(), Component.literal(h.id()).withStyle(ChatFormatting.GRAY)),
          mouseX, mouseY);
    } else if (tabHover != null) {
      g.renderTooltip(font, tabHover, mouseX, mouseY);
    }
  }

  @Override
  public void onClose() {
    if (previous != null) {
      minecraft.setScreen(previous);
    }
  }
}
