package com.github.simcityexpansion.buildpack.integration;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/**
 * Authoring support for SimuKraft's commercial/industrial building definitions — the
 * {@code <building>.json} that lives next to a building's .sk entry inside a zip package
 * (SimuKraft resolves it via the sibling-name rule, or an explicit {@code commercial:}/
 * {@code industrial:} line in the .sk).
 *
 * <p>Provides kind detection, starter templates matching SimuKraft's official customization
 * docs, and a structural validator mirroring the rules of SimuKraft's
 * {@code CommercialDefinitionLoader}/{@code IndustrialDefinitionLoader} plus the documented
 * runtime pitfalls (missing stock rules, unregistered ids, out-of-bounds structure coordinates,
 * unknown step types, dangling point/container references).
 */
public final class SimukraftDefinitions {
  private SimukraftDefinitions() {}

  /** Definition kind, mirroring SimuKraft's two definition loaders. */
  public enum Kind {
    COMMERCIAL,
    INDUSTRIAL;

    /** Localized display name ("商业"/"工业"). */
    public Component displayName() {
      return Component.translatable(
          "buildpack.definition.kind." + name().toLowerCase(Locale.ROOT));
    }
  }

  /**
   * A single validation finding. {@code error} findings make SimuKraft treat the definition as
   * invalid; the rest are warnings about likely runtime misbehavior.
   */
  public record Issue(boolean error, Component text) {}

  private static final Set<String> VISIBILITIES = Set.of("player", "npc", "mixed");

  /** Legacy commercial keys kept by SimuKraft as compatibility read entry points. */
  private static final Set<String> LEGACY_COMMERCIAL_KEYS =
      Set.of("trades", "buyTrades", "shopMode", "sellPrice", "buyPrice");

  /**
   * Step types executed by SimuKraft's IndustrialWorkService (exact, case-sensitive), plus
   * {@code repeat}/{@code loop} which the loader expands at parse time.
   */
  private static final Set<String> STEP_TYPES = Set.of(
      "set_held_item", "repeat", "loop",
      "move_to", "move_to_container", "move_to_chest", "move_to_entity",
      "look_at", "look_at_container", "look_at_chest",
      "require_inputs", "require_output_space", "use_item",
      "craft_recipe", "craft_available_recipe", "craft_all_recipe", "real_machine_recipe",
      "inspect_container", "open_container",
      "breed_entities", "breed_animals", "slaughter_entities", "slaughter_animals",
      "shear_entities", "shear_sheep",
      "require_drops", "require_drop_items", "has_drops", "collect_drops",
      "harvest_block_clusters", "harvest_blocks",
      "deposit_carried_items", "store_carried_items", "put_carried_items",
      "insert_item", "store_item", "put_item",
      "fill_item", "fill_slot", "refill_item", "refill_slot",
      "place_block", "set_block", "place_fluid", "place_liquid",
      "destroy_block", "break_block", "remove_block",
      "require_block", "wait_for_block", "find_block", "check_block",
      "set_status");

  /** Input-group logic keys whose array children are nested input requirements. */
  private static final Set<String> INPUT_GROUP_KEYS =
      Set.of("或", "与", "or", "and", "anyOf", "allOf");

  /** Whether the text parses as a JSON object at all (the only hard requirement for saving). */
  public static boolean parses(String text) {
    try {
      return JsonParser.parseString(text).isJsonObject();
    } catch (RuntimeException e) {
      return false;
    }
  }

  /** Detects the definition kind, or null when neither commercial nor industrial markers exist. */
  @Nullable
  public static Kind detect(String text) {
    try {
      JsonElement element = JsonParser.parseString(text);
      return element.isJsonObject() ? detectKind(element.getAsJsonObject()) : null;
    } catch (RuntimeException e) {
      return null;
    }
  }

  /** {@link #detect(String)} for an already-parsed root object. */
  @Nullable
  public static Kind detectRoot(JsonObject root) {
    return detectKind(root);
  }

  @Nullable
  private static Kind detectKind(JsonObject root) {
    if (root.has("offers")) {
      return Kind.COMMERCIAL;
    }
    if (root.has("recipes")) {
      return Kind.INDUSTRIAL;
    }
    if (root.has("job") || root.has("workTime")
        || LEGACY_COMMERCIAL_KEYS.stream().anyMatch(root::has)) {
      return Kind.COMMERCIAL;
    }
    if (root.has("points") || root.has("workArea") || root.has("spawnEntity")) {
      return Kind.INDUSTRIAL;
    }
    return null;
  }

