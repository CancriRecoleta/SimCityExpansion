package com.github.simcityexpansion.buildpack.integration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory registry of "active" build-pack buildings that should appear in SimuKraft <b>without</b>
 * being installed into {@code simukraftbuilding/}. The activation flow registers a pack's buildings
 * here (their structures already converted into this mod's cache directory); the SimuKraft mixins in
 * {@code com.github.simcityexpansion.mixin.simukraft} read it to splice those buildings into
 * SimuKraft's catalog (server) and build menu / preview (client) on the fly.
 *
 * <p>Contains no SimuKraft reference, so it loads regardless of whether SimuKraft is installed.
 * All reads return immutable snapshots and are safe to call from the server thread, the client
 * thread, and the manager UI concurrently.
 */
public final class ActivePackProvider {
  private ActivePackProvider() {}

  /** packId -> its active buildings. */
  private static final Map<String, List<ActiveBuilding>> BY_PACK = new ConcurrentHashMap<>();

  /** Immutable snapshot keyed by normalized category, rebuilt on every activation change. */
  private static volatile Map<String, List<ActiveBuilding>> byCategory = Map.of();

  /** Activates (or replaces) a pack's buildings. */
  public static void activate(String packId, List<ActiveBuilding> buildings) {
    BY_PACK.put(packId, List.copyOf(buildings));
    rebuild();
  }

  /** Deactivates a pack; its buildings disappear from SimuKraft on the next catalog/menu query. */
  public static void deactivate(String packId) {
    if (BY_PACK.remove(packId) != null) {
      rebuild();
    }
  }

  public static boolean isActive(String packId) {
    return BY_PACK.containsKey(packId);
  }

  /** Ids of all currently active packs (immutable snapshot). */
  public static Set<String> activePackIds() {
    return Set.copyOf(BY_PACK.keySet());
  }

  /** All active buildings in a category (case-insensitive), or an empty list. */
  public static List<ActiveBuilding> forCategory(String category) {
    return byCategory.getOrDefault(normalize(category), List.of());
  }

  /** Finds an active building in a category by structure file name (extension-insensitive). */
  public static Optional<ActiveBuilding> findByStructureFile(String category, String structureFileName) {
    if (structureFileName == null) {
      return Optional.empty();
    }
    String target = stripExtension(structureFileName);
    for (ActiveBuilding building : forCategory(category)) {
      if (stripExtension(building.structureFileName()).equalsIgnoreCase(target)) {
        return Optional.of(building);
      }
    }
    return Optional.empty();
  }

  private static void rebuild() {
    Map<String, List<ActiveBuilding>> grouped = new HashMap<>();
    for (List<ActiveBuilding> buildings : BY_PACK.values()) {
      for (ActiveBuilding building : buildings) {
        grouped.computeIfAbsent(normalize(building.category()), key -> new ArrayList<>()).add(building);
      }
    }
    Map<String, List<ActiveBuilding>> snapshot = new HashMap<>();
    grouped.forEach((category, buildings) -> snapshot.put(category, List.copyOf(buildings)));
    byCategory = Map.copyOf(snapshot);
  }

  private static String normalize(String category) {
    return category == null ? "other" : category.toLowerCase(Locale.ROOT);
  }

  private static String stripExtension(String fileName) {
    int dot = fileName.lastIndexOf('.');
    return dot > 0 ? fileName.substring(0, dot) : fileName;
  }
}
