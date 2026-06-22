package com.github.simcityexpansion.buildpack.command;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.github.simcityexpansion.buildpack.I18nLog;
import com.github.simcityexpansion.buildpack.LocalizedIOException;
import com.github.simcityexpansion.buildpack.convert.LitematicWriter;
import com.github.simcityexpansion.buildpack.convert.NbtStructure;
import com.github.simcityexpansion.buildpack.convert.ParsedStructure;
import com.github.simcityexpansion.buildpack.convert.StructureNbtWriter;
import com.github.simcityexpansion.buildpack.convert.WorldCapture;
import com.github.simcityexpansion.buildpack.install.BuildingInstaller;
import com.github.simcityexpansion.buildpack.install.InstallRegistry;
import com.github.simcityexpansion.buildpack.install.PackInstaller;
import com.github.simcityexpansion.buildpack.install.PackReader;
import com.github.simcityexpansion.buildpack.model.BuildingCategory;
import com.github.simcityexpansion.buildpack.model.BuildingMetadata;
import com.github.simcityexpansion.buildpack.model.ImportFile;
import com.github.simcityexpansion.buildpack.model.ImportScanner;
import com.github.simcityexpansion.buildpack.model.PackArchive;
import com.github.simcityexpansion.buildpack.model.StructureFormat;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code /buildpack} server-side admin command (OP level 2), providing native install capability
 * for dedicated servers: scans {@code simcity_expansion/import/} in the <b>server</b> game
 * directory and reuses the same conversion/install pipeline as the client GUI to write into
 * {@code simukraftbuilding/}.
 *
 * <pre>
 * /buildpack list                              List structures and zip packs in the import directory
 * /buildpack install &lt;file&gt; [category] [name]  Install a loose file (default category: other)
 * /buildpack installpack &lt;zip&gt;                 Install a zip build pack
 * /buildpack packs                             List installed build packs
 * /buildpack uninstallpack &lt;packId&gt;            Uninstall a build pack by registry ID
 * </pre>
 *
 * <p>Messages use translation components: they are rendered in the player's language when sent to
 * a player who has the mod installed, and resolved using the built-in English (en_us) on the
 * server console.
 */
public final class BuildPackCommands {
  private BuildPackCommands() {}

  private static final Logger LOGGER = LoggerFactory.getLogger(BuildPackCommands.class);

  /** Maximum number of lines for list-style output. */
  private static final int MAX_LINES = 30;

  /** Maximum volume (in blocks) for capturing a structure from the world. */
  private static final long MAX_CAPTURE_VOLUME = WorldCapture.MAX_CAPTURE_VOLUME;

  /** Subscribed to the NeoForge bus; fired when the logical server builds the command tree (works in both singleplayer and dedicated server). */
  public static void onRegisterCommands(RegisterCommandsEvent event) {
    register(event.getDispatcher());
  }

