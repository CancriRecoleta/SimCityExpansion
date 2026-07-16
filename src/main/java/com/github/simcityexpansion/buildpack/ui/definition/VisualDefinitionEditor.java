package com.github.simcityexpansion.buildpack.ui.definition;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import com.github.simcityexpansion.buildpack.integration.SimukraftDefinitions.Kind;
import com.github.simcityexpansion.buildpack.ui.BuildPackTheme;
import com.github.simcityexpansion.buildpack.ui.ContextMenu;
import com.github.simcityexpansion.buildpack.ui.TextPromptScreen;
import com.github.simcityexpansion.buildpack.ui.ThemedButton;
import com.github.simcityexpansion.buildpack.ui.tree.TreeNode;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

/**
 * Low-code visual editor over a parsed definition document: turns the gson tree into a structure
 * tree (basics / offers / points / containers / recipes / inputs / outputs / steps) and builds a
 * small fitting form for the selected node. Field edits write straight into the {@link JsonObject}
 * tree; add/remove/move operations report back through {@link Host#structureChanged} so the
 * hosting screen can rebuild the tree and widgets. Advanced or exotic fields stay editable in the
 * JSON mode — this editor covers the documented common cases without ever destroying unknown keys.
 */
public final class VisualDefinitionEditor {

  /** Callbacks into the hosting screen. */
  public interface Host {
    /** A field value changed (mark the document dirty). */
    void markDirty();

    /** Tree structure changed; rebuild tree/widgets and reselect {@code select} when non-null. */
    void structureChanged(@Nullable JsonElement select);

    /** Opens a context menu on top of the screen. */
    void openMenu(ContextMenu menu);
  }

  /** Node kinds of the structure tree. */
  public enum NodeKind {
    INFO,
    OFFER_GROUP,
    OFFER,
    POINT_GROUP,
    POINT,
    CONTAINER_GROUP,
    CONTAINER,
    RECIPE_GROUP,
    RECIPE,
    INPUT_GROUP,
    INPUT,
    OUTPUT_GROUP,
    OUTPUT,
    STEP_GROUP,
    STEP
  }

  /**
   * A tree node payload: the JSON element being edited plus context for structure operations.
   *
   * @param object the edited object (null for non-object array elements, e.g. string inputs)
   * @param owner group nodes: the object owning the child collection; map entries: the map object
   * @param array array elements: the owning array (also the target array of group add operations)
   * @param index array elements: position inside {@code array}
   * @param mapKey map entries (points/containers): the entry key
   */
  public record DefinitionNode(NodeKind kind, @Nullable JsonObject object,
      @Nullable JsonObject owner, @Nullable JsonArray array, int index, @Nullable String mapKey) {

    /** Chip color used by the tree icon (the "color partition" of node categories). */
    public int iconColor() {
      return switch (kind) {
        case INFO -> 0xFFB0BEC5;
        case OFFER_GROUP, OFFER -> 0xFFF6C445;
        case POINT_GROUP, POINT -> 0xFF4DB6AC;
        case CONTAINER_GROUP, CONTAINER -> 0xFFBCAAA4;
        case RECIPE_GROUP, RECIPE -> 0xFF64B5F6;
        case INPUT_GROUP, INPUT -> 0xFF81C784;
        case OUTPUT_GROUP, OUTPUT -> 0xFFFFB74D;
        case STEP_GROUP, STEP -> 0xFFBA68C8;
      };
    }
  }

  private static final int ROW_H = 16;
  private static final int ROW_GAP = 3;
  private static final int LABEL_W = 52;

  /** Step types offered by the "common types" menu (the full set stays available via JSON). */
  private static final List<String> COMMON_STEP_TYPES = List.of(
      "move_to", "move_to_container", "look_at", "look_at_container", "inspect_container",
      "require_inputs", "require_output_space", "set_held_item", "use_item", "craft_recipe",
      "real_machine_recipe", "insert_item", "collect_drops", "deposit_carried_items",
      "set_status");

  private static final List<String> VISIBILITIES = List.of("player", "npc", "mixed");
  private static final List<String> SELECT_MODES = List.of("nearest", "ordered");

  private record FieldLabel(Component text, int x, int y) {}

  private final Host host;
  private JsonObject root;
  private Kind kind = Kind.COMMERCIAL;
  private final Map<JsonElement, TreeNode<String, Object>> nodeIndex = new IdentityHashMap<>();

  // Form build state (valid between buildForm and the next rebuild).
  private final List<FieldLabel> labels = new ArrayList<>();
  private Font font;
  private Consumer<AbstractWidget> sink;
  private int formX;
  private int formW;
  private int cursorY;

  public VisualDefinitionEditor(Host host) {
    this.host = host;
  }

  /** Binds the parsed document (call before {@link #buildTree}). */
  public void bind(JsonObject root, Kind kind) {
    this.root = root;
    this.kind = kind;
  }

