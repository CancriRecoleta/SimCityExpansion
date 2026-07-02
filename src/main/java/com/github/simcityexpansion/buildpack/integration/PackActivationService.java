package com.github.simcityexpansion.buildpack.integration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.github.simcityexpansion.buildpack.BuildPack;
import com.github.simcityexpansion.buildpack.I18nLog;
import com.github.simcityexpansion.buildpack.LocalizedIOException;
import com.github.simcityexpansion.buildpack.convert.StructureMaterializer;
import com.github.simcityexpansion.buildpack.convert.StructureNbtWriter;
import com.github.simcityexpansion.buildpack.install.BuildingInstaller;
import com.github.simcityexpansion.buildpack.install.InstallRegistry;
import com.github.simcityexpansion.buildpack.install.LegacyMigration;
import com.github.simcityexpansion.buildpack.install.PackInstaller;
import com.github.simcityexpansion.buildpack.install.PackReader;
import com.github.simcityexpansion.buildpack.install.SimukraftZips;
import com.github.simcityexpansion.buildpack.install.SkFileReader;
import com.github.simcityexpansion.buildpack.install.SkFileWriter;
import com.github.simcityexpansion.buildpack.model.BuildingCategory;
import com.github.simcityexpansion.buildpack.model.BuildingMetadata;
import com.github.simcityexpansion.buildpack.model.FileNames;
import com.github.simcityexpansion.buildpack.model.ImportScanner;
import com.github.simcityexpansion.buildpack.model.PackArchive;
import com.github.simcityexpansion.buildpack.model.PackBuildingEntry;
import com.github.simcityexpansion.buildpack.network.BuildPackNetwork;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Activation pipeline for build packs: converts a pack into a cache <b>zip package</b> in SimuKraft
 * 2.0's own layout ({@code simcity_expansion/cache/<packId>.zip} with
 * {@code buildings/<category>/} entries) and registers it with {@link ActivePackProvider}, whose
 * cache zips a mixin splices into SimuKraft's package scan — so activation serves buildings through
 * SimuKraft's own zip pipeline <b>without</b> writing anything into {@code simukraftbuilding/}.
 * The set of active packs is persisted to {@code active_packs.json} and re-applied on server start.
 *
 * <p>Conversions are cached keyed by the source zip's size + modified-time, so re-activating or
 * restarting reuses the converted package instead of converting again.
 */
public final class PackActivationService {
  private PackActivationService() {}

  private static final Logger LOGGER = LoggerFactory.getLogger(PackActivationService.class);
  private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
  /** Cache signature sidecar suffix (source zip size:mtime), used to decide whether a re-conversion is needed. */
  private static final String SIGNATURE_SUFFIX = ".signature";

  /**
   * NeoForge hook: migrate any pre-2.0 loose building files, then re-apply persisted active packs
   * when the (integrated or dedicated) server starts.
   */
  public static void onServerStarting(ServerStartingEvent event) {
    List<Component> migrationMessages = new ArrayList<>();
    LegacyMigration.migrateIfNeeded(InstallRegistry.load(), migrationMessages);
    if (!migrationMessages.isEmpty()) {
      // The catalog may already have been scanned (e.g. from the title-screen build menu).
      SimukraftBridge.requestCatalogReload();
    }
    reloadActive();
  }

  /** Whether a pack id is currently active. */
  public static boolean isActive(String packId) {
    return ActivePackProvider.isActive(packId);
  }

  /**
   * Activates a pack: (re)converts it into a cache zip package when needed, registers it, and
   * persists the activation. Conversion warnings are appended to {@code messages}.
   */
  public static synchronized void activate(PackArchive pack, List<Component> messages)
      throws IOException {
    String packId = pack.manifest().id();
    String packIdSafe = FileNames.sanitize(packId, "pack").toLowerCase(Locale.ROOT);
    Path cacheZip = BuildPack.cacheDir().resolve(packIdSafe + ".zip");
    String signature = signatureOf(pack.zipPath());

    List<ActiveBuilding> buildings = null;
    if (signature.equals(readSignature(cacheZip)) && Files.isRegularFile(cacheZip)) {
      buildings = loadFromCache(packId, cacheZip);
    }
    if (buildings == null || buildings.isEmpty()) {
      buildings = convertToCache(pack, cacheZip, signature, messages);
    }

    ActivePackProvider.activate(packId, cacheZip, buildings);
    addActiveId(packId);
    SimukraftBridge.requestCatalogReload();
    BuildPackNetwork.broadcast();
  }

