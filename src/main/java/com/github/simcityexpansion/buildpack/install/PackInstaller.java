package com.github.simcityexpansion.buildpack.install;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
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
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import com.github.simcityexpansion.buildpack.BuildPack;
import com.github.simcityexpansion.buildpack.I18nLog;
import com.github.simcityexpansion.buildpack.LocalizedIOException;
import com.github.simcityexpansion.buildpack.convert.StructureMaterializer;
import com.github.simcityexpansion.buildpack.convert.StructureNbtWriter;
import com.github.simcityexpansion.buildpack.convert.StructureTagOps;
import com.github.simcityexpansion.buildpack.install.BuildingInstaller.InstallResult;
import com.github.simcityexpansion.buildpack.integration.SimukraftBridge;
import com.github.simcityexpansion.buildpack.model.BuildingCategory;
import com.github.simcityexpansion.buildpack.model.BuildingMetadata;
import com.github.simcityexpansion.buildpack.model.FileNames;
import com.github.simcityexpansion.buildpack.model.PackArchive;
import com.github.simcityexpansion.buildpack.model.PackBuildingEntry;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Full install and uninstall of a zip build pack. Since SimuKraft 2.0 reads zip packages directly,
 * installing a pack means <b>normalizing</b> it into a managed package
 * ({@code simukraftbuilding/sce_pack_<id>.zip}): every structure is converted to vanilla
 * {@code .nbt}, a {@code .sk} entry is guaranteed per building, and SimuKraft native job/trade
 * {@code .json} definitions are carried over verbatim. Uninstalling deletes the managed package.
 * The install is recorded in {@link InstallRegistry}.
 */
public final class PackInstaller {
  private PackInstaller() {}

  private static final Logger LOGGER = LoggerFactory.getLogger(PackInstaller.class);

  /** Installs an entire build pack as a managed zip package and registers it. */
  public static InstallResult installPack(PackArchive pack, InstallRegistry registry) {
    List<Component> messages = new ArrayList<>();
    List<String> installedFiles = new ArrayList<>();
    String packId = pack.manifest().id();
    Path targetZip = SimukraftZips.packZip(packId);
    InstallRegistry.Entry previous = registry.find(packId).orElse(null);

    // Base names taken by every other package. This pack's own target zip is excluded (a reinstall
    // may reclaim its previous names instead of drifting to _2/_3); the same applies to entries a
    // migrated legacy install left in the shared local zip.
    Map<BuildingCategory, Set<String>> taken =
        SimukraftZips.baseNamesByCategory(Set.of(targetZip));
    reclaimPreviousLocalNames(previous, taken);

    Map<String, byte[]> entries = new LinkedHashMap<>();
    int installed = 0;
    try (ZipFile zip = new ZipFile(pack.zipPath().toFile())) {
      Map<String, String> hashes = readIndexHashes(zip);
      for (PackBuildingEntry building : pack.buildings()) {
        try {
          installBuilding(zip, hashes, pack.manifest().name(), building, taken,
              entries, installedFiles, messages);
          installed++;
        } catch (IOException | RuntimeException e) {
          I18nLog.warn(LOGGER, e, "buildpack.log.pack_building_failed", building.structureEntry());
          messages.add(Component.translatable("buildpack.msg.parse_failed",
              Component.literal(building.name() + ": ")
                  .append(LocalizedIOException.messageOf(e))));
        }
      }
    } catch (IOException e) {
      I18nLog.warn(LOGGER, e, "buildpack.log.pack_open_failed", pack.zipPath());
      return InstallResult.failure(Component.translatable(
          "buildpack.msg.invalid_pack", LocalizedIOException.messageOf(e)));
    }

    if (installed == 0) {
      messages.add(0, Component.translatable(
          "buildpack.msg.invalid_pack", pack.manifest().name()));
      return new InstallResult(false, messages);
    }

    try {
      // Full-replace write: dropping every existing entry removes files orphaned by the update.
      SimukraftZips.updateZip(targetZip, entries, SimukraftZips.listEntries(targetZip));
      cleanupPrevious(previous, targetZip);
    } catch (IOException e) {
      I18nLog.warn(LOGGER, e, "buildpack.log.pack_open_failed", targetZip);
      return InstallResult.failure(Component.translatable(
          "buildpack.msg.parse_failed", LocalizedIOException.messageOf(e)));
    }

    int removed = countRemoved(previous, installedFiles);
    registry.add(new InstallRegistry.Entry(
        packId, pack.manifest().name(), pack.manifest().version(),
        System.currentTimeMillis(), targetZip.getFileName().toString(), installedFiles));
    registry.save();
    SimukraftBridge.requestCatalogReload();
    messages.add(0, previous != null
        ? Component.translatable(
            "buildpack.msg.pack_updated", pack.manifest().name(), installed, removed)
        : Component.translatable(
            "buildpack.msg.pack_installed", pack.manifest().name(), installed));
    return new InstallResult(true, messages);
  }