  // ---- Templates (modeled on SimuKraft's commercial/industrial customization docs) ----

  /** Starter commercial definition (one sell offer, one buy offer) prefilled from the building. */
  public static String commercialTemplate(String id, String name, String jobType) {
    return """
        {
          "id": %s,
          "name": %s,
          "job": {
            "id": %s,
            "name": %s,
            "heldItem": "minecraft:bread"
          },
          "workTime": { "start": 1000, "end": 13000 },
          "offers": [
            {
              "id": "sell_bread",
              "visibleTo": "mixed",
              "cost": [{ "money": 0.25 }],
              "result": [{ "item": "minecraft:bread", "count": 1 }],
              "stock": {
                "item": "minecraft:bread",
                "max": 64,
                "initial": 16,
                "restockAmount": 8,
                "restockInterval": 12000
              }
            },
            {
              "id": "buy_wheat",
              "visibleTo": "player",
              "cost": [{ "item": "minecraft:wheat", "count": 16 }],
              "result": [{ "money": 3.2 }],
              "stock": { "item": "minecraft:wheat", "max": 256 }
            }
          ]
        }
        """.formatted(quote(id), quote(name), quote(jobKey(id, jobType)),
            quote(translate("buildpack.definition.template.job_commercial")));
  }

  /** Starter industrial definition (stand/input/output plus one craft recipe) prefilled from the building. */
  public static String industrialTemplate(String id, String name, String jobType) {
    return """
        {
          "id": %s,
          "name": %s,
          "jobType": %s,
          "jobName": %s,
          "heldItem": "minecraft:wheat",
          "points": {
            "stand": { "type": "structure_pos", "pos": [1, 1, 1] }
          },
          "containers": {
            "input": { "type": "structure_pos", "pos": [2, 1, 1] },
            "output": { "type": "structure_pos", "pos": [3, 1, 1] }
          },
          "recipes": [
            {
              "id": "recipe_1",
              "name": %s,
              "inputs": [{ "item": "minecraft:wheat", "count": 3 }],
              "outputs": [{ "item": "minecraft:bread", "baseAmount": 1, "probability": 1.0 }],
              "steps": [
                { "type": "move_to_container", "container": "input", "range": 1.2 },
                { "type": "look_at_container", "container": "input" },
                { "type": "inspect_container", "container": "input", "ticks": 20 },
                { "type": "require_inputs", "container": "input" },
                { "type": "require_output_space", "container": "output" },
                { "type": "set_held_item", "item": "minecraft:wheat" },
                { "type": "move_to", "point": "stand", "range": 1.2 },
                { "type": "use_item", "ticks": 60, "swing": true },
                { "type": "move_to_container", "container": "output", "range": 1.2 },
                { "type": "look_at_container", "container": "output" },
                { "type": "inspect_container", "container": "output", "ticks": 20 },
                { "type": "craft_recipe", "input": "input", "output": "output" }
              ]
            }
          ]
        }
        """.formatted(quote(id), quote(name), quote(jobKey(id, jobType)),
            quote(translate("buildpack.definition.template.job_industrial")),
            quote(translate("buildpack.definition.template.recipe")));
  }

  /** Job key for templates: the .sk {@code job_type} when present, else derived from the id. */
  private static String jobKey(String id, String jobType) {
    if (jobType != null && !jobType.isBlank()) {
      return jobType.trim();
    }
    String key = id == null ? "" : id.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
    return (key.replace("_", "").isEmpty() ? "building" : key) + "_worker";
  }

  private static String quote(String value) {
    return new JsonPrimitive(value == null ? "" : value).toString();
  }

  private static String translate(String key) {
    return Component.translatable(key).getString();
  }

  // ---- Validation ----

