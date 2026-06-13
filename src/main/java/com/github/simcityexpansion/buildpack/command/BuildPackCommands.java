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
 * {@code /buildpack} 服务端管理命令（OP 等级 2），为专用服务器提供原生安装能力：
 * 扫描<b>服务端</b>游戏目录的 {@code simcity_expansion/import/}，
 * 复用与客户端界面完全相同的转换/安装链路写入 {@code simukraftbuilding/}。
 *
 * <pre>
 * /buildpack list                              列出导入目录中的结构与 zip 包
 * /buildpack install &lt;文件&gt; [分类] [名称]      安装散文件（默认分类 other）
 * /buildpack installpack &lt;zip&gt;                 安装 zip 拓展包
 * /buildpack packs                             列出已安装拓展包
 * /buildpack uninstallpack &lt;包id&gt;              按注册表卸载拓展包
 * </pre>
 *
 * <p>消息使用翻译组件：发给装有本模组的玩家时按其语言显示；
 * 服务端控制台按内置英文（en_us）解析。
 */
public final class BuildPackCommands {
  private BuildPackCommands() {}

  private static final Logger LOGGER = LoggerFactory.getLogger(BuildPackCommands.class);

  /** 列表类输出的最大行数。 */
  private static final int MAX_LINES = 30;

  /** 从世界捕获结构的体积上限（格）。 */
  private static final long MAX_CAPTURE_VOLUME = 2_000_000L;

  /** 挂到 NeoForge 总线（逻辑服务端创建命令树时触发，单人与专服皆有效）。 */
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

  /** {@code /buildpack capture <from> <to> [name] [format]} 子命令节点。 */
  private static LiteralArgumentBuilder<CommandSourceStack> captureNode() {
    return Commands.literal("capture")
        .then(Commands.argument("from", BlockPosArgument.blockPos())
            .then(Commands.argument("to", BlockPosArgument.blockPos())
                .executes(context -> capture(context.getSource(),
                    BlockPosArgument.getLoadedBlockPos(context, "from"),
                    BlockPosArgument.getLoadedBlockPos(context, "to"), null, "both"))
                .then(Commands.argument("name", StringArgumentType.word())
                    .executes(context -> capture(context.getSource(),
                        BlockPosArgument.getLoadedBlockPos(context, "from"),
                        BlockPosArgument.getLoadedBlockPos(context, "to"),
                        StringArgumentType.getString(context, "name"), "both"))
                    .then(Commands.argument("format", StringArgumentType.word())
                        .suggests(BuildPackCommands::suggestFormats)
                        .executes(context -> capture(context.getSource(),
                            BlockPosArgument.getLoadedBlockPos(context, "from"),
                            BlockPosArgument.getLoadedBlockPos(context, "to"),
                            StringArgumentType.getString(context, "name"),
                            StringArgumentType.getString(context, "format")))))));
  }

  // ---- 子命令实现 ----

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

  /** 捕获 [from,to] 区域为结构并按 format（nbt/litematic/both）导出到导入目录。 */
  private static int capture(CommandSourceStack source, BlockPos a, BlockPos b,
      @Nullable String name, String format) {
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
    try {
      NbtStructure structure = WorldCapture.capture(source.getLevel(), min, max);
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

  // ---- 工具 ----

  /** 解析导入目录下的相对路径，并防止 {@code ../} 越界。 */
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

  // ---- 补全 ----

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

  /** 含空格的建议项加引号，匹配 {@link StringArgumentType#string()} 的解析规则。 */
  private static String quoteIfNeeded(String text) {
    return text.contains(" ") ? "\"" + text + "\"" : text;
  }
}