  /** Deactivates a pack (its buildings vanish from SimuKraft); the converted cache is kept for fast re-activation. */
  public static synchronized void deactivate(String packId) {
    ActivePackProvider.deactivate(packId);
    removeActiveId(packId);
    SimukraftBridge.requestCatalogReload();
    BuildPackNetwork.broadcast();
  }

  /** Re-applies every persisted active pack by locating its zip in the import directory. */
  public static synchronized void reloadActive() {
    List<String> ids = readActiveIds();
    if (ids.isEmpty()) {
      return;
    }
    Map<String, PackArchive> byId = new HashMap<>();
    for (Path zip : ImportScanner.scanZips()) {
      try {
        PackArchive pack = PackReader.read(zip);
        byId.putIfAbsent(pack.manifest().id(), pack);
      } catch (IOException | RuntimeException e) {
        I18nLog.warn(LOGGER, e, "buildpack.log.pack_invalid", zip);
      }
    }
    int activated = 0;
    for (String id : ids) {
      PackArchive pack = byId.get(id);
      if (pack == null) {
        I18nLog.warn(LOGGER, "buildpack.log.activate_pack_missing", id);
        continue;
      }
      try {
        activate(pack, new ArrayList<>());
        activated++;
      } catch (IOException | RuntimeException e) {
        I18nLog.warn(LOGGER, e, "buildpack.log.activate_failed", id);
      }
    }
    LOGGER.info("SimCity Expansion: activated {} build pack(s) from {}.", activated, ids.size());
  }

  // ---- Conversion / cache ----

  /** Converts the pack into the cache zip package and returns the active-building descriptors. */
  private static List<ActiveBuilding> convertToCache(PackArchive pack, Path cacheZip,
      String signature, List<Component> messages) throws IOException {
    Files.deleteIfExists(cacheZip);
    String packId = pack.manifest().id();

    // Base names already taken across the installed packages and other active cache packages:
    // SimuKraft merges every scanned package into one catalog keyed by base name, and on a clash
    // the installed package wins (cache packages are scanned first), which would hide the building.
    Map<BuildingCategory, Set<String>> taken = SimukraftZips.baseNamesByCategory(Set.of());
    for (Path otherCache : ActivePackProvider.activeCacheZips()) {
      try {
        for (String entry : SimukraftZips.listEntries(otherCache)) {
          SimukraftZips.parseEntry(entry).ifPresent(building -> taken.get(building.category())
              .add(FileNames.baseName(building.fileName()).toLowerCase(Locale.ROOT)));
        }
      } catch (IOException e) {
        I18nLog.warn(LOGGER, e, "buildpack.log.pack_open_failed", otherCache);
      }
    }

    Map<String, byte[]> entries = new LinkedHashMap<>();
    List<ActiveBuilding> buildings = new ArrayList<>();
    for (PackBuildingEntry building : pack.buildings()) {
      try {
        buildings.add(materialize(pack.zipPath(), packId, building, taken, entries, messages));
      } catch (IOException | RuntimeException e) {
        I18nLog.warn(LOGGER, e, "buildpack.log.activate_building_failed", building.structureEntry());
        messages.add(Component.translatable("buildpack.msg.parse_failed",
            Component.literal(building.name() + ": ").append(LocalizedIOException.messageOf(e))));
      }
    }
    if (!entries.isEmpty()) {
      SimukraftZips.updateZip(cacheZip, entries, SimukraftZips.listEntries(cacheZip));
    }
    Files.createDirectories(cacheZip.getParent());
    Files.writeString(signatureFile(cacheZip), signature, StandardCharsets.UTF_8);
    return buildings;
  }