  /**
   * Validates definition JSON against SimuKraft's loader rules and documented pitfalls. The
   * structure size bounds the structure-coordinate checks; pass zeros when unknown to skip them.
   */
  public static List<Issue> validate(String text, int sizeX, int sizeY, int sizeZ) {
    List<Issue> issues = new ArrayList<>();
    JsonObject root;
    try {
      JsonElement element = JsonParser.parseString(text);
      if (!element.isJsonObject()) {
        issues.add(new Issue(true, Component.translatable("buildpack.definition.issue.not_object")));
        return issues;
      }
      root = element.getAsJsonObject();
    } catch (RuntimeException e) {
      String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
      issues.add(new Issue(true,
          Component.translatable("buildpack.definition.issue.syntax", reason)));
      return issues;
    }
    Kind kind = detectKind(root);
    if (kind == null) {
      issues.add(new Issue(true, Component.translatable("buildpack.definition.issue.unknown_kind")));
      return issues;
    }
    Validator validator = new Validator(issues, sizeX, sizeY, sizeZ);
    if (root.has("offers") && root.has("recipes")) {
      validator.warn("buildpack.definition.issue.both_kinds");
    }
    if (kind == Kind.COMMERCIAL) {
      validator.commercial(root);
    } else {
      validator.industrial(root);
    }
    return issues;
  }

  /**
   * Structure-aware checks that need the actual block data (run in addition to
   * {@link #validate}): points/containers whose {@code type} is not {@code structure_pos} are
   * silently ignored by SimuKraft, and container coordinates should point at a block that can
   * hold items (air or a plain block means the NPC finds no container at runtime).
   */
  public static List<Issue> validateAgainstStructure(
      String text, com.github.simcityexpansion.buildpack.convert.NbtStructure structure) {
    List<Issue> issues = new ArrayList<>();
    JsonObject root;
    try {
      JsonElement element = JsonParser.parseString(text);
      if (!element.isJsonObject()) {
        return issues;
      }
      root = element.getAsJsonObject();
    } catch (RuntimeException e) {
      return issues;
    }
    java.util.Map<String, String> blockAt = new java.util.HashMap<>();
    for (var block : structure.blocks) {
      var entry = block.stateIndex() >= 0 && block.stateIndex() < structure.palette.size()
          ? structure.palette.get(block.stateIndex()) : null;
      if (entry != null && !entry.isAir()) {
        blockAt.put(block.x() + "," + block.y() + "," + block.z(), entry.blockName());
      }
    }
    checkPosHolders(root, "points", blockAt, issues, false);
    checkPosHolders(root, "containers", blockAt, issues, true);
    return issues;
  }

  /** Walks one points/containers map: type check for all, target-block checks for containers. */
  private static void checkPosHolders(JsonObject root, String mapKey,
      java.util.Map<String, String> blockAt, List<Issue> issues, boolean requireContainer) {
    if (!root.has(mapKey) || !root.get(mapKey).isJsonObject()) {
      return;
    }
    for (var entry : root.getAsJsonObject(mapKey).entrySet()) {
      if (!entry.getValue().isJsonObject()) {
        continue;
      }
      JsonObject holder = entry.getValue().getAsJsonObject();
      String type = str(holder, "type");
      if (!type.isBlank() && !"structure_pos".equals(type)) {
        issues.add(new Issue(false, Component.translatable(
            "buildpack.definition.issue.pos_type", mapKey, entry.getKey(), type)));
        continue;
      }
      if (!requireContainer) {
        continue;
      }
      for (int[] pos : holderPositions(holder)) {
        String key = pos[0] + "," + pos[1] + "," + pos[2];
        String blockName = blockAt.get(key);
        if (blockName == null) {
          issues.add(new Issue(false, Component.translatable(
              "buildpack.definition.issue.container_air", entry.getKey(), key)));
        } else if (!blockHasEntity(blockName)) {
          issues.add(new Issue(false, Component.translatable(
              "buildpack.definition.issue.container_not_container",
              entry.getKey(), key, blockName)));
        }
      }
    }
  }

  /** All integer coordinates declared by a pos holder ({@code pos} and {@code positions}). */
  private static List<int[]> holderPositions(JsonObject holder) {
    List<int[]> positions = new ArrayList<>();
    if (holder.has("pos")) {
      addIntPos(positions, holder.get("pos"));
    }
    if (holder.has("positions") && holder.get("positions").isJsonArray()) {
      for (JsonElement element : holder.getAsJsonArray("positions")) {
        addIntPos(positions, element);
      }
    }
    return positions;
  }