  /**
   * Installs a single building from a pack into the managed local zip, without a registry entry
   * (buildings in managed zips are recognizable and uninstallable by the zip itself).
   */
  public static InstallResult installSingle(PackArchive pack, PackBuildingEntry building) {
    List<Component> messages = new ArrayList<>();
    try (ZipFile zip = new ZipFile(pack.zipPath().toFile())) {
      Map<BuildingCategory, Set<String>> taken = SimukraftZips.baseNamesByCategory(Set.of());
      Map<String, byte[]> entries = new LinkedHashMap<>();
      String finalName = installBuilding(zip, readIndexHashes(zip), pack.manifest().name(),
          building, taken, entries, new ArrayList<>(), messages);
      SimukraftZips.updateZip(SimukraftZips.localZip(), entries, List.of());
      SimukraftBridge.requestCatalogReload();
      messages.add(0, Component.translatable("buildpack.msg.installed",
          building.category().dirName() + "/" + finalName));
      return new InstallResult(true, messages);
    } catch (IOException | RuntimeException e) {
      I18nLog.warn(LOGGER, e, "buildpack.log.pack_single_failed", building.structureEntry());
      return InstallResult.failure(Component.translatable(
          "buildpack.msg.parse_failed", LocalizedIOException.messageOf(e)));
    }
  }

  /** Uninstalls an entire build pack according to the registry record. */
  public static boolean uninstallPack(String packId, InstallRegistry registry) {
    InstallRegistry.Entry entry = registry.find(packId).orElse(null);
    if (entry == null) {
      return false;
    }
    boolean ok = true;
    try {
      if (entry.zip().isBlank()) {
        // Record from before the zip layout: delete the loose files it lists (if still present).
        for (String relative : entry.files()) {
          Files.deleteIfExists(BuildPack.simukraftDir().resolve(relative));
        }
      } else if (entry.zip().equalsIgnoreCase(SimukraftZips.LOCAL_ZIP_NAME)) {
        // A migrated legacy install shares the local zip: remove only this pack's entries.
        SimukraftZips.updateZip(SimukraftZips.localZip(), Map.of(), entry.files());
      } else {
        Path zip = BuildPack.simukraftDir().resolve(entry.zip());
        if (SimukraftZips.isManaged(zip)) {
          Files.deleteIfExists(zip);
        }
      }
    } catch (IOException e) {
      I18nLog.warn(LOGGER, e, "buildpack.log.pack_file_delete_failed", entry.zip());
      ok = false;
    }
    registry.remove(packId);
    registry.save();
    SimukraftBridge.requestCatalogReload();
    return ok;
  }

