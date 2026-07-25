package crabcraft.net.crabUtilities.media.file;

import crabcraft.net.crabUtilities.CrabUtilities;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;

/**
 * One-way compatibility bridge for installations that used the previous media
 * component. New runtime code must not depend on these paths.
 */
public final class LegacyMediaMigration {
  private static final String IMPORT_MARKER = ".legacy-config-imported";
  private static final Map<String, String> CONFIG_KEYS = Map.ofEntries(
    Map.entry("global.debug", "media.debug"),
    Map.entry("command.create.remote.tabcomplete", "media.creation.remote.suggestions"),
    Map.entry("command.create.remote.youtube.custom-model", "media.creation.remote.youtube.custom-model"),
    Map.entry("command.create.remote.youtube.filter", "media.creation.remote.youtube.filter"),
    Map.entry("command.create.remote.soundcloud.custom-model", "media.creation.remote.soundcloud.custom-model"),
    Map.entry("command.create.remote.soundcloud.filter", "media.creation.remote.soundcloud.filter"),
    Map.entry("command.create.remote.http.custom-model", "media.creation.remote.http.custom-model"),
    Map.entry("command.create.remote.http.filter", "media.creation.remote.http.filter"),
    Map.entry("disc.volume", "media.discs.volume"),
    Map.entry("disc.allow-hoppers", "media.discs.allow-hoppers"),
    Map.entry("disc.range.min", "media.discs.range.min"),
    Map.entry("disc.range.max", "media.discs.range.max"),
    Map.entry("disc.range.default", "media.discs.range.default"),
    Map.entry("horn.volume", "media.horns.volume"),
    Map.entry("horn.range", "media.horns.range"),
    Map.entry("horn.max-length-seconds", "media.horns.max-length-seconds"),
    Map.entry("horn.cooldown-ticks", "media.horns.cooldown-ticks"),
    Map.entry("horn.cache.enabled", "media.horns.cache.enabled"),
    Map.entry("horn.cache.size", "media.horns.cache.size"),
    Map.entry("providers.yt-dlp-path", "media.providers.yt-dlp-path"),
    Map.entry("providers.ffmpeg-path", "media.providers.ffmpeg-path"),
    Map.entry("providers.yt-dlp-cookies", "media.providers.yt-dlp-cookies"),
    Map.entry("providers.yt-dlp-proxy", "media.providers.yt-dlp-proxy")
  );

  private LegacyMediaMigration() {}

  public static void run(CrabUtilities plugin, File mediaFolder) {
    List<File> legacyFolders = List.of(
      new File(plugin.getDataFolder(), "customdiscs"),
      new File(plugin.getDataFolder().getParentFile(), "CustomDiscs")
    );

    try {
      Files.createDirectories(mediaFolder.toPath());
      for (File legacyFolder : legacyFolders) {
        copyRuntimeData(legacyFolder, mediaFolder);
      }
      importConfig(plugin, mediaFolder, legacyFolders);
    } catch (IOException e) {
      plugin.getSLF4JLogger().warn("Could not migrate legacy media data: {}", e.getMessage());
    }
  }

  private static void copyRuntimeData(File sourceFolder, File mediaFolder) throws IOException {
    if (!sourceFolder.isDirectory()) return;

    for (String directory : List.of("bin", "horn-cache")) {
      copyMissingFiles(sourceFolder.toPath().resolve(directory), mediaFolder.toPath().resolve(directory));
    }
  }

  private static void copyMissingFiles(Path sourceRoot, Path destinationRoot) throws IOException {
    if (!Files.isDirectory(sourceRoot) || Files.isSymbolicLink(sourceRoot)) return;
    try (var paths = Files.walk(sourceRoot)) {
      for (Path source : paths.toList()) {
        if (Files.isSymbolicLink(source)) continue;
        Path relative = sourceRoot.relativize(source);
        Path destination = destinationRoot.resolve(relative);
        if (Files.isDirectory(source)) {
          Files.createDirectories(destination);
        } else if (Files.notExists(destination)) {
          Files.createDirectories(destination.getParent());
          Files.copy(source, destination, StandardCopyOption.COPY_ATTRIBUTES);
        }
      }
    }
  }

  private static void importConfig(
    CrabUtilities plugin,
    File mediaFolder,
    List<File> legacyFolders
  ) throws IOException {
    Path marker = mediaFolder.toPath().resolve(IMPORT_MARKER);
    if (Files.exists(marker)) return;

    File legacyConfig = legacyFolders.stream()
      .map(folder -> new File(folder, "config.yml"))
      .filter(File::isFile)
      .findFirst()
      .orElse(null);

    if (legacyConfig != null) {
      YamlConfiguration oldConfig = YamlConfiguration.loadConfiguration(legacyConfig);
      Map<String, Object> importedValues = new java.util.LinkedHashMap<>();
      for (Map.Entry<String, String> entry : CONFIG_KEYS.entrySet()) {
        if (oldConfig.contains(entry.getKey())) {
          importedValues.put(entry.getValue(), oldConfig.get(entry.getKey()));
        }
      }

      if (oldConfig.isConfigurationSection("horn.range")) {
        importedValues.put(
          "media.horns.range",
          oldConfig.getInt("horn.range.default", plugin.getConfig().getInt("media.horns.range", 128)));
      }

      plugin.saveModuleConfigValues(importedValues);
      plugin.getSLF4JLogger().info(
        "Imported legacy media settings into Crab Utilities modules/media.yml");
    }

    Files.createFile(marker);
  }
}