  private static void addIntPos(List<int[]> positions, JsonElement element) {
    if (!element.isJsonArray() || element.getAsJsonArray().size() < 3) {
      return;
    }
    JsonArray array = element.getAsJsonArray();
    try {
      positions.add(new int[] {
          array.get(0).getAsInt(), array.get(1).getAsInt(), array.get(2).getAsInt()});
    } catch (RuntimeException ignored) {
      // Non-numeric coordinates are already reported by the shape checks in validate().
    }
  }

  /** Whether the block id resolves and its default state has a block entity (container heuristic). */
  private static boolean blockHasEntity(String blockName) {
    ResourceLocation id = ResourceLocation.tryParse(blockName);
    return id != null && BuiltInRegistries.BLOCK.getOptional(id)
        .map(block -> block.defaultBlockState().hasBlockEntity())
        // Unresolvable ids (mod not installed) cannot be judged; give the author the benefit.
        .orElse(true);
  }

  /** Stateful walk over one definition document, accumulating issues. */
  private static final class Validator {
    private final List<Issue> issues;
    private final int sizeX;
    private final int sizeY;
    private final int sizeZ;
    private final Set<String> reportedIds = new HashSet<>();

    Validator(List<Issue> issues, int sizeX, int sizeY, int sizeZ) {
      this.issues = issues;
      this.sizeX = sizeX;
      this.sizeY = sizeY;
      this.sizeZ = sizeZ;
    }

    // ---- Commercial ----

    void commercial(JsonObject root) {
      if (strAny(root, "name", "buildingName", "building_name").isBlank()) {
        warn("buildpack.definition.issue.no_name");
      }
      JsonArray offers = arr(root, "offers");
      if (offers == null || offers.isEmpty()) {
        error("buildpack.definition.issue.no_offers");
        return;
      }
      Set<String> offerIds = new HashSet<>();
      Set<String> stockItems = new HashSet<>();
      List<String> soldItems = new ArrayList<>();
      for (int i = 0; i < offers.size(); i++) {
        JsonObject offer = asObject(offers.get(i));
        if (offer == null) {
          error("buildpack.definition.issue.offer_invalid", i + 1);
          continue;
        }
        String id = str(offer, "id").isBlank() ? "offer_" + i : str(offer, "id");
        if (!offerIds.add(id)) {
          warn("buildpack.definition.issue.duplicate_offer", id);
        }
        String visibleTo = strAny(offer, "visibleTo", "visible_to", "visibility");
        if (!visibleTo.isBlank() && !VISIBILITIES.contains(visibleTo)) {
          warn("buildpack.definition.issue.visible_to", id);
        }
        checkResources(arrAny(offer, "cost", "costs"),
            id, "cost", "buildpack.definition.issue.offer_cost");
        List<String> resultItems = checkResources(arrAny(offer, "result", "results"),
            id, "result", "buildpack.definition.issue.offer_result");
        soldItems.addAll(resultItems);
        JsonObject stock = obj(offer, "stock");
        if (stock != null) {
          String stockItem = str(stock, "item");
          if (!stockItem.isBlank()) {
            checkRegistered(stockItem, BuiltInRegistries.ITEM,
                "buildpack.definition.issue.unknown_item");
            stockItems.add(stockItem);
          }
          JsonArray materials =
              arrAny(stock, "materials", "requiredMaterials", "required_materials");
          if (materials != null) {
            for (JsonElement element : materials) {
              JsonObject material = asObject(element);
              if (material != null && !str(material, "item").isBlank()) {
                checkRegistered(str(material, "item"), BuiltInRegistries.ITEM,
                    "buildpack.definition.issue.unknown_item");
              }
            }
          }
        }
      }
      // Sold/exchanged items without any stock rule are purchasable without limit (documented pitfall).
      for (String item : soldItems) {
        if (!stockItems.contains(item) && reportedIds.add("stock:" + item)) {
          warn("buildpack.definition.issue.no_stock", item);
        }
      }
      JsonObject containers = obj(root, "containers");
      if (containers != null) {
        for (String key : containers.keySet()) {
          JsonObject container = asObject(containers.get(key));
          if (container != null) {
            checkPositions(container, "containers." + key);
          }
        }
      }
    }