  /**
   * Normalizes one building into zip entries: converted {@code .nbt}, guaranteed {@code .sk}, and
   * the optional native {@code .json}; returns the final base name used inside the package.
   */
  private static String installBuilding(ZipFile zip, Map<String, String> hashes, String packName,
      PackBuildingEntry building, Map<BuildingCategory, Set<String>> taken,
      Map<String, byte[]> entries, List<String> installedFiles, List<Component> messages)
      throws IOException {
    BuildingCategory category = building.category();
    String baseName = BuildingInstaller.sanitizeFileName(building.name());
    String finalName = BuildingInstaller.resolveConflict(taken.get(category), baseName);
    boolean conflicted = !finalName.equals(baseName);
    if (conflicted) {
      messages.add(Component.translatable("buildpack.msg.name_conflict", finalName));
    }

    byte[] structureBytes = readEntry(zip, building.structureEntry());
    verifyHash(hashes, building.structureEntry(), structureBytes, messages);
    StructureMaterializer.Result result =
        StructureMaterializer.toVanilla(structureBytes, building.format(), messages);
    int strippedVoid = StructureTagOps.stripStructureVoid(result.nbt());
    if (strippedVoid > 0) {
      messages.add(Component.translatable("buildpack.msg.void_stripped", strippedVoid));
    }
    String nbtEntry = SimukraftZips.entryPath(category, finalName + ".nbt");
    entries.put(nbtEntry, StructureNbtWriter.toBytes(result.nbt()));
    installedFiles.add(nbtEntry);

    byte[] skBytes;
    if (building.skEntry() != null) {
      // .sk bundled in the pack: carried over as-is (treated as author-authored metadata).
      skBytes = readEntry(zip, building.skEntry());
      verifyHash(hashes, building.skEntry(), skBytes, messages);
      if (conflicted) {
        // Same name as another package's building: disambiguate only the in-game display name (all
        // other author-authored fields are preserved) so the two are distinguishable.
        String original = SkFileReader.parseFields(skBytes).getOrDefault("name", building.name());
        skBytes = renameSkDisplay(skBytes, disambiguate(
            original.isBlank() ? building.name() : original, packName));
      }
    } else {
      BuildingMetadata meta = building.metaJsonEntry() != null
          ? readJsonMeta(zip, building.metaJsonEntry())
          : new BuildingMetadata();
      if (meta.name.isBlank()) {
        meta.name = building.name();
      }
      if (conflicted) {
        meta.name = disambiguate(meta.name, packName);
      }
      meta.category = category;
      meta.sizeX = result.sizeX();
      meta.sizeY = result.sizeY();
      meta.sizeZ = result.sizeZ();
      skBytes = SkFileWriter.toBytes(meta);
    }
    String skEntry = SimukraftZips.entryPath(category, finalName + ".sk");
    entries.put(skEntry, skBytes);
    installedFiles.add(skEntry);

    // SimuKraft native job/trade definitions are carried over as-is.
    if (building.simukraftJsonEntry() != null) {
      byte[] jsonBytes = readEntry(zip, building.simukraftJsonEntry());
      verifyHash(hashes, building.simukraftJsonEntry(), jsonBytes, messages);
      String jsonEntry = SimukraftZips.entryPath(category, finalName + ".json");
      entries.put(jsonEntry, jsonBytes);
      installedFiles.add(jsonEntry);
    }
    return finalName;
  }

  /**
   * When the previous install of this pack lives in the shared local zip (a migrated legacy
   * install), its base names may be reclaimed by this install instead of counting as conflicts.
   */
  private static void reclaimPreviousLocalNames(@Nullable InstallRegistry.Entry previous,
      Map<BuildingCategory, Set<String>> taken) {
    if (previous == null || !previous.zip().equalsIgnoreCase(SimukraftZips.LOCAL_ZIP_NAME)) {
      return;
    }
    for (String file : previous.files()) {
      SimukraftZips.parseEntry(file).ifPresent(entry -> taken.get(entry.category())
          .remove(FileNames.baseName(entry.fileName()).toLowerCase(Locale.ROOT)));
    }
  }

  /** Removes the previous install when it lived somewhere other than the target zip. */
  private static void cleanupPrevious(@Nullable InstallRegistry.Entry previous, Path targetZip)
      throws IOException {
    if (previous == null || previous.zip().isBlank()
        || previous.zip().equalsIgnoreCase(targetZip.getFileName().toString())) {
      return;
    }
    if (previous.zip().equalsIgnoreCase(SimukraftZips.LOCAL_ZIP_NAME)) {
      SimukraftZips.updateZip(SimukraftZips.localZip(), Map.of(), previous.files());
      return;
    }
    Path previousZip = BuildPack.simukraftDir().resolve(previous.zip());
    if (SimukraftZips.isManaged(previousZip)) {
      Files.deleteIfExists(previousZip);
    }
  }

  /** Number of files present in the previous install but absent from the new one (for the update message). */
  private static int countRemoved(@Nullable InstallRegistry.Entry previous, List<String> newFiles) {
    if (previous == null) {
      return 0;
    }
    Set<String> current = Set.copyOf(newFiles);
    int removed = 0;
    for (String oldFile : previous.files()) {
      // Legacy records store "category/name.ext"; normalize to the zip-internal form for comparison.
      String normalized = oldFile.startsWith("buildings/") ? oldFile : "buildings/" + oldFile;
      if (!current.contains(normalized)) {
        removed++;
      }
    }
    return removed;
  }

  /** Appends the pack name so same-named buildings from different packs are distinguishable in-game. */
  private static String disambiguate(String displayName, String packName) {
    return packName.isBlank() ? displayName : displayName + " (" + packName + ")";
  }

  /** Returns the .sk bytes with the {@code name} field set to {@code newName}, preserving every other line. */
  private static byte[] renameSkDisplay(byte[] skBytes, String newName) {
    StringBuilder out = new StringBuilder(skBytes.length + newName.length());
    boolean replaced = false;
    for (String line : new String(skBytes, StandardCharsets.UTF_8).split("\\R", -1)) {
      String trimmed = line.trim();
      int colon = trimmed.indexOf(':');
      if (!replaced && !trimmed.startsWith("#") && colon > 0
          && trimmed.substring(0, colon).trim().equals("name")) {
        out.append("name:").append(newName).append('\n');
        replaced = true;
      } else {
        out.append(line).append('\n');
      }
    }
    if (!replaced) {
      out.append("name:").append(newName).append('\n');
    }
    return out.toString().getBytes(StandardCharsets.UTF_8);
  }