  /** The bound document root (null when nothing is bound). */
  @Nullable
  public JsonObject root() {
    return root;
  }

  // ---- Tree building ----

  /** Builds the structure tree for the bound document and refreshes the reselection index. */
  public TreeNode<String, Object> buildTree() {
    nodeIndex.clear();
    TreeNode<String, Object> tree = new TreeNode<>("root", null);
    if (root == null) {
      return tree;
    }
    TreeNode<String, Object> info = new TreeNode<>(translate("buildpack.definition.node.info"),
        new DefinitionNode(NodeKind.INFO, root, null, null, -1, null));
    tree.addChild(info);
    nodeIndex.put(root, info);
    if (kind == Kind.COMMERCIAL) {
      tree.addChild(arrayGroup(NodeKind.OFFER_GROUP, NodeKind.OFFER,
          "buildpack.definition.node.offers", root, "offers", this::offerLabel));
    } else {
      tree.addChild(mapGroup(NodeKind.POINT_GROUP, NodeKind.POINT,
          "buildpack.definition.node.points", "points"));
      tree.addChild(mapGroup(NodeKind.CONTAINER_GROUP, NodeKind.CONTAINER,
          "buildpack.definition.node.containers", "containers"));
      tree.addChild(recipeGroup());
    }
    return tree;
  }

  /** Tree node built for a JSON element in the latest {@link #buildTree} pass, or null. */
  @Nullable
  public TreeNode<String, Object> nodeFor(JsonElement element) {
    return nodeIndex.get(element);
  }

  private TreeNode<String, Object> arrayGroup(NodeKind groupKind, NodeKind childKind,
      String labelKey, JsonObject owner, String field, Consumer<GroupChild> labeler) {
    JsonArray array = owner.has(field) && owner.get(field).isJsonArray()
        ? owner.getAsJsonArray(field) : null;
    int count = array == null ? 0 : array.size();
    TreeNode<String, Object> group = new TreeNode<>(translate(labelKey) + " (" + count + ")",
        new DefinitionNode(groupKind, owner, owner, array, -1, null));
    if (array != null) {
      for (int i = 0; i < array.size(); i++) {
        JsonElement element = array.get(i);
        JsonObject object = element.isJsonObject() ? element.getAsJsonObject() : null;
        GroupChild child = new GroupChild(element, object, i);
        labeler.accept(child);
        TreeNode<String, Object> node = new TreeNode<>(child.label,
            new DefinitionNode(childKind, object, null, array, i, null));
        group.addChild(node);
        nodeIndex.put(element, node);
      }
    }
    return group;
  }

  /** Mutable holder passed to labelers while building array groups. */
  private static final class GroupChild {
    final JsonElement element;
    @Nullable
    final JsonObject object;
    final int index;
    String label = "";

    GroupChild(JsonElement element, @Nullable JsonObject object, int index) {
      this.element = element;
      this.object = object;
      this.index = index;
    }
  }

  private void offerLabel(GroupChild child) {
    child.label = child.object == null
        ? (child.index + 1) + ". ?"
        : str(child.object, "id").isBlank() ? "offer_" + child.index : str(child.object, "id");
  }

  private TreeNode<String, Object> mapGroup(NodeKind groupKind, NodeKind childKind,
      String labelKey, String field) {
    JsonObject map = root.has(field) && root.get(field).isJsonObject()
        ? root.getAsJsonObject(field) : null;
    int count = map == null ? 0 : map.size();
    TreeNode<String, Object> group = new TreeNode<>(translate(labelKey) + " (" + count + ")",
        new DefinitionNode(groupKind, root, root, null, -1, field));
    if (map != null) {
      for (String key : map.keySet()) {
        JsonElement element = map.get(key);
        JsonObject object = element.isJsonObject() ? element.getAsJsonObject() : null;
        TreeNode<String, Object> node = new TreeNode<>(key,
            new DefinitionNode(childKind, object, map, null, -1, key));
        group.addChild(node);
        nodeIndex.put(element, node);
      }
    }
    return group;
  }