    /** Validates one cost/result list; returns the item ids it references. */
    private List<String> checkResources(
        @Nullable JsonArray resources, String offerId, String listName, String emptyKey) {
      List<String> items = new ArrayList<>();
      if (resources == null || resources.isEmpty()) {
        error(emptyKey, offerId);
        return items;
      }
      if (resources.size() > 4) {
        warn("buildpack.definition.issue.resource_limit", offerId, listName);
      }
      for (int i = 0; i < resources.size(); i++) {
        JsonObject resource = asObject(resources.get(i));
        if (resource == null) {
          error("buildpack.definition.issue.resource_invalid", offerId, listName, i + 1);
          continue;
        }
        if (resource.has("money")) {
          if (number(resource, "money") <= 0.0) {
            error("buildpack.definition.issue.resource_invalid", offerId, listName, i + 1);
          }
          continue;
        }
        String item = str(resource, "item");
        if (item.isBlank()) {
          error("buildpack.definition.issue.resource_invalid", offerId, listName, i + 1);
          continue;
        }
        checkRegistered(item, BuiltInRegistries.ITEM, "buildpack.definition.issue.unknown_item");
        items.add(item);
      }
      return items;
    }

    // ---- Industrial ----

    void industrial(JsonObject root) {
      if (str(root, "name").isBlank()) {
        warn("buildpack.definition.issue.no_name");
      }
      if (strAny(root, "jobType", "JobType", "job_type").isBlank()) {
        warn("buildpack.definition.issue.no_job_type");
      }
      checkOptionalItem(str(root, "heldItem"));

      Set<String> pointIds = new HashSet<>();
      JsonObject points = obj(root, "points");
      if (points != null) {
        for (String key : points.keySet()) {
          JsonObject point = asObject(points.get(key));
          if (point != null) {
            pointIds.add(key);
            checkPositions(point, "points." + key);
          }
        }
      }
      Set<String> containerIds = new HashSet<>();
      JsonObject containers = obj(root, "containers");
      if (containers != null) {
        for (String key : containers.keySet()) {
          JsonObject container = asObject(containers.get(key));
          if (container != null) {
            containerIds.add(key);
            checkPositions(container, "containers." + key);
          }
        }
      }
      if (containerIds.isEmpty()) {
        warn("buildpack.definition.issue.no_containers");
      }
      JsonObject workArea = obj(root, "workArea");
      if (workArea != null && !str(workArea, "type").isBlank()
          && !"building_outer_rect".equals(str(workArea, "type"))) {
        warn("buildpack.definition.issue.work_area_type");
      }
      JsonObject spawnEntity = obj(root, "spawnEntity");
      if (spawnEntity != null) {
        String type = strAny(spawnEntity, "type", "entityType", "entity");
        if (!type.isBlank()) {
          checkRegistered(type, BuiltInRegistries.ENTITY_TYPE,
              "buildpack.definition.issue.unknown_entity");
        }
      }

      JsonArray recipes = arr(root, "recipes");
      if (recipes == null || recipes.isEmpty()) {
        error("buildpack.definition.issue.no_recipes");
        return;
      }
      for (int i = 0; i < recipes.size(); i++) {
        JsonObject recipe = asObject(recipes.get(i));
        if (recipe == null) {
          error("buildpack.definition.issue.recipe_invalid", i + 1);
          continue;
        }
        String id = str(recipe, "id").isBlank() ? "recipe_" + i : str(recipe, "id");
        JsonArray inputs = arr(recipe, "inputs");
        JsonArray outputs = arr(recipe, "outputs");
        if (inputs == null || outputs == null) {
          warn("buildpack.definition.issue.recipe_no_io", id);
        }
        if (inputs != null) {
          for (int j = 0; j < inputs.size(); j++) {
            checkInput(inputs.get(j), id, j + 1);
          }
        }
        if (outputs != null) {
          for (int j = 0; j < outputs.size(); j++) {
            checkOutput(outputs.get(j), id, j + 1);
          }
        }
        JsonArray steps = arr(recipe, "steps");
        if (steps == null || steps.isEmpty()) {
          error("buildpack.definition.issue.recipe_steps", id);
          continue;
        }
        checkSteps(steps, id, pointIds, containerIds);
      }
    }

