package com.github.simcityexpansion.buildpack.integration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.github.simcityexpansion.buildpack.BuildPack;
import com.github.simcityexpansion.buildpack.I18nLog;
import com.github.simcityexpansion.buildpack.LocalizedIOException;
import com.github.simcityexpansion.buildpack.convert.StructureMaterializer;
import com.github.simcityexpansion.buildpack.convert.StructureNbtWriter;
import com.github.simcityexpansion.buildpack.install.PackInstaller;
import com.github.simcityexpansion.buildpack.install.PackReader;
import com.github.simcityexpansion.buildpack.install.SkFileReader;
import com.github.simcityexpansion.buildpack.install.SkFileWriter;
import com.github.simcityexpansion.buildpack.model.BuildingCategory;
import com.github.simcityexpansion.buildpack.model.BuildingMetadata;
import com.github.simcityexpansion.buildpack.model.FileNames;
import com.github.simcityexpansion.buildpack.model.ImportScanner;
import com.github.simcityexpansion.buildpack.model.PackArchive;
import com.github.simcityexpansion.buildpack.model.PackBuildingEntry;
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
 * Activation pipeline for build packs: converts a pack's buildings into this mod's cache
 * ({@code simcity_expansion/cache/<packId>/<category>/}) and registers them with
 * {@link ActivePackProvider} so the SimuKraft mixins serve them <b>without</b> writing anything into
 * {@code simukraftbuilding/}. The set of active packs is persisted to {@code active_packs.json} and
 * re-applied on server start.
 *
 * <p>Conversions are cached keyed by the source zip's size + modified-time, so re-activating or
 * restarting reuses the converted {@code .nbt}/{@code .sk} instead of converting again.
 */
public final class PackActivationService {
  private PackActivationService() {}

  private static final Logger LOGGER = LoggerFactory.getLogger(PackActivationService.class);
  private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
  /** Cache signature marker (source zip size:mtime) used to decide whether a re-conversion is needed. */
  private static final String SIGNATURE_FILE = ".signature";

  /** NeoForge hook: re-apply persisted active packs when the (integrated or dedicated) server starts. */
  public static void onServerStarting(ServerStartingEvent event) {
    reloadActive();
  }

  /** Whether a pack id is currently active. */
  public static boolean isActive(String packId) {
    return ActivePackProvider.isActive(packId);
  }

  /**
   * Activates a pack: (re)converts its buildings into the cache when needed, registers them, and
   * persists the activation. Conversion warnings are appended to {@code messages}.
   */
  public static synchronized void activate(PackArchive pack, List<Component> messages)
      throws IOException {
    String packId = pack.manifest().id();
    Path cacheDir = BuildPack.cacheDir().resolve(FileNames.sanitize(packId, "pack"));
    String signature = signatureOf(pack.zipPath());

    List<ActiveBuilding> buildings = null;
    if (signature.equals(readSignature(cacheDir))) {
      buildings = loadFromCache(cacheDir);
    }
    if (buildings == null || buildings.isEmpty()) {
      buildings = convertToCache(pack, cacheDir, signature, messages);
    }

    ActivePackProvider.activate(packId, buildings);
    addActiveId(packId);
  }