  private TreeNode<String, Object> recipeGroup() {
    JsonArray recipes = root.has("recipes") && root.get("recipes").isJsonArray()
        ? root.getAsJsonArray("recipes") : null;
    int count = recipes == null ? 0 : recipes.size();
    TreeNode<String, Object> group =
        new TreeNode<>(translate("buildpack.definition.node.recipes") + " (" + count + ")",
            new DefinitionNode(NodeKind.RECIPE_GROUP, root, root, recipes, -1, null));
    if (recipes == null) {
      return group;
    }
    for (int i = 0; i < recipes.size(); i++) {
      JsonElement element = recipes.get(i);
      JsonObject recipe = element.isJsonObject() ? element.getAsJsonObject() : null;
      String label = recipe == null ? (i + 1) + ". ?"
          : str(recipe, "id").isBlank() ? "recipe_" + i : str(recipe, "id");
      TreeNode<String, Object> node = new TreeNode<>(label,
          new DefinitionNode(NodeKind.RECIPE, recipe, null, recipes, i, null));
      group.addChild(node);
      nodeIndex.put(element, node);
      if (recipe != null) {
        node.addChild(arrayGroup(NodeKind.INPUT_GROUP, NodeKind.INPUT,
            "buildpack.definition.node.inputs", recipe, "inputs", this::inputLabel));
        node.addChild(arrayGroup(NodeKind.OUTPUT_GROUP, NodeKind.OUTPUT,
            "buildpack.definition.node.outputs", recipe, "outputs", this::outputLabel));
        node.addChild(arrayGroup(NodeKind.STEP_GROUP, NodeKind.STEP,
            "buildpack.definition.node.steps", recipe, "steps", this::stepLabel));
      }
    }
    return group;
  }

  private void inputLabel(GroupChild child) {
    if (child.object == null) {
      child.label = (child.index + 1) + ". " + shortText(child.element);
      return;
    }
    String what = !str(child.object, "item").isBlank() ? str(child.object, "item")
        : !str(child.object, "tag").isBlank() ? "#" + str(child.object, "tag") : "?";
    child.label = (child.index + 1) + ". " + what;
  }

  private void outputLabel(GroupChild child) {
    child.label = child.object == null
        ? (child.index + 1) + ". " + shortText(child.element)
        : (child.index + 1) + ". "
            + (str(child.object, "item").isBlank() ? "?" : str(child.object, "item"));
  }

  private void stepLabel(GroupChild child) {
    child.label = child.object == null
        ? (child.index + 1) + ". ?"
        : (child.index + 1) + ". "
            + (str(child.object, "type").isBlank() ? "?" : str(child.object, "type"));
  }

  private static String shortText(JsonElement element) {
    String text = element.toString();
    return text.length() > 18 ? text.substring(0, 18) + "…" : text;
  }

  // ---- Form building ----

  /**
   * Builds the form widgets for a node into the given area; returns the bottom Y. Labels are
   * rendered separately via {@link #renderLabels}.
   */
  public int buildForm(DefinitionNode node, Font font, int x, int y, int width,
      Consumer<AbstractWidget> add) {
    this.font = font;
    this.sink = add;
    this.formX = x;
    this.formW = width;
    this.cursorY = y;
    labels.clear();
    switch (node.kind()) {
      case INFO -> {
        if (kind == Kind.COMMERCIAL) {
          commercialInfoForm();
        } else {
          industrialInfoForm();
        }
      }
      case OFFER -> offerForm(node);
      case POINT, CONTAINER -> mapEntryForm(node);
      case RECIPE -> recipeForm(node);
      case INPUT -> inputForm(node);
      case OUTPUT -> outputForm(node);
      case STEP -> stepForm(node);
      case OFFER_GROUP -> addButton("buildpack.definition.visual.add_offer", this::addOffer);
      case POINT_GROUP -> addButton("buildpack.definition.visual.add_point",
          () -> addMapEntry("points", "point"));
      case CONTAINER_GROUP -> addButton("buildpack.definition.visual.add_container",
          () -> addMapEntry("containers", "container"));
      case RECIPE_GROUP -> addButton("buildpack.definition.visual.add_recipe", this::addRecipe);
      case INPUT_GROUP -> addButton("buildpack.definition.visual.add_input",
          () -> addArrayElement(node, defaultInput()));
      case OUTPUT_GROUP -> addButton("buildpack.definition.visual.add_output",
          () -> addArrayElement(node, defaultOutput()));
      case STEP_GROUP -> stepGroupForm(node);
    }
    return cursorY;
  }

  /** Draws the field labels created by the latest {@link #buildForm}. */
  public void renderLabels(GuiGraphics g) {
    if (font == null) {
      return;
    }
    for (FieldLabel label : labels) {
      g.drawString(font, label.text(), label.x(),
          label.y() + (ROW_H - font.lineHeight) / 2, BuildPackTheme.LABEL, true);
    }
  }

  // ---- Individual forms ----

  private void commercialInfoForm() {
    textRow("buildpack.definition.field.id", str(root, "id"), v -> setString(root, "id", v));
    textRow("buildpack.definition.field.name", str(root, "name"),
        v -> setString(root, "name", v));
    JsonObject job = ensureObject(root, "job");
    textRow("buildpack.definition.field.job_id", str(job, "id"), v -> setString(job, "id", v));
    textRow("buildpack.definition.field.job_name", str(job, "name"),
        v -> setString(job, "name", v));
    textRow("buildpack.definition.field.held_item", str(job, "heldItem"),
        v -> setString(job, "heldItem", v));
    JsonObject workTime = ensureObject(root, "workTime");
    int half = (formW - LABEL_W - 4) / 2;
    label("buildpack.definition.field.work_time");
    textField(formX + LABEL_W, half, str(workTime, "start"),
        v -> setInt(workTime, "start", v));
    textField(formX + LABEL_W + half + 4, half, str(workTime, "end"),
        v -> setInt(workTime, "end", v));
    nextRow();
  }