  /** Parses this mod's building metadata JSON from a zip entry. */
  public static BuildingMetadata readJsonMeta(ZipFile zip, String entryName) throws IOException {
    ZipEntry entry = zip.getEntry(entryName);
    return entry == null ? new BuildingMetadata() : readJsonMeta(readEntry(zip, entryName));
  }

  /** Parses this mod's building metadata JSON from raw bytes (shared with UI code that reads zip entries directly). */
  public static BuildingMetadata readJsonMeta(byte[] bytes) {
    BuildingMetadata meta = new BuildingMetadata();
    try {
      JsonObject json = JsonParser
          .parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
      meta.name = getString(json, "name");
      meta.amount = getString(json, "amount");
      meta.author = getString(json, "author");
      meta.description = getString(json, "description");
      meta.jobType = getString(json, "job_type");
      if (json.has("tags") && json.get("tags").isJsonArray()) {
        JsonArray tags = json.getAsJsonArray("tags");
        List<String> values = new ArrayList<>();
        for (JsonElement tag : tags) {
          values.add(tag.getAsString());
        }
        meta.tags = String.join(",", values);
      } else {
        meta.tags = getString(json, "tags");
      }
    } catch (RuntimeException e) {
      I18nLog.warn(LOGGER, e, "buildpack.log.pack_meta_failed");
    }
    return meta;
  }

  private static String getString(JsonObject json, String key) {
    return json.has(key) && json.get(key).isJsonPrimitive() ? json.get(key).getAsString() : "";
  }

  private static byte[] readEntry(ZipFile zip, String entryName) throws IOException {
    ZipEntry entry = zip.getEntry(entryName);
    if (entry == null) {
      throw new LocalizedIOException(
          Component.translatable("buildpack.error.zip_entry_missing", entryName));
    }
    // Reject oversized entries (zip bombs): check the declared size first, then bound the actual read.
    if (entry.getSize() > BuildPack.MAX_PACK_ENTRY_BYTES) {
      throw new LocalizedIOException(
          Component.translatable("buildpack.error.entry_too_large", entryName));
    }
    try (InputStream stream = zip.getInputStream(entry)) {
      byte[] bytes = stream.readNBytes((int) BuildPack.MAX_PACK_ENTRY_BYTES + 1);
      if (bytes.length > BuildPack.MAX_PACK_ENTRY_BYTES) {
        throw new LocalizedIOException(
            Component.translatable("buildpack.error.entry_too_large", entryName));
      }
      return bytes;
    }
  }

  /**
   * Reads the SHA-256 file manifest from {@code index.json} ({@code entry path -> hex hash}); returns
   * an empty map when the pack has no index or it cannot be read.
   */
  private static Map<String, String> readIndexHashes(ZipFile zip) {
    ZipEntry entry = zip.getEntry("index.json");
    if (entry == null || entry.getSize() > BuildPack.MAX_PACK_JSON_BYTES) {
      return Map.of();
    }
    try (InputStreamReader reader = new InputStreamReader(
        zip.getInputStream(entry), StandardCharsets.UTF_8)) {
      JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
      if (!root.has("files") || !root.get("files").isJsonArray()) {
        return Map.of();
      }
      Map<String, String> hashes = new HashMap<>();
      for (JsonElement element : root.getAsJsonArray("files")) {
        JsonObject file = element.getAsJsonObject();
        if (file.has("path") && file.has("sha256")) {
          hashes.put(file.get("path").getAsString(), file.get("sha256").getAsString());
        }
      }
      return hashes;
    } catch (IOException | RuntimeException e) {
      I18nLog.warn(LOGGER, e, "buildpack.log.index_verify_failed");
      return Map.of();
    }
  }

  /** Warns (without aborting) when a packed file's bytes do not match the SHA-256 recorded in {@code index.json}. */
  private static void verifyHash(Map<String, String> hashes, String entryName, byte[] bytes,
      List<Component> messages) {
    String expected = hashes.get(entryName);
    if (expected != null && !expected.equalsIgnoreCase(BuildPack.sha256Hex(bytes))) {
      messages.add(Component.translatable("buildpack.msg.checksum_mismatch", entryName));
    }
  }
}
