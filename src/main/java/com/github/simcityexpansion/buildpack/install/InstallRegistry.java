package com.github.simcityexpansion.buildpack.install;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.github.simcityexpansion.buildpack.BuildPack;
import com.github.simcityexpansion.buildpack.I18nLog;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Local registry of installed zip build packs ({@code simcity_expansion/installed_packs.json}).
 * Records the manifest of files written by each pack, used for one-click uninstall and
 * tracing ownership on the "Installed" tab.
 */
public final class InstallRegistry {

  private static final Logger LOGGER = LoggerFactory.getLogger(InstallRegistry.class);
  private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

  /**
   * A single install record.
   *
   * @param files file paths relative to {@code simukraftbuilding/} (e.g., {@code residential/x.nbt})
   */
  public record Entry(String id, String name, String version, long installedAt,
      List<String> files) {}

  private final List<Entry> entries = new ArrayList<>();

  private InstallRegistry() {}

  /** Loads the registry from disk; returns an empty registry if the file is missing or corrupt. */
  public static InstallRegistry load() {
    InstallRegistry registry = new InstallRegistry();
    Path file = BuildPack.registryFile();
    if (!Files.isRegularFile(file)) {
      return registry;
    }
    try {
      JsonObject root = JsonParser
          .parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
      for (JsonElement element : root.getAsJsonArray("packs")) {
        JsonObject pack = element.getAsJsonObject();
        List<String> files = new ArrayList<>();
        for (JsonElement path : pack.getAsJsonArray("files")) {
          files.add(path.getAsString());
        }
        registry.entries.add(new Entry(
            pack.get("id").getAsString(),
            pack.has("name") ? pack.get("name").getAsString() : "",
            pack.has("version") ? pack.get("version").getAsString() : "",
            pack.has("installedAt") ? pack.get("installedAt").getAsLong() : 0L,
            files));
      }
    } catch (IOException | RuntimeException e) {
      I18nLog.warn(LOGGER, e, "buildpack.log.registry_read_failed", file);
    }
    return registry;
  }

  /** Persists the registry to disk. */
  public void save() {
    JsonArray packs = new JsonArray();
    for (Entry entry : entries) {
      JsonObject pack = new JsonObject();
      pack.addProperty("id", entry.id());
      pack.addProperty("name", entry.name());
      pack.addProperty("version", entry.version());
      pack.addProperty("installedAt", entry.installedAt());
      JsonArray files = new JsonArray();
      entry.files().forEach(files::add);
      pack.add("files", files);
      packs.add(pack);
    }
    JsonObject root = new JsonObject();
    root.add("packs", packs);

    Path file = BuildPack.registryFile();
    try {
      if (file.getParent() != null) {
        Files.createDirectories(file.getParent());
      }
      Files.writeString(file, GSON.toJson(root), StandardCharsets.UTF_8);
    } catch (IOException e) {
      I18nLog.warn(LOGGER, e, "buildpack.log.registry_write_failed", file);
    }
  }

  /** Returns all install records (read-only view). */
  public List<Entry> entries() {
    return List.copyOf(entries);
  }

  /** Looks up a record by pack id. */
  public Optional<Entry> find(String packId) {
    return entries.stream().filter(entry -> entry.id().equals(packId)).findFirst();
  }

  /** Adds a record, removing any existing record with the same id first. */
  public void add(Entry entry) {
    entries.removeIf(existing -> existing.id().equals(entry.id()));
    entries.add(entry);
  }

  /** Removes a record. */
  public void remove(String packId) {
    entries.removeIf(entry -> entry.id().equals(packId));
  }

  /** Returns the pack id that owns the given relative file path (path separator normalized to {@code /}). */
  public Optional<String> packOwning(String relativeFile) {
    String normalized = relativeFile.replace('\\', '/');
    for (Entry entry : entries) {
      if (entry.files().contains(normalized)) {
        return Optional.of(entry.id());
      }
    }
    return Optional.empty();
  }

  /** Removes a file from a record's manifest; removes the entire record if the manifest becomes empty. */
  public void removeFile(String packId, String relativeFile) {
    String normalized = relativeFile.replace('\\', '/');
    find(packId).ifPresent(entry -> {
      List<String> files = new ArrayList<>(entry.files());
      files.remove(normalized);
      entries.removeIf(existing -> existing.id().equals(packId));
      if (!files.isEmpty()) {
        entries.add(new Entry(
            entry.id(), entry.name(), entry.version(), entry.installedAt(), files));
      }
    });
  }

  /** Replaces one file path in a record with another (used when recategorizing a building). */
  public void replaceFile(String packId, String oldRelative, String newRelative) {
    String oldNorm = oldRelative.replace('\\', '/');
    String newNorm = newRelative.replace('\\', '/');
    find(packId).ifPresent(entry -> {
      List<String> files = new ArrayList<>(entry.files());
      int index = files.indexOf(oldNorm);
      if (index >= 0) {
        files.set(index, newNorm);
        entries.removeIf(existing -> existing.id().equals(packId));
        entries.add(new Entry(
            entry.id(), entry.name(), entry.version(), entry.installedAt(), files));
      }
    });
  }
}