    /** One input requirement: a leaf spec, a nested 或/与 group, or a string shorthand. */
    private void checkInput(JsonElement element, String recipeId, int index) {
      if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
        checkItemSpecString(element.getAsString());
        return;
      }
      JsonObject input = asObject(element);
      if (input == null) {
        error("buildpack.definition.issue.input_empty", recipeId, index);
        return;
      }
      for (String groupKey : INPUT_GROUP_KEYS) {
        JsonArray children = arr(input, groupKey);
        if (children != null) {
          for (JsonElement child : children) {
            checkInput(child, recipeId, index);
          }
          return;
        }
      }
      String item = str(input, "item");
      String tag = strAny(input, "tag", "itemTag", "item_tag");
      boolean hasStack = !strAny(input, "itemStack", "itemString", "stack").isBlank();
      if (item.isBlank() && tag.isBlank() && !hasStack) {
        error("buildpack.definition.issue.input_empty", recipeId, index);
        return;
      }
      checkOptionalItem(item);
      checkIdFormat(tag);
    }

    /** One product output: must resolve to a concrete item (tag-only outputs cannot materialize). */
    private void checkOutput(JsonElement element, String recipeId, int index) {
      JsonObject output = asObject(element);
      if (output == null) {
        warn("buildpack.definition.issue.output_not_concrete", recipeId, index);
        return;
      }
      String item = str(output, "item");
      boolean hasStack = !strAny(output, "itemStack", "itemString", "stack").isBlank();
      if (item.isBlank() && !hasStack) {
        warn("buildpack.definition.issue.output_not_concrete", recipeId, index);
        return;
      }
      checkOptionalItem(item);
    }

    private void checkSteps(
        JsonArray steps, String recipeId, Set<String> pointIds, Set<String> containerIds) {
      for (int i = 0; i < steps.size(); i++) {
        JsonObject step = asObject(steps.get(i));
        if (step == null) {
          error("buildpack.definition.issue.step_invalid", recipeId, i + 1);
          continue;
        }
        String type = str(step, "type");
        if (type.isBlank()) {
          error("buildpack.definition.issue.step_no_type", recipeId, i + 1);
          continue;
        }
        if (!STEP_TYPES.contains(type)) {
          warn("buildpack.definition.issue.step_unknown_type", recipeId, type);
        }
        if ("repeat".equals(type) || "loop".equals(type)) {
          checkPositions(step, stepContext(recipeId, i));
          JsonArray nested = arr(step, "steps");
          if (nested != null) {
            checkSteps(nested, recipeId, pointIds, containerIds);
          }
          continue;
        }
        String point = str(step, "point");
        if (!point.isBlank() && !pointIds.contains(point)
            && reportedIds.add("point:" + recipeId + ":" + point)) {
          warn("buildpack.definition.issue.step_bad_point", recipeId, point);
        }
        for (String key : new String[] {"container", "input", "output"}) {
          String container = str(step, key);
          if (!container.isBlank() && !containerIds.contains(container)
              && reportedIds.add("container:" + recipeId + ":" + container)) {
            warn("buildpack.definition.issue.step_bad_container", recipeId, container);
          }
        }
        checkOptionalItem(str(step, "item"));
        JsonArray candidates = arr(step, "items");
        if (candidates != null) {
          for (JsonElement candidate : candidates) {
            if (candidate.isJsonPrimitive() && candidate.getAsJsonPrimitive().isString()) {
              checkItemSpecString(candidate.getAsString());
            } else if (asObject(candidate) != null) {
              checkOptionalItem(str(asObject(candidate), "item"));
            }
          }
        }
        String entityType = strAny(step, "entityType", "entity");
        if (!entityType.isBlank()) {
          checkRegistered(entityType, BuiltInRegistries.ENTITY_TYPE,
              "buildpack.definition.issue.unknown_entity");
        }
        String block = str(step, "block");
        if (!block.isBlank()) {
          checkRegistered(block, BuiltInRegistries.BLOCK,
              "buildpack.definition.issue.unknown_block");
        }
        String fluid = strAny(step, "fluid", "liquid");
        if (!fluid.isBlank()) {
          checkRegistered(fluid, BuiltInRegistries.FLUID,
              "buildpack.definition.issue.unknown_fluid");
        }
        checkPositions(step, stepContext(recipeId, i));
      }
    }

    private String stepContext(String recipeId, int index) {
      return recipeId + ".steps[" + (index + 1) + "]";
    }

    // ---- Shared checks ----