  static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
    dispatcher.register(Commands.literal("buildpack")
        .requires(source -> source.hasPermission(Commands.LEVEL_GAMEMASTERS))
        .then(Commands.literal("list")
            .executes(context -> list(context.getSource())))
        .then(Commands.literal("packs")
            .executes(context -> packs(context.getSource())))
        .then(Commands.literal("install")
            .then(Commands.argument("file", StringArgumentType.string())
                .suggests(BuildPackCommands::suggestStructures)
                .executes(context -> install(context.getSource(),
                    StringArgumentType.getString(context, "file"), null, null))
                .then(Commands.argument("category", StringArgumentType.word())
                    .suggests(BuildPackCommands::suggestCategories)
                    .executes(context -> install(context.getSource(),
                        StringArgumentType.getString(context, "file"),
                        StringArgumentType.getString(context, "category"), null))
                    .then(Commands.argument("name", StringArgumentType.greedyString())
                        .executes(context -> install(context.getSource(),
                            StringArgumentType.getString(context, "file"),
                            StringArgumentType.getString(context, "category"),
                            StringArgumentType.getString(context, "name")))))))
        .then(Commands.literal("installpack")
            .then(Commands.argument("zip", StringArgumentType.string())
                .suggests(BuildPackCommands::suggestZips)
                .executes(context -> installPack(context.getSource(),
                    StringArgumentType.getString(context, "zip")))))
        .then(Commands.literal("uninstallpack")
            .then(Commands.argument("id", StringArgumentType.string())
                .suggests(BuildPackCommands::suggestPackIds)
                .executes(context -> uninstallPack(context.getSource(),
                    StringArgumentType.getString(context, "id")))))
        .then(captureNode()));
  }

  /** {@code /buildpack capture <from> <to> [name] [format]} subcommand node. */
  private static LiteralArgumentBuilder<CommandSourceStack> captureNode() {
    return Commands.literal("capture")
        .then(Commands.argument("from", BlockPosArgument.blockPos())
            .then(Commands.argument("to", BlockPosArgument.blockPos())
                .executes(context -> capture(context.getSource(),
                    BlockPosArgument.getLoadedBlockPos(context, "from"),
                    BlockPosArgument.getLoadedBlockPos(context, "to"), null, "both", true))
                .then(Commands.argument("name", StringArgumentType.word())
                    .executes(context -> capture(context.getSource(),
                        BlockPosArgument.getLoadedBlockPos(context, "from"),
                        BlockPosArgument.getLoadedBlockPos(context, "to"),
                        StringArgumentType.getString(context, "name"), "both", true))
                    .then(Commands.argument("format", StringArgumentType.word())
                        .suggests(BuildPackCommands::suggestFormats)
                        .executes(context -> capture(context.getSource(),
                            BlockPosArgument.getLoadedBlockPos(context, "from"),
                            BlockPosArgument.getLoadedBlockPos(context, "to"),
                            StringArgumentType.getString(context, "name"),
                            StringArgumentType.getString(context, "format"), true))
                        .then(Commands.argument("contents", BoolArgumentType.bool())
                            .executes(context -> capture(context.getSource(),
                                BlockPosArgument.getLoadedBlockPos(context, "from"),
                                BlockPosArgument.getLoadedBlockPos(context, "to"),
                                StringArgumentType.getString(context, "name"),
                                StringArgumentType.getString(context, "format"),
                                BoolArgumentType.getBool(context, "contents"))))))));
  }

  // ---- Subcommand implementations ----

  private static int list(CommandSourceStack source) {
    List<String> entries = new ArrayList<>();
    Path importDir = ImportScanner.ensureImportDir();
    for (ImportFile file : ImportScanner.scan()) {
      entries.add(relativize(importDir, file.path()));
    }
    for (Path zip : ImportScanner.scanZips()) {
      entries.add(relativize(importDir, zip));
    }
    if (entries.isEmpty()) {
      source.sendSuccess(() -> Component.translatable(
          "buildpack.cmd.list.empty", importDir.toString()), false);
      return 0;
    }
    source.sendSuccess(() -> Component.translatable(
        "buildpack.cmd.list.header", entries.size()), false);
    sendLines(source, entries);
    return entries.size();
  }

  private static int packs(CommandSourceStack source) {
    List<InstallRegistry.Entry> entries = InstallRegistry.load().entries();
    if (entries.isEmpty()) {
      source.sendSuccess(() -> Component.translatable("buildpack.cmd.packs.empty"), false);
      return 0;
    }
    source.sendSuccess(() -> Component.translatable(
        "buildpack.cmd.packs.header", entries.size()), false);
    for (InstallRegistry.Entry entry : entries) {
      source.sendSuccess(() -> Component.translatable("buildpack.cmd.packs.entry",
          entry.id(), entry.name(), entry.files().size()), false);
    }
    return entries.size();
  }

  private static int install(CommandSourceStack source, String relative,
      @Nullable String categoryName, @Nullable String name) {
    BuildingCategory category = BuildingCategory.OTHER;
    if (categoryName != null) {
      var resolved = BuildingCategory.byDirName(categoryName);
      if (resolved.isEmpty()) {
        source.sendFailure(Component.translatable("buildpack.cmd.bad_category", categoryName));
        return 0;
      }
      category = resolved.get();
    }

    Path file = resolveImportFile(relative);
    if (file == null) {
      source.sendFailure(Component.translatable("buildpack.cmd.file_not_found", relative));
      return 0;
    }
    StructureFormat format = StructureFormat.byFileName(file.getFileName().toString())
        .orElse(null);
    if (format == null) {
      source.sendFailure(Component.translatable("buildpack.error.unknown_format"));
      return 0;
    }

    try {
      ImportFile importFile = new ImportFile(file, format,
          Files.size(file), Files.getLastModifiedTime(file).toInstant());
      BuildingMetadata meta = new BuildingMetadata();
      meta.prefill(ParsedStructure.parse(file, format).info(), importFile.baseName());
      if (name != null && !name.isBlank()) {
        meta.name = name.trim();
      }
      if (meta.author.isBlank()) {
        meta.author = source.getTextName();
      }
      meta.category = category;

      BuildingInstaller.InstallResult result = BuildingInstaller.install(importFile, meta, false);
      sendResult(source, result.ok(), result.messages());
      return result.ok() ? 1 : 0;
    } catch (IOException | RuntimeException e) {
      I18nLog.warn(LOGGER, e, "buildpack.log.cmd_install_failed", file);
      source.sendFailure(Component.translatable(
          "buildpack.msg.parse_failed", LocalizedIOException.messageOf(e)));
      return 0;
    }
  }

  private static int installPack(CommandSourceStack source, String relative) {
    Path zip = resolveImportFile(relative);
    if (zip == null) {
      source.sendFailure(Component.translatable("buildpack.cmd.file_not_found", relative));
      return 0;
    }
    try {
      PackArchive pack = PackReader.read(zip);
      InstallRegistry registry = InstallRegistry.load();
      BuildingInstaller.InstallResult result = PackInstaller.installPack(pack, registry);
      sendResult(source, result.ok(), result.messages());
      return result.ok() ? 1 : 0;
    } catch (IOException | RuntimeException e) {
      I18nLog.warn(LOGGER, e, "buildpack.log.cmd_pack_failed", zip);
      source.sendFailure(Component.translatable(
          "buildpack.msg.invalid_pack", LocalizedIOException.messageOf(e)));
      return 0;
    }
  }

  private static int uninstallPack(CommandSourceStack source, String packId) {
    InstallRegistry registry = InstallRegistry.load();
    if (registry.find(packId).isEmpty()) {
      source.sendFailure(Component.translatable("buildpack.cmd.pack_not_found", packId));
      return 0;
    }
    PackInstaller.uninstallPack(packId, registry);
    source.sendSuccess(
        () -> Component.translatable("buildpack.msg.uninstalled", packId), true);
    return 1;
  }

  /** Captures the [from, to] region as a structure and exports it to the import directory in the given format (nbt/litematic/both). */
  private static int capture(CommandSourceStack source, BlockPos a, BlockPos b,
      @Nullable String name, String format, boolean includeBlockEntities) {
    BlockPos min = new BlockPos(Math.min(a.getX(), b.getX()),
        Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()));
    BlockPos max = new BlockPos(Math.max(a.getX(), b.getX()),
        Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()));
    int sizeX = max.getX() - min.getX() + 1;
    int sizeY = max.getY() - min.getY() + 1;
    int sizeZ = max.getZ() - min.getZ() + 1;
    long volume = (long) sizeX * sizeY * sizeZ;
    if (volume > MAX_CAPTURE_VOLUME) {
      source.sendFailure(Component.translatable(
          "buildpack.cmd.capture.too_big", volume, MAX_CAPTURE_VOLUME));
      return 0;
    }
    if (!WorldCapture.regionLoaded(source.getLevel(), min, max)) {
      source.sendFailure(Component.translatable("buildpack.cmd.capture.unloaded"));
      return 0;
    }
    try {
      NbtStructure structure = WorldCapture.capture(source.getLevel(), min, max, includeBlockEntities);
      String base = sanitizeName(name != null && !name.isBlank() ? name
          : "capture_" + min.getX() + "_" + min.getY() + "_" + min.getZ());
      Path dir = ImportScanner.ensureImportDir();
      List<String> written = new ArrayList<>();
      if (!"litematic".equals(format)) {
        Path target = uniqueTarget(dir, base + ".nbt");
        StructureNbtWriter.write(structure, target);
        written.add(target.getFileName().toString());
      }
      if (!"nbt".equals(format)) {
        Path target = uniqueTarget(dir, base + ".litematic");
        LitematicWriter.write(structure, base, source.getTextName(), target);
        written.add(target.getFileName().toString());
      }
      String size = sizeX + " x " + sizeY + " x " + sizeZ;
      String files = String.join(", ", written);
      source.sendSuccess(() -> Component.translatable("buildpack.cmd.capture.done", size, files), true);
      return 1;
    } catch (IOException | RuntimeException e) {
      I18nLog.warn(LOGGER, e, "buildpack.log.export_failed");
      source.sendFailure(Component.translatable(
          "buildpack.msg.parse_failed", LocalizedIOException.messageOf(e)));
      return 0;
    }
  }

  // ---- Utilities ----

  /** Resolves a relative path under the import directory and guards against {@code ../} traversal. */
  @Nullable
  private static Path resolveImportFile(String relative) {
    Path importDir = ImportScanner.ensureImportDir().toAbsolutePath().normalize();
    Path resolved = importDir.resolve(relative.replace('\\', '/')).normalize();
    if (!resolved.startsWith(importDir) || !Files.isRegularFile(resolved)) {
      return null;
    }
    return resolved;
  }

  private static String relativize(Path importDir, Path file) {
    return importDir.relativize(file).toString().replace('\\', '/');
  }

  private static String sanitizeName(String name) {
    String cleaned = name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
    return cleaned.isBlank() ? "capture" : cleaned;
  }

  private static Path uniqueTarget(Path dir, String fileName) {
    Path target = dir.resolve(fileName);
    int dot = fileName.lastIndexOf('.');
    String base = dot > 0 ? fileName.substring(0, dot) : fileName;
    String extension = dot > 0 ? fileName.substring(dot) : "";
    int suffix = 2;
    while (Files.exists(target)) {
      target = dir.resolve(base + "_" + suffix++ + extension);
    }
    return target;
  }

  private static void sendLines(CommandSourceStack source, List<String> lines) {
    int shown = Math.min(lines.size(), MAX_LINES);
    for (int i = 0; i < shown; i++) {
      String line = lines.get(i);
      source.sendSuccess(() -> Component.literal("  " + line), false);
    }
    if (lines.size() > shown) {
      source.sendSuccess(() -> Component.translatable(
          "buildpack.cmd.list.more", lines.size() - shown), false);
    }
  }

  private static void sendResult(CommandSourceStack source, boolean ok, List<Component> messages) {
    MutableComponent joined = Component.empty();
    for (int i = 0; i < messages.size(); i++) {
      if (i > 0) {
        joined.append(Component.literal(" · "));
      }
      joined.append(messages.get(i));
    }
    if (ok) {
      source.sendSuccess(() -> joined, true);
    } else {
      source.sendFailure(joined);
    }
  }

  // ---- Suggestions ----

  private static CompletableFuture<Suggestions> suggestStructures(
      CommandContext<CommandSourceStack> context,
      SuggestionsBuilder builder) {
    Path importDir = ImportScanner.ensureImportDir();
    List<String> names = ImportScanner.scan().stream()
        .map(file -> quoteIfNeeded(relativize(importDir, file.path())))
        .toList();
    return SharedSuggestionProvider.suggest(names, builder);
  }

  private static CompletableFuture<Suggestions> suggestZips(
      CommandContext<CommandSourceStack> context,
      SuggestionsBuilder builder) {
    Path importDir = ImportScanner.ensureImportDir();
    List<String> names = ImportScanner.scanZips().stream()
        .map(zip -> quoteIfNeeded(relativize(importDir, zip)))
        .toList();
    return SharedSuggestionProvider.suggest(names, builder);
  }

  private static CompletableFuture<Suggestions> suggestCategories(
      CommandContext<CommandSourceStack> context,
      SuggestionsBuilder builder) {
    List<String> names = Arrays.stream(BuildingCategory.values())
        .map(BuildingCategory::dirName)
        .toList();
    return SharedSuggestionProvider.suggest(names, builder);
  }

  private static CompletableFuture<Suggestions> suggestPackIds(
      CommandContext<CommandSourceStack> context,
      SuggestionsBuilder builder) {
    List<String> ids = InstallRegistry.load().entries().stream()
        .map(entry -> quoteIfNeeded(entry.id()))
        .toList();
    return SharedSuggestionProvider.suggest(ids, builder);
  }

  private static CompletableFuture<Suggestions> suggestFormats(
      CommandContext<CommandSourceStack> context,
      SuggestionsBuilder builder) {
    return SharedSuggestionProvider.suggest(List.of("nbt", "litematic", "both"), builder);
  }

  /** Wraps suggestion entries that contain spaces in quotes, matching the parsing rules of {@link StringArgumentType#string()}. */
  private static String quoteIfNeeded(String text) {
    return text.contains(" ") ? "\"" + text + "\"" : text;
  }
}