  /** Converts one building into cache zip entries and returns its {@link ActiveBuilding} descriptor. */
  private static ActiveBuilding materialize(Path zipPath, String packId,
      PackBuildingEntry building, Map<BuildingCategory, Set<String>> taken,
      Map<String, byte[]> entries, List<Component> messages) throws IOException {
    BuildingCategory category = building.category();
    String baseName = FileNames.sanitize(building.name(), "building");
    String finalName = BuildingInstaller.resolveConflict(taken.get(category), baseName);
    boolean renamed = !finalName.equals(baseName);

    byte[] structureBytes = PackReader.readEntryBytes(zipPath, building.structureEntry());
    StructureMaterializer.Result result =
        StructureMaterializer.toVanilla(structureBytes, building.format(), messages);
    entries.put(SimukraftZips.entryPath(category, finalName + ".nbt"),
        StructureNbtWriter.toBytes(result.nbt()));

    String size = result.sizeX() + " x " + result.sizeY() + " x " + result.sizeZ();
    String displayName;
    String amount;
    String author;
    String description;
    byte[] skBytes;
    if (building.skEntry() != null) {
      // Preserve the author's .sk (it may carry POI or job definitions SimuKraft reads). SimuKraft
      // parses its file-reference fields itself now, so a renamed building must have them rewritten.
      skBytes = PackReader.readEntryBytes(zipPath, building.skEntry());
      Map<String, String> fields = SkFileReader.parseFields(skBytes);
      displayName = orDefault(fields.get("name"), building.name());
      amount = orDefault(fields.get("amount"), "");
      author = orDefault(fields.get("author"), "");
      description = orDefault(fields.get("description"), orDefault(fields.get("desc"), ""));
      if (renamed) {
        skBytes = rewriteSkFileReferences(skBytes, finalName);
      }
    } else {
      BuildingMetadata meta = building.metaJsonEntry() != null
          ? PackInstaller.readJsonMeta(PackReader.readEntryBytes(zipPath, building.metaJsonEntry()))
          : new BuildingMetadata();
      if (meta.name.isBlank()) {
        meta.name = building.name();
      }
      meta.category = category;
      meta.sizeX = result.sizeX();
      meta.sizeY = result.sizeY();
      meta.sizeZ = result.sizeZ();
      skBytes = SkFileWriter.toBytes(meta);
      displayName = meta.name;
      amount = meta.amount;
      author = meta.author;
      description = meta.description;
    }
    entries.put(SimukraftZips.entryPath(category, finalName + ".sk"), skBytes);

    if (building.simukraftJsonEntry() != null) {
      entries.put(SimukraftZips.entryPath(category, finalName + ".json"),
          PackReader.readEntryBytes(zipPath, building.simukraftJsonEntry()));
    }

    return new ActiveBuilding(packId, category.dirName(), displayName, size, amount, author,
        description, finalName + ".sk", finalName + ".nbt");
  }

  /**
   * Rewrites the .sk fields that reference sibling files by name ({@code structure}/{@code file}
   * point at the structure, {@code commercial}/{@code industrial} at the job definition JSON) so
   * they follow the building's renamed entry base name.
   */
  private static byte[] rewriteSkFileReferences(byte[] skBytes, String finalName) {
    StringBuilder out = new StringBuilder(skBytes.length + 64);
    for (String line : new String(skBytes, StandardCharsets.UTF_8).split("\\R", -1)) {
      String trimmed = line.trim();
      int colon = trimmed.indexOf(':');
      if (!trimmed.startsWith("#") && colon > 0) {
        String key = trimmed.substring(0, colon).trim().toLowerCase(Locale.ROOT);
        String value = trimmed.substring(colon + 1).trim();
        if (!value.isEmpty() && (key.equals("structure") || key.equals("file"))) {
          out.append(key).append(':').append(finalName)
              .append(FileNames.extension(value).isEmpty() ? ".nbt" : FileNames.extension(value))
              .append('\n');
          continue;
        }
        if (!value.isEmpty() && (key.equals("commercial") || key.equals("industrial"))) {
          out.append(key).append(':').append(finalName).append(".json").append('\n');
          continue;
        }
      }
      out.append(line).append('\n');
    }
    return out.toString().getBytes(StandardCharsets.UTF_8);
  }

