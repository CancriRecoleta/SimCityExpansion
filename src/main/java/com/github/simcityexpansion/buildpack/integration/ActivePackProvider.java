package com.github.simcityexpansion.buildpack.integration;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory registry of "active" build packs that should appear in SimuKraft <b>without</b> being
 * installed into {@code simukraftbuilding/}. The activation flow converts a pack into a cache zip
 * package ({@code simcity_expansion/cache/<packId>.zip}, SimuKraft 2.0 package layout) and
 * registers it here; the {@code BuildingPackageCatalogMixin} splices those cache zips into
 * SimuKraft's package scan, and the client-menu mixin uses the building metadata for remote
 * (server-pushed) entries on dedicated servers.
 *
 * <p>Contains no SimuKraft reference, so it loads regardless of whether SimuKraft is installed.
 * All reads return immutable snapshots and are safe to call from the server thread, the client
 * thread, and the manager UI concurrently.
 */
public final class ActivePackProvider {
  private ActivePackProvider() {}

  /** An active pack: its cache zip package plus per-building display metadata. */
  private record ActivePack(Path cacheZip, List<ActiveBuilding> buildings) {}

  /** packId -> active pack. */
  private static final Map<String, ActivePack> BY_PACK = new ConcurrentHashMap<>();

  /**
   * Buildings pushed from a remote (dedicated) server for display only — the cache zip lives
   * server-side. Kept separate from {@link #BY_PACK} so a host client never mixes them with its
   * own locally-activated packs. Immutable snapshot keyed by normalized category.
   */
  private static volatile Map<String, List<ActiveBuilding>> remoteByCategory = Map.of();

  /** Activates (or replaces) a pack: its cache zip package and building metadata. */
  public static void activate(String packId, Path cacheZip, List<ActiveBuilding> buildings) {
    BY_PACK.put(packId, new ActivePack(cacheZip, List.copyOf(buildings)));
  }

  /** Deactivates a pack; its package disappears from SimuKraft on the next catalog reload. */
  public static void deactivate(String packId) {
    BY_PACK.remove(packId);
  }

  public static boolean isActive(String packId) {
    return BY_PACK.containsKey(packId);
  }

  /** Ids of all currently active packs (immutable snapshot). */
  public static Set<String> activePackIds() {
    return Set.copyOf(BY_PACK.keySet());
  }

  /** Cache zip packages of all active packs, spliced into SimuKraft's package scan by mixin. */
  public static List<Path> activeCacheZips() {
    return BY_PACK.values().stream().map(ActivePack::cacheZip).toList();
  }

  /** All locally-activated buildings across packs (used by the server to sync clients). */
  public static List<ActiveBuilding> allActive() {
    List<ActiveBuilding> all = new ArrayList<>();
    BY_PACK.values().forEach(pack -> all.addAll(pack.buildings()));
    return all;
  }

  /** Replaces the remote (server-pushed) building set on a connected client. */
  public static void replaceRemote(List<ActiveBuilding> buildings) {
    Map<String, List<ActiveBuilding>> grouped = new HashMap<>();
    for (ActiveBuilding building : buildings) {
      grouped.computeIfAbsent(normalize(building.category()), key -> new ArrayList<>()).add(building);
    }
    Map<String, List<ActiveBuilding>> snapshot = new HashMap<>();
    grouped.forEach((category, list) -> snapshot.put(category, List.copyOf(list)));
    remoteByCategory = Map.copyOf(snapshot);
  }

  /**
   * Remote (server-pushed) buildings in a category (case-insensitive). Locally activated packs are
   * not included: their cache zips are part of the (client-side) package scan already, so the
   * client menu sees them through SimuKraft's own pipeline.
   */
  public static List<ActiveBuilding> remoteForCategory(String category) {
    return remoteByCategory.getOrDefault(normalize(category), List.of());
  }

  private static String normalize(String category) {
    return category == null ? "other" : category.toLowerCase(Locale.ROOT);
  }
}