  /** Deactivates a pack (its buildings vanish from SimuKraft); the converted cache is kept for fast re-activation. */
  public static synchronized void deactivate(String packId) {
    ActivePackProvider.deactivate(packId);
    removeActiveId(packId);
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

  private static List<ActiveBuilding> convertToCache(PackArchive pack, Path cacheDir,
      String signature, List<Component> messages) throws IOException {
    deleteRecursively(cacheDir);
    Files.createDirectories(cacheDir);
    String packIdSafe = FileNames.sanitize(pack.manifest().id(), "pack");
    List<ActiveBuilding> buildings = new ArrayList<>();
    for (PackBuildingEntry building : pack.buildings()) {
      try {
        buildings.add(materialize(pack.zipPath(), building, packIdSafe, cacheDir, messages));
      } catch (IOException | RuntimeException e) {
        I18nLog.warn(LOGGER, e, "buildpack.log.activate_building_failed", building.structureEntry());
        messages.add(Component.translatable("buildpack.msg.parse_failed",
            Component.literal(building.name() + ": ").append(LocalizedIOException.messageOf(e))));
      }
    }
    Files.writeString(cacheDir.resolve(SIGNATURE_FILE), signature, StandardCharsets.UTF_8);
    return buildings;
  }

  /** Converts one building into the cache and returns its {@link ActiveBuilding} descriptor. */
  private static ActiveBuilding materialize(Path zipPath, PackBuildingEntry building,
      String packIdSafe, Path cacheDir, List<Component> messages) throws IOException {
    Path categoryDir = cacheDir.resolve(building.category().dirName());
    Files.createDirectories(categoryDir);
    // Name-space by pack id so the merged SimuKraft catalog never has two packs' files collide.
    String key = packIdSafe + "__" + FileNames.sanitize(building.name(), "building");
    Path nbtPath = FileNames.unique(categoryDir, key, ".nbt");
    String finalKey = FileNames.baseName(nbtPath.getFileName().toString());
    Path skPath = categoryDir.resolve(finalKey + ".sk");

    byte[] structureBytes = PackReader.readEntryBytes(zipPath, building.structureEntry());
    StructureMaterializer.Result result =
        StructureMaterializer.toVanilla(structureBytes, building.format(), messages);
    StructureNbtWriter.writeTag(result.nbt(), nbtPath);

    String size = result.sizeX() + " x " + result.sizeY() + " x " + result.sizeZ();
    String displayName;
    String amount;
    String author;
    if (building.skEntry() != null) {
      // Preserve the author's .sk verbatim (it may carry POI definitions SimuKraft reads).
      byte[] skBytes = PackReader.readEntryBytes(zipPath, building.skEntry());
      Map<String, String> fields = SkFileReader.parseFields(skBytes);
      displayName = orDefault(fields.get("name"), building.name());
      amount = orDefault(fields.get("amount"), "");
      author = orDefault(fields.get("author"), "");
      Files.write(skPath, skBytes);
    } else {
      BuildingMetadata meta = building.metaJsonEntry() != null
          ? PackInstaller.readJsonMeta(PackReader.readEntryBytes(zipPath, building.metaJsonEntry()))
          : new BuildingMetadata();
      if (meta.name.isBlank()) {
        meta.name = building.name();
      }
      meta.category = building.category();
      meta.sizeX = result.sizeX();
      meta.sizeY = result.sizeY();
      meta.sizeZ = result.sizeZ();
      SkFileWriter.write(skPath, meta);
      displayName = meta.name;
      amount = meta.amount;
      author = meta.author;
    }

    if (building.simukraftJsonEntry() != null) {
      Files.write(categoryDir.resolve(finalKey + ".json"),
          PackReader.readEntryBytes(zipPath, building.simukraftJsonEntry()));
    }

    return new ActiveBuilding(building.category().dirName(), displayName, size, amount, author,
        finalKey + ".sk", finalKey + ".nbt", skPath, nbtPath);
  }

  /** Rebuilds the active-building list from an up-to-date cache; returns {@code null} if it is inconsistent. */
  private static List<ActiveBuilding> loadFromCache(Path cacheDir) {
    List<ActiveBuilding> buildings = new ArrayList<>();
    for (BuildingCategory category : BuildingCategory.values()) {
      Path categoryDir = cacheDir.resolve(category.dirName());
      if (!Files.isDirectory(categoryDir)) {
        continue;
      }
      List<Path> metaFiles;
      try (var stream = Files.list(categoryDir)) {
        metaFiles = stream
            .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".sk"))
            .toList();
      } catch (IOException e) {
        I18nLog.warn(LOGGER, e, "buildpack.log.activate_failed", categoryDir);
        return null;
      }
      for (Path skPath : metaFiles) {
        String base = FileNames.baseName(skPath.getFileName().toString());
        Path nbtPath = categoryDir.resolve(base + ".nbt");
        if (!Files.isRegularFile(nbtPath)) {
          return null;
        }
        try {
          Map<String, String> fields = SkFileReader.parseFields(Files.readAllBytes(skPath));
          buildings.add(new ActiveBuilding(category.dirName(),
              orDefault(fields.get("name"), base), orDefault(fields.get("size"), "-"),
              orDefault(fields.get("amount"), ""), orDefault(fields.get("author"), ""),
              base + ".sk", base + ".nbt", skPath, nbtPath));
        } catch (IOException e) {
          I18nLog.warn(LOGGER, e, "buildpack.log.sk_read_failed", skPath);
          return null;
        }
      }
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

  private static String signatureOf(Path zip) throws IOException {
    return Files.size(zip) + ":" + Files.getLastModifiedTime(zip).toMillis();
  }

  private static String readSignature(Path cacheDir) {
    Path signature = cacheDir.resolve(SIGNATURE_FILE);
    try {
      return Files.isRegularFile(signature)
          ? Files.readString(signature, StandardCharsets.UTF_8).trim() : "";
    } catch (IOException e) {
      return "";
    }
  }

  private static void deleteRecursively(Path dir) throws IOException {
    if (!Files.exists(dir)) {
      return;
    }
    try (var walk = Files.walk(dir)) {
      walk.sorted(Comparator.reverseOrder()).forEach(path -> {
        try {
          Files.deleteIfExists(path);
        } catch (IOException e) {
          I18nLog.warn(LOGGER, e, "buildpack.log.delete_failed", path);
        }
      });
    }
  }

  private static String orDefault(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }
}