  private void industrialInfoForm() {
    textRow("buildpack.definition.field.id", str(root, "id"), v -> setString(root, "id", v));
    textRow("buildpack.definition.field.name", str(root, "name"),
        v -> setString(root, "name", v));
    textRow("buildpack.definition.field.job_id", str(root, "jobType"),
        v -> setString(root, "jobType", v));
    textRow("buildpack.definition.field.job_name", str(root, "jobName"),
        v -> setString(root, "jobName", v));
    textRow("buildpack.definition.field.held_item", str(root, "heldItem"),
        v -> setString(root, "heldItem", v));
  }

  private void offerForm(DefinitionNode node) {
    JsonObject offer = node.object();
    if (offer == null) {
      hintRow("buildpack.definition.visual.raw_hint");
      arrayOpButtons(node);
      return;
    }
    textRow("buildpack.definition.field.id", str(offer, "id"), v -> setString(offer, "id", v));
    cycleRow("buildpack.definition.field.visible_to", VISIBILITIES,
        str(offer, "visibleTo").isBlank() ? "player" : str(offer, "visibleTo"),
        v -> setString(offer, "visibleTo", v));
    resourceRow("buildpack.definition.field.cost", offer, "cost");
    resourceRow("buildpack.definition.field.result", offer, "result");
    // The stock object is created lazily on the first write, so merely viewing an offer never
    // injects an empty (semantically dubious) stock rule into the document.
    JsonObject stockRead = obj(offer, "stock");
    label("buildpack.definition.field.stock");
    int itemW = formW - LABEL_W - 40;
    textField(formX + LABEL_W, itemW, str(stockRead, "item"),
        v -> setString(ensureObject(offer, "stock"), "item", v));
    textField(formX + LABEL_W + itemW + 4, 36, str(stockRead, "max"),
        v -> setInt(ensureObject(offer, "stock"), "max", v));
    nextRow();
    label("buildpack.definition.field.restock");
    int third = (formW - LABEL_W - 8) / 3;
    hintedField(formX + LABEL_W, third, str(stockRead, "initial"),
        "buildpack.definition.field.initial",
        v -> setInt(ensureObject(offer, "stock"), "initial", v));
    hintedField(formX + LABEL_W + third + 4, third, str(stockRead, "restockAmount"),
        "buildpack.definition.field.restock_amount",
        v -> setInt(ensureObject(offer, "stock"), "restockAmount", v));
    hintedField(formX + LABEL_W + (third + 4) * 2, third, str(stockRead, "restockInterval"),
        "buildpack.definition.field.restock_interval",
        v -> setInt(ensureObject(offer, "stock"), "restockInterval", v));
    nextRow();
    arrayOpButtons(node);
  }

  /** One "type + value (+ count)" row bound to the first element of a cost/result list. */
  private void resourceRow(String labelKey, JsonObject offer, String field) {
    JsonObject resource = firstObject(ensureArray(offer, field), "cost".equals(field));
    boolean money = resource.has("money");
    label(labelKey);
    int typeW = 44;
    int x = formX + LABEL_W;
    Component typeLabel = Component.translatable(money
        ? "buildpack.definition.field.money" : "buildpack.definition.field.item");
    sink.accept(new ThemedButton(x, cursorY, typeW, ROW_H, typeLabel, () -> {
      // Toggle the resource shape between {money} and {item, count}; rebuild to relayout.
      for (String key : List.copyOf(resource.keySet())) {
        resource.remove(key);
      }
      if (money) {
        resource.addProperty("item", "minecraft:bread");
        resource.addProperty("count", 1);
      } else {
        resource.addProperty("money", 1.0);
      }
      host.markDirty();
      host.structureChanged(offer);
    }));
    x += typeW + 4;
    if (money) {
      textField(x, formW - LABEL_W - typeW - 4, str(resource, "money"),
          v -> setDouble(resource, "money", v));
    } else {
      int itemW = formW - LABEL_W - typeW - 48;
      textField(x, itemW, str(resource, "item"), v -> setString(resource, "item", v));
      textField(x + itemW + 4, 40, str(resource, "count"), v -> setInt(resource, "count", v));
    }
    nextRow();
  }

