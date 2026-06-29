package com.github.simcityexpansion.buildpack.install;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import com.github.simcityexpansion.buildpack.BuildPack;
import com.github.simcityexpansion.buildpack.I18nLog;
import com.github.simcityexpansion.buildpack.LocalizedIOException;
import com.github.simcityexpansion.buildpack.convert.LitematicConverter;
import com.github.simcityexpansion.buildpack.convert.LitematicReader;
import com.github.simcityexpansion.buildpack.convert.NbtStructure;
import com.github.simcityexpansion.buildpack.convert.SchemReader;
import com.github.simcityexpansion.buildpack.convert.StructureNbtReader;
import com.github.simcityexpansion.buildpack.convert.StructureNbtWriter;
import com.github.simcityexpansion.buildpack.convert.StructureUpgrader;
import com.github.simcityexpansion.buildpack.install.BuildingInstaller.InstallResult;
import com.github.simcityexpansion.buildpack.model.BuildingMetadata;
import com.github.simcityexpansion.buildpack.model.FileNames;
import com.github.simcityexpansion.buildpack.model.PackArchive;
import com.github.simcityexpansion.buildpack.model.PackBuildingEntry;
import com.github.simcityexpansion.buildpack.model.StructureFormat;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Full install and uninstall of a zip build pack (install manifest recorded in {@link InstallRegistry}). */
public final class PackInstaller {
  private PackInstaller() {}

  private static final Logger LOGGER = LoggerFactory.getLogger(PackInstaller.class);

  /**
   * Per-zip context shared across every building installed from one pack.
   *
   * @param reclaimable {@code category/name} keys this pack already owns and may overwrite
   * @param packName the pack's display name, appended to a building's name on a cross-pack conflict
   */
  private record PackContext(ZipFile zip, Map<String, String> hashes,
      Set<String> reclaimable, String packName) {}