    /** Checks {@code pos}/{@code positions} entries for shape and structure bounds. */
    private void checkPositions(JsonObject holder, String context) {
      if (holder.has("pos")) {
        checkPos(holder.get("pos"), context);
      }
      JsonArray positions = arr(holder, "positions");
      if (positions != null) {
        for (JsonElement element : positions) {
          checkPos(element, context);
        }
      }
    }

    private void checkPos(JsonElement element, String context) {
      if (!element.isJsonArray() || element.getAsJsonArray().size() < 3) {
        warn("buildpack.definition.issue.pos_format", context);
        return;
      }
      JsonArray array = element.getAsJsonArray();
      int x;
      int y;
      int z;
      try {
        x = array.get(0).getAsInt();
        y = array.get(1).getAsInt();
        z = array.get(2).getAsInt();
      } catch (RuntimeException e) {
        warn("buildpack.definition.issue.pos_format", context);
        return;
      }
      if (sizeX <= 0 || sizeY <= 0 || sizeZ <= 0) {
        return;
      }
      if (x < 0 || x >= sizeX || y < 0 || y >= sizeY || z < 0 || z >= sizeZ) {
        warn("buildpack.definition.issue.pos_bounds", context, x + ", " + y + ", " + z,
            sizeX + " x " + sizeY + " x " + sizeZ);
      }
    }

    /** Warns when a non-blank id is malformed or not registered in the given registry. */
    private void checkRegistered(String id, Registry<?> registry, String key) {
      ResourceLocation location = ResourceLocation.tryParse(id.trim());
      if (location == null) {
        warn("buildpack.definition.issue.bad_id", id);
      } else if (!registry.containsKey(location)) {
        warn(key, id);
      }
    }

    private void checkOptionalItem(String item) {
      if (!item.isBlank()) {
        checkRegistered(item, BuiltInRegistries.ITEM, "buildpack.definition.issue.unknown_item");
      }
    }

    /** A string spec: {@code #ns:tag} checks format only, bracketed stacks are skipped. */
    private void checkItemSpecString(String spec) {
      String trimmed = spec.trim();
      if (trimmed.isEmpty() || trimmed.contains("[")) {
        return;
      }
      if (trimmed.startsWith("#")) {
        checkIdFormat(trimmed.substring(1));
      } else {
        checkOptionalItem(trimmed);
      }
    }

    private void checkIdFormat(String id) {
      if (!id.isBlank() && ResourceLocation.tryParse(id.trim()) == null) {
        warn("buildpack.definition.issue.bad_id", id);
      }
    }

    private void warn(String key, Object... args) {
      issues.add(new Issue(false, Component.translatable(key, args)));
    }

    private void error(String key, Object... args) {
      issues.add(new Issue(true, Component.translatable(key, args)));
    }
  }

  // ---- Gson helpers (lenient like SimuKraft's loaders) ----

  @Nullable
  private static JsonObject asObject(@Nullable JsonElement element) {
    return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
  }

  @Nullable
  private static JsonObject obj(JsonObject parent, String key) {
    return parent.has(key) && parent.get(key).isJsonObject() ? parent.getAsJsonObject(key) : null;
  }

  @Nullable
  private static JsonArray arr(JsonObject parent, String key) {
    return parent.has(key) && parent.get(key).isJsonArray() ? parent.getAsJsonArray(key) : null;
  }

  @Nullable
  private static JsonArray arrAny(JsonObject parent, String... keys) {
    for (String key : keys) {
      JsonArray array = arr(parent, key);
      if (array != null) {
        return array;
      }
    }
    return null;
  }

  private static String str(JsonObject parent, String key) {
    if (!parent.has(key) || !parent.get(key).isJsonPrimitive()) {
      return "";
    }
    try {
      return parent.get(key).getAsString().trim();
    } catch (RuntimeException e) {
      return "";
    }
  }

  private static String strAny(JsonObject parent, String... keys) {
    for (String key : keys) {
      String value = str(parent, key);
      if (!value.isBlank()) {
        return value;
      }
    }
    return "";
  }

  private static double number(JsonObject parent, String key) {
    if (!parent.has(key) || !parent.get(key).isJsonPrimitive()) {
      return 0.0;
    }
    try {
      return parent.get(key).getAsDouble();
    } catch (RuntimeException e) {
      return 0.0;
    }
  }
}