  private void mapEntryForm(DefinitionNode node) {
    JsonObject entry = node.object();
    if (entry == null) {
      hintRow("buildpack.definition.visual.raw_hint");
      return;
    }
    textRow("buildpack.definition.field.positions", positionsText(entry),
        v -> writePositions(entry, v));
    if (node.kind() == NodeKind.POINT) {
      cycleRow("buildpack.definition.field.select_mode", SELECT_MODES,
          str(entry, "select").isBlank() ? "nearest" : str(entry, "select"),
          v -> setString(entry, "select", v));
    }
    int half = (formW - 4) / 2;
    sink.accept(new ThemedButton(formX, cursorY, half, ROW_H,
        Component.translatable("buildpack.menu.rename"), () -> renameMapEntry(node)));
    sink.accept(new ThemedButton(formX + half + 4, cursorY, half, ROW_H,
        Component.translatable("buildpack.menu.delete"), () -> {
          node.owner().remove(node.mapKey());
          host.markDirty();
          host.structureChanged(null);
        }));
    nextRow();
  }

  private void recipeForm(DefinitionNode node) {
    JsonObject recipe = node.object();
    if (recipe == null) {
      hintRow("buildpack.definition.visual.raw_hint");
      arrayOpButtons(node);
      return;
    }
    textRow("buildpack.definition.field.id", str(recipe, "id"),
        v -> setString(recipe, "id", v));
    textRow("buildpack.definition.field.name", str(recipe, "name"),
        v -> setString(recipe, "name", v));
    textRow("buildpack.definition.field.held_item", str(recipe, "heldItem"),
        v -> setString(recipe, "heldItem", v));
    arrayOpButtons(node);
  }

  private void inputForm(DefinitionNode node) {
    JsonObject input = node.object();
    if (input == null) {
      hintRow("buildpack.definition.visual.raw_hint");
      arrayOpButtons(node);
      return;
    }
    textRow("buildpack.definition.field.item", str(input, "item"),
        v -> setString(input, "item", v));
    textRow("buildpack.definition.field.tag", str(input, "tag"),
        v -> setString(input, "tag", v));
    textRow("buildpack.definition.field.count", str(input, "count"),
        v -> setInt(input, "count", v));
    boolRow("buildpack.definition.field.consume",
        !input.has("consume") || bool(input, "consume", true), v -> {
          // The loader defaults consume to true; only an explicit false needs to be stored.
          if (v) {
            input.remove("consume");
          } else {
            input.addProperty("consume", false);
          }
        });
    arrayOpButtons(node);
  }

  private void outputForm(DefinitionNode node) {
    JsonObject output = node.object();
    if (output == null) {
      hintRow("buildpack.definition.visual.raw_hint");
      arrayOpButtons(node);
      return;
    }
    textRow("buildpack.definition.field.item", str(output, "item"),
        v -> setString(output, "item", v));
    textRow("buildpack.definition.field.base_amount", str(output, "baseAmount"),
        v -> setInt(output, "baseAmount", v));
    textRow("buildpack.definition.field.random_range", str(output, "randomRange"),
        v -> setInt(output, "randomRange", v));
    textRow("buildpack.definition.field.probability", str(output, "probability"),
        v -> setDouble(output, "probability", v));
    boolRow("buildpack.definition.field.ignore_multiplier",
        bool(output, "ignoreMultiplier", false), v -> {
          if (v) {
            output.addProperty("ignoreMultiplier", true);
          } else {
            output.remove("ignoreMultiplier");
          }
        });
    arrayOpButtons(node);
  }

  private void stepForm(DefinitionNode node) {
    JsonObject step = node.object();
    if (step == null) {
      hintRow("buildpack.definition.visual.raw_hint");
      arrayOpButtons(node);
      return;
    }
    label("buildpack.definition.field.type");
    int menuW = 60;
    int typeW = formW - LABEL_W - menuW - 4;
    textField(formX + LABEL_W, typeW, str(step, "type"), v -> setString(step, "type", v));
    int menuX = formX + LABEL_W + typeW + 4;
    int menuY = cursorY;
    sink.accept(new ThemedButton(menuX, menuY, menuW, ROW_H,
        Component.translatable("buildpack.definition.visual.common_types"),
        () -> openStepTypeMenu(menuX, menuY + ROW_H,
            type -> {
              step.addProperty("type", type);
              host.markDirty();
              host.structureChanged(step);
            })));
    nextRow();
    textRow("buildpack.definition.field.point", str(step, "point"),
        v -> setString(step, "point", v));
    textRow("buildpack.definition.field.container", str(step, "container"),
        v -> setString(step, "container", v));
    textRow("buildpack.definition.field.item", str(step, "item"),
        v -> setString(step, "item", v));
    int third = (formW - LABEL_W - 8) / 3;
    label("buildpack.definition.field.count");
    hintedField(formX + LABEL_W, third, str(step, "count"),
        "buildpack.definition.field.count", v -> setInt(step, "count", v));
    hintedField(formX + LABEL_W + third + 4, third, str(step, "ticks"),
        "buildpack.definition.field.ticks", v -> setInt(step, "ticks", v));
    hintedField(formX + LABEL_W + (third + 4) * 2, third, str(step, "range"),
        "buildpack.definition.field.range", v -> setDouble(step, "range", v));
    nextRow();
    boolRow("buildpack.definition.field.swing", bool(step, "swing", false), v -> {
      if (v) {
        step.addProperty("swing", true);
      } else {
        step.remove("swing");
      }
    });
    hintRow("buildpack.definition.visual.advanced_hint");
    arrayOpButtons(node);
  }