  /** Installs an entire build pack: converts/copies structures per building, generates or extracts .sk files, and registers the pack. */
  public static InstallResult installPack(PackArchive pack, InstallRegistry registry) {
    List<Component> messages = new ArrayList<>();
    List<String> installedFiles = new ArrayList<>();
    int installed = 0;
    // Resolve the previous install first so this pack can reclaim (overwrite) its own file names on
    // reinstall/update, instead of colliding with them and drifting to _2/_3 each time.
    InstallRegistry.Entry previous = registry.find(pack.manifest().id()).orElse(null);
    Set<String> reclaimable = reclaimableNames(previous);
    try (ZipFile zip = new ZipFile(pack.zipPath().toFile())) {
      PackContext context = new PackContext(
          zip, readIndexHashes(zip), reclaimable, pack.manifest().name());
      for (PackBuildingEntry building : pack.buildings()) {
        try {
          installBuilding(context, building, installedFiles, messages);
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
      return new InstallResult(false, messages, null);
    }

    int removed = removeOrphans(previous, installedFiles);
    registry.add(new InstallRegistry.Entry(
        pack.manifest().id(), pack.manifest().name(), pack.manifest().version(),
        System.currentTimeMillis(), installedFiles));
    registry.save();
    messages.add(0, previous != null
        ? Component.translatable(
            "buildpack.msg.pack_updated", pack.manifest().name(), installed, removed)
        : Component.translatable(
            "buildpack.msg.pack_installed", pack.manifest().name(), installed));
    return new InstallResult(true, messages, null);
  }

  /**
   * Base names (as {@code category/name}) the pack installed previously and may therefore reclaim
   * (overwrite) on this install, so its own file names stay stable across reinstall/update.
   */
  private static Set<String> reclaimableNames(@Nullable InstallRegistry.Entry previous) {
    if (previous == null) {
      return Set.of();
    }
    Set<String> names = new HashSet<>();
    for (String file : previous.files()) {
      int slash = file.lastIndexOf('/');
      if (slash > 0) {
        names.add(file.substring(0, slash + 1) + FileNames.baseName(file.substring(slash + 1)));
      }
    }
    return names;
  }

  /** Removes files present in the old version but absent from the new version during a pack update; returns the number of files deleted. */
  private static int removeOrphans(InstallRegistry.Entry previous, List<String> newFiles) {
    if (previous == null) {
      return 0;
    }
    int removed = 0;
    for (String oldFile : previous.files()) {
      if (!newFiles.contains(oldFile)) {
        try {
          if (Files.deleteIfExists(BuildPack.simukraftDir().resolve(oldFile))) {
            removed++;
          }
        } catch (IOException e) {
          I18nLog.warn(LOGGER, e, "buildpack.log.pack_file_delete_failed", oldFile);
        }
      }
    }
    return removed;
  }

  /**
   * Installs a single building from a pack without updating the registry. A .sk bundled in the
   * pack has the mod's generated marker prepended so that it is recognizable and uninstallable
   * on the "Installed" tab.
   */
  public static InstallResult installSingle(PackArchive pack, PackBuildingEntry building) {
    List<Component> messages = new ArrayList<>();
    try (ZipFile zip = new ZipFile(pack.zipPath().toFile())) {
      PackContext context = new PackContext(
          zip, readIndexHashes(zip), Set.of(), pack.manifest().name());
      String finalName = installBuilding(context, building, null, messages);
      messages.add(0, Component.translatable("buildpack.msg.installed",
          building.category().dirName() + "/" + finalName));
      return new InstallResult(true, messages, null);
    } catch (IOException | RuntimeException e) {
      I18nLog.warn(LOGGER, e, "buildpack.log.pack_single_failed", building.structureEntry());
      return InstallResult.failure(Component.translatable(
          "buildpack.msg.parse_failed", LocalizedIOException.messageOf(e)));
    }
  }

  /** Uninstalls an entire build pack according to the registry manifest. */
  public static boolean uninstallPack(String packId, InstallRegistry registry) {
    InstallRegistry.Entry entry = registry.find(packId).orElse(null);
    if (entry == null) {
      return false;
    }
    boolean ok = true;
    for (String relative : entry.files()) {
      // The registry always stores '/', which Path.resolve accepts on all platforms.
      Path file = BuildPack.simukraftDir().resolve(relative);
      try {
        Files.deleteIfExists(file);
      } catch (IOException e) {
        I18nLog.warn(LOGGER, e, "buildpack.log.pack_file_delete_failed", file);
        ok = false;
      }
    }
    registry.remove(packId);
    registry.save();
    return ok;
  }

  /**
   * Installs one building from a pack and returns the final base name used on disk.
   *
   * @param installedFiles manifest collector for full-pack mode; pass {@code null} for
   *     single-building installs (no registry entry; the copied .sk gets the generated marker)
   */
  private static String installBuilding(PackContext context, PackBuildingEntry building,
      @Nullable List<String> installedFiles, List<Component> messages) throws IOException {
    ZipFile zip = context.zip();
    Map<String, String> hashes = context.hashes();
    Files.createDirectories(building.category().dir());

    String categoryPrefix = building.category().dirName() + "/";
    String baseName = BuildingInstaller.sanitizeFileName(building.name());
    // A building from a different pack may already own this name; keep both by suffixing, but let
    // this pack reclaim the names it installed before so reinstalling does not drift to _2/_3.
    String finalName = BuildingInstaller.resolveConflict(building.category().dir(), baseName,
        candidate -> context.reclaimable().contains(categoryPrefix + candidate));
    boolean conflicted = !finalName.equals(baseName);
    if (conflicted) {
      messages.add(Component.translatable("buildpack.msg.name_conflict", finalName));
    }
    Path nbtTarget = building.category().dir().resolve(finalName + ".nbt");
    Path skTarget = building.category().dir().resolve(finalName + ".sk");

    // Read the structure entry into memory in one pass: vanilla format is written directly to disk;
    // litematic/schem is parsed and converted; old-version structures are upgraded to the current DataVersion via DataFixer.
    byte[] structureBytes = readEntry(zip, building.structureEntry());
    verifyHash(hashes, building.structureEntry(), structureBytes, messages);
    CompoundTag root = NbtIo.readCompressed(new ByteArrayInputStream(structureBytes),
        NbtAccounter.create(BuildPack.MAX_STRUCTURE_NBT_BYTES));
    NbtStructure structure;
    switch (building.format()) {
      case LITEMATIC, SCHEM -> {
        structure = building.format() == StructureFormat.LITEMATIC
            ? LitematicReader.readAndMerge(root)
            : SchemReader.read(root);
        messages.addAll(LitematicConverter.validate(structure));
        StructureUpgrader.warnMissingBlocks(structure, messages);
        CompoundTag tag = StructureUpgrader.upgradeToCurrent(
            StructureNbtWriter.toTag(structure), structure.dataVersion, messages);
        StructureNbtWriter.writeTag(tag, nbtTarget);
      }
      case VANILLA_NBT -> {
        structure = StructureNbtReader.read(root);
        StructureUpgrader.warnMissingBlocks(structure, messages);
        CompoundTag upgraded = StructureUpgrader.upgradeToCurrent(
            root, root.getInt("DataVersion"), messages);
        if (upgraded == root) {
          Files.write(nbtTarget, structureBytes);
        } else {
          StructureNbtWriter.writeTag(upgraded, nbtTarget);
        }
      }
      default -> throw new LocalizedIOException(
          Component.translatable("buildpack.error.unknown_format"));
    }

    // Install SimuKraft native job/trade definitions as-is.
    if (building.simukraftJsonEntry() != null) {
      byte[] jsonBytes = readEntry(zip, building.simukraftJsonEntry());
      verifyHash(hashes, building.simukraftJsonEntry(), jsonBytes, messages);
      Files.write(building.category().dir().resolve(finalName + ".json"), jsonBytes);
    }

    if (building.skEntry() != null) {
      // .sk bundled in the pack: install as-is (treated as author-authored metadata).
      // For single-building installs, prepend the generated marker line (SimuKraft skips # comments)
      // so the "Installed" tab can recognize and uninstall the building.
      byte[] skBytes = readEntry(zip, building.skEntry());
      verifyHash(hashes, building.skEntry(), skBytes, messages);
      if (conflicted) {
        // Same name as another pack's building: disambiguate only the in-game display name (all
        // other author-authored fields are preserved) so the two are distinguishable.
        String original = SkFileReader.parseFields(skBytes).getOrDefault("name", building.name());
        skBytes = renameSkDisplay(skBytes, disambiguate(
            original.isBlank() ? building.name() : original, context.packName()));
      }
      if (installedFiles == null) {
        byte[] marker = ("# " + BuildPack.GENERATED_MARKER + System.lineSeparator())
            .getBytes(StandardCharsets.UTF_8);
        byte[] combined = new byte[marker.length + skBytes.length];
        System.arraycopy(marker, 0, combined, 0, marker.length);
        System.arraycopy(skBytes, 0, combined, marker.length, skBytes.length);
        skBytes = combined;
      }
      Files.write(skTarget, skBytes);
    } else {
      BuildingMetadata meta = building.metaJsonEntry() != null
          ? readJsonMeta(zip, building.metaJsonEntry())
          : new BuildingMetadata();
      if (meta.name.isBlank()) {
        meta.name = building.name();
      }
      if (conflicted) {
        meta.name = disambiguate(meta.name, context.packName());
      }
      meta.category = building.category();
      meta.sizeX = structure.sizeX;
      meta.sizeY = structure.sizeY;
      meta.sizeZ = structure.sizeZ;
      SkFileWriter.write(skTarget, meta);
    }

    if (installedFiles != null) {
      String prefix = building.category().dirName() + "/";
      installedFiles.add(prefix + finalName + ".nbt");
      installedFiles.add(prefix + finalName + ".sk");
      if (building.simukraftJsonEntry() != null) {
        installedFiles.add(prefix + finalName + ".json");
      }
    }
    return finalName;
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