  /** Rebuilds the active-building list from an up-to-date cache zip; returns {@code null} if it is inconsistent. */
  private static List<ActiveBuilding> loadFromCache(String packId, Path cacheZip) {
    List<ActiveBuilding> buildings = new ArrayList<>();
    try {
      List<String> entryNames = SimukraftZips.listEntries(cacheZip);
      for (String entryName : entryNames) {
        SimukraftZips.BuildingEntry parsed = SimukraftZips.parseEntry(entryName).orElse(null);
        if (parsed == null || !parsed.fileName().toLowerCase(Locale.ROOT).endsWith(".sk")) {
          continue;
        }
        BuildingCategory category = parsed.category();
        String base = FileNames.baseName(parsed.fileName());
        if (!entryNames.contains(SimukraftZips.entryPath(category, base + ".nbt"))) {
          return null;
        }
        byte[] skBytes = SimukraftZips.readEntry(cacheZip, entryName).orElse(null);
        if (skBytes == null) {
          return null;
        }
        Map<String, String> fields = SkFileReader.parseFields(skBytes);
        buildings.add(new ActiveBuilding(packId, category.dirName(),
            orDefault(fields.get("name"), base), orDefault(fields.get("size"), "-"),
            orDefault(fields.get("amount"), ""), orDefault(fields.get("author"), ""),
            orDefault(fields.get("description"), ""), base + ".sk", base + ".nbt"));
      }
    } catch (IOException e) {
      I18nLog.warn(LOGGER, e, "buildpack.log.activate_failed", cacheZip);
      return null;
    }
    return buildings;
  }

  // ---- Persistence ----

  private static synchronized List<String> readActiveIds() {
    Path file = BuildPack.activePacksFile();
    if (!Files.isRegularFile(file)) {
      return new ArrayList<>();
    }
    try {
      JsonObject root = JsonParser.parseString(
          Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
      List<String> ids = new ArrayList<>();
      if (root.has("active") && root.get("active").isJsonArray()) {
        for (JsonElement element : root.getAsJsonArray("active")) {
          ids.add(element.getAsString());
        }
      }
      return ids;
    } catch (IOException | RuntimeException e) {
      I18nLog.warn(LOGGER, e, "buildpack.log.active_read_failed", file);
      return new ArrayList<>();
    }
  }

  private static synchronized void writeActiveIds(List<String> ids) {
    JsonObject root = new JsonObject();
    JsonArray array = new JsonArray();
    ids.forEach(array::add);
    root.add("active", array);
    try {
      BuildPack.writeAtomically(
          BuildPack.activePacksFile(), GSON.toJson(root).getBytes(StandardCharsets.UTF_8));
    } catch (IOException e) {
      I18nLog.warn(LOGGER, e, "buildpack.log.active_write_failed", BuildPack.activePacksFile());
    }
  }

  private static void addActiveId(String packId) {
    List<String> ids = readActiveIds();
    if (!ids.contains(packId)) {
      ids.add(packId);
      writeActiveIds(ids);
    }
  }

  private static void removeActiveId(String packId) {
    List<String> ids = readActiveIds();
    if (ids.remove(packId)) {
      writeActiveIds(ids);
    }
  }

  // ---- Utilities ----

  private static Path signatureFile(Path cacheZip) {
    return cacheZip.resolveSibling(cacheZip.getFileName() + SIGNATURE_SUFFIX);
  }

  private static String signatureOf(Path zip) throws IOException {
    return Files.size(zip) + ":" + Files.getLastModifiedTime(zip).toMillis();
  }

  private static String readSignature(Path cacheZip) {
    Path signature = signatureFile(cacheZip);
    try {
      return Files.isRegularFile(signature)
          ? Files.readString(signature, StandardCharsets.UTF_8).trim() : "";
    } catch (IOException e) {
      return "";
    }
  }

  private static String orDefault(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }
}