  private void stepGroupForm(DefinitionNode node) {
    int x = formX;
    int y = cursorY;
    sink.accept(new ThemedButton(x, y, formW, ROW_H,
        Component.translatable("buildpack.definition.visual.add_step"),
        () -> openStepTypeMenu(x, y + ROW_H, type -> {
          JsonObject step = new JsonObject();
          step.addProperty("type", type);
          addArrayElement(node, step);
        })));
    nextRow();
  }

  private void openStepTypeMenu(int x, int y, Consumer<String> onPick) {
    List<ContextMenu.Item> items = new ArrayList<>();
    for (String type : COMMON_STEP_TYPES) {
      items.add(new ContextMenu.Item(Component.literal(type), () -> onPick.accept(type)));
    }
    host.openMenu(new ContextMenu(x, y, items));
  }

  // ---- Structure operations ----

  private void addButton(String labelKey, Runnable action) {
    sink.accept(new ThemedButton(formX, cursorY, formW, ROW_H,
        Component.translatable(labelKey), action));
    nextRow();
  }

  /** Delete / move-up / move-down buttons shared by all array element forms. */
  private void arrayOpButtons(DefinitionNode node) {
    if (node.array() == null) {
      return;
    }
    int third = (formW - 8) / 3;
    sink.accept(new ThemedButton(formX, cursorY, third, ROW_H,
        Component.translatable("buildpack.menu.delete"), () -> {
          node.array().remove(node.index());
          host.markDirty();
          host.structureChanged(null);
        }));
    sink.accept(new ThemedButton(formX + third + 4, cursorY, third, ROW_H,
        Component.translatable("buildpack.definition.visual.move_up"),
        () -> moveElement(node, -1)));
    sink.accept(new ThemedButton(formX + (third + 4) * 2, cursorY, third, ROW_H,
        Component.translatable("buildpack.definition.visual.move_down"),
        () -> moveElement(node, 1)));
    nextRow();
  }

  private void moveElement(DefinitionNode node, int direction) {
    JsonArray array = node.array();
    int from = node.index();
    int to = from + direction;
    if (array == null || to < 0 || to >= array.size()) {
      return;
    }
    JsonElement moved = array.get(from);
    array.set(from, array.get(to));
    array.set(to, moved);
    host.markDirty();
    host.structureChanged(moved);
  }

  private void addOffer() {
    JsonArray offers = ensureArray(root, "offers");
    JsonObject offer = new JsonObject();
    offer.addProperty("id", uniqueArrayId(offers, "offer"));
    offer.addProperty("visibleTo", "player");
    JsonArray cost = new JsonArray();
    JsonObject money = new JsonObject();
    money.addProperty("money", 1.0);
    cost.add(money);
    offer.add("cost", cost);
    JsonArray result = new JsonArray();
    JsonObject item = new JsonObject();
    item.addProperty("item", "minecraft:bread");
    item.addProperty("count", 1);
    result.add(item);
    offer.add("result", result);
    JsonObject stock = new JsonObject();
    stock.addProperty("item", "minecraft:bread");
    stock.addProperty("max", 64);
    offer.add("stock", stock);
    offers.add(offer);
    host.markDirty();
    host.structureChanged(offer);
  }

  private void addRecipe() {
    JsonArray recipes = ensureArray(root, "recipes");
    JsonObject recipe = new JsonObject();
    recipe.addProperty("id", uniqueArrayId(recipes, "recipe"));
    recipe.addProperty("name", translate("buildpack.definition.template.recipe"));
    JsonArray inputs = new JsonArray();
    inputs.add(defaultInput());
    recipe.add("inputs", inputs);
    JsonArray outputs = new JsonArray();
    outputs.add(defaultOutput());
    recipe.add("outputs", outputs);
    JsonArray steps = new JsonArray();
    steps.add(step("require_inputs"));
    steps.add(step("require_output_space"));
    steps.add(step("use_item"));
    steps.add(step("craft_recipe"));
    recipe.add("steps", steps);
    recipes.add(recipe);
    host.markDirty();
    host.structureChanged(recipe);
  }

  private static JsonObject step(String type) {
    JsonObject step = new JsonObject();
    step.addProperty("type", type);
    if ("use_item".equals(type)) {
      step.addProperty("ticks", 60);
      step.addProperty("swing", true);
    }
    return step;
  }

  private static JsonObject defaultInput() {
    JsonObject input = new JsonObject();
    input.addProperty("item", "minecraft:wheat");
    input.addProperty("count", 1);
    return input;
  }

  private static JsonObject defaultOutput() {
    JsonObject output = new JsonObject();
    output.addProperty("item", "minecraft:bread");
    output.addProperty("baseAmount", 1);
    return output;
  }

  /** Appends an element to the array a group node manages (creating the array when absent). */
  private void addArrayElement(DefinitionNode groupNode, JsonObject element) {
    JsonArray array = groupNode.array();
    if (array == null) {
      array = ensureArray(groupNode.object(), switch (groupNode.kind()) {
        case INPUT_GROUP -> "inputs";
        case OUTPUT_GROUP -> "outputs";
        case STEP_GROUP -> "steps";
        case OFFER_GROUP -> "offers";
        default -> "recipes";
      });
    }
    array.add(element);
    host.markDirty();
    host.structureChanged(element);
  }

  private void addMapEntry(String field, String prefix) {
    JsonObject map = ensureObject(root, field);
    String key = "containers".equals(field) && !map.has("input") ? "input"
        : "containers".equals(field) && !map.has("output") ? "output"
        : uniqueMapKey(map, prefix);
    JsonObject entry = new JsonObject();
    entry.addProperty("type", "structure_pos");
    JsonArray pos = new JsonArray();
    pos.add(1);
    pos.add(1);
    pos.add(1);
    entry.add("pos", pos);
    map.add(key, entry);
    host.markDirty();
    host.structureChanged(entry);
  }

  private void renameMapEntry(DefinitionNode node) {
    TextPromptScreen.open(Component.translatable("buildpack.menu.rename"),
        Component.translatable("buildpack.definition.prompt.key"), node.mapKey(), newKey -> {
          JsonObject owner = node.owner();
          if (owner.has(newKey) || node.mapKey().equals(newKey)) {
            return;
          }
          JsonElement value = owner.get(node.mapKey());
          owner.remove(node.mapKey());
          owner.add(newKey, value);
          host.markDirty();
          host.structureChanged(value);
        });
  }

  private static String uniqueArrayId(JsonArray array, String prefix) {
    return prefix + "_" + (array.size() + 1);
  }

  private static String uniqueMapKey(JsonObject map, String prefix) {
    int i = 1;
    while (map.has(prefix + "_" + i)) {
      i++;
    }
    return prefix + "_" + i;
  }

  // ---- Positions text codec ("x,y,z; x,y,z") ----

  private static String positionsText(JsonObject entry) {
    List<String> parts = new ArrayList<>();
    if (entry.has("pos") && entry.get("pos").isJsonArray()) {
      parts.add(posToText(entry.getAsJsonArray("pos")));
    }
    if (entry.has("positions") && entry.get("positions").isJsonArray()) {
      for (JsonElement element : entry.getAsJsonArray("positions")) {
        if (element.isJsonArray()) {
          parts.add(posToText(element.getAsJsonArray()));
        }
      }
    }
    return String.join("; ", parts);
  }

  private static String posToText(JsonArray pos) {
    List<String> nums = new ArrayList<>();
    for (JsonElement element : pos) {
      nums.add(element.toString());
    }
    return String.join(",", nums);
  }

  private void writePositions(JsonObject entry, String text) {
    List<JsonArray> parsed = new ArrayList<>();
    for (String part : text.split(";")) {
      String[] nums = part.trim().split("[,\\s]+");
      if (nums.length < 3) {
        continue;
      }
      try {
        JsonArray pos = new JsonArray();
        pos.add(Integer.parseInt(nums[0].trim()));
        pos.add(Integer.parseInt(nums[1].trim()));
        pos.add(Integer.parseInt(nums[2].trim()));
        parsed.add(pos);
      } catch (NumberFormatException ignored) {
        // Half-typed coordinates are skipped until they parse.
      }
    }
    entry.remove("pos");
    entry.remove("positions");
    if (parsed.size() == 1) {
      entry.add("pos", parsed.get(0));
    } else if (parsed.size() > 1) {
      JsonArray positions = new JsonArray();
      parsed.forEach(positions::add);
      entry.add("positions", positions);
    }
    host.markDirty();
  }

  // ---- Row/widget helpers ----

  private void textRow(String labelKey, String value, Consumer<String> setter) {
    label(labelKey);
    textField(formX + LABEL_W, formW - LABEL_W, value, setter);
    nextRow();
  }

  private void cycleRow(String labelKey, List<String> options, String current,
      Consumer<String> setter) {
    label(labelKey);
    ThemedButton[] holder = new ThemedButton[1];
    holder[0] = new ThemedButton(formX + LABEL_W, cursorY, formW - LABEL_W, ROW_H,
        Component.literal(current), () -> {
          int next = (options.indexOf(holder[0].getMessage().getString()) + 1) % options.size();
          String value = options.get(next);
          holder[0].setMessage(Component.literal(value));
          setter.accept(value);
          host.markDirty();
        });
    sink.accept(holder[0]);
    nextRow();
  }

  private void boolRow(String labelKey, boolean current, Consumer<Boolean> setter) {
    label(labelKey);
    boolean[] state = {current};
    ThemedButton[] holder = new ThemedButton[1];
    holder[0] = new ThemedButton(formX + LABEL_W, cursorY, formW - LABEL_W, ROW_H,
        yesNo(current), () -> {
          state[0] = !state[0];
          holder[0].setMessage(yesNo(state[0]));
          setter.accept(state[0]);
          host.markDirty();
        });
    sink.accept(holder[0]);
    nextRow();
  }

  private static Component yesNo(boolean value) {
    return Component.translatable(
        value ? "buildpack.definition.value.yes" : "buildpack.definition.value.no");
  }

  private void hintRow(String key) {
    labels.add(new FieldLabel(Component.translatable(key), formX, cursorY));
    nextRow();
  }

  private void label(String key) {
    labels.add(new FieldLabel(Component.translatable(key), formX, cursorY));
  }

  private void textField(int x, int width, String value, Consumer<String> setter) {
    EditBox box = new EditBox(font, x, cursorY, width, ROW_H, Component.empty());
    box.setMaxLength(256);
    box.setValue(value);
    box.setResponder(v -> {
      setter.accept(v);
      host.markDirty();
    });
    sink.accept(box);
  }

  /** A small field with a placeholder hint (used for compact multi-field rows). */
  private void hintedField(int x, int width, String value, String hintKey,
      Consumer<String> setter) {
    EditBox box = new EditBox(font, x, cursorY, width, ROW_H, Component.empty());
    box.setMaxLength(16);
    box.setHint(Component.translatable(hintKey));
    box.setValue(value);
    box.setResponder(v -> {
      setter.accept(v);
      host.markDirty();
    });
    sink.accept(box);
  }

  private void nextRow() {
    cursorY += ROW_H + ROW_GAP;
  }

  // ---- JSON access helpers ----

  @Nullable
  private static JsonObject obj(JsonObject owner, String key) {
    return owner.has(key) && owner.get(key).isJsonObject() ? owner.getAsJsonObject(key) : null;
  }

  private static String str(JsonObject object, String key) {
    if (object == null || !object.has(key) || !object.get(key).isJsonPrimitive()) {
      return "";
    }
    try {
      return object.get(key).getAsString();
    } catch (RuntimeException e) {
      return "";
    }
  }

  private static boolean bool(JsonObject object, String key, boolean fallback) {
    if (!object.has(key) || !object.get(key).isJsonPrimitive()) {
      return fallback;
    }
    try {
      return object.get(key).getAsBoolean();
    } catch (RuntimeException e) {
      return fallback;
    }
  }

  private void setString(JsonObject object, String key, String value) {
    if (value == null || value.isBlank()) {
      object.remove(key);
    } else {
      object.addProperty(key, value.trim());
    }
  }

  private void setInt(JsonObject object, String key, String raw) {
    if (raw == null || raw.isBlank()) {
      object.remove(key);
      return;
    }
    try {
      object.addProperty(key, Integer.parseInt(raw.trim()));
    } catch (NumberFormatException ignored) {
      // Keep the previous value until the input parses.
    }
  }

  private void setDouble(JsonObject object, String key, String raw) {
    if (raw == null || raw.isBlank()) {
      object.remove(key);
      return;
    }
    try {
      object.addProperty(key, Double.parseDouble(raw.trim()));
    } catch (NumberFormatException ignored) {
      // Keep the previous value until the input parses.
    }
  }

  private static JsonObject ensureObject(JsonObject owner, String key) {
    if (!owner.has(key) || !owner.get(key).isJsonObject()) {
      owner.add(key, new JsonObject());
    }
    return owner.getAsJsonObject(key);
  }

  private static JsonArray ensureArray(JsonObject owner, String key) {
    if (!owner.has(key) || !owner.get(key).isJsonArray()) {
      owner.add(key, new JsonArray());
    }
    return owner.getAsJsonArray(key);
  }

  /** First object of the list (created when absent: money for cost, an item for result). */
  private static JsonObject firstObject(JsonArray array, boolean moneyDefault) {
    if (array.isEmpty() || !array.get(0).isJsonObject()) {
      JsonObject created = new JsonObject();
      if (moneyDefault) {
        created.addProperty("money", 1.0);
      } else {
        created.addProperty("item", "minecraft:bread");
        created.addProperty("count", 1);
      }
      if (array.isEmpty()) {
        array.add(created);
      } else {
        array.set(0, created);
      }
      return created;
    }
    return array.get(0).getAsJsonObject();
  }

  private static String translate(String key) {
    return Component.translatable(key).getString();
  }
}
