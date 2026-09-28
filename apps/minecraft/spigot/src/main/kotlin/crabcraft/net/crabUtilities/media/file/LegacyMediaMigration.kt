package crabcraft.net.crabUtilities.media.file

import crabcraft.net.crabUtilities.CrabUtilities
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import org.bukkit.configuration.file.YamlConfiguration

/** One-way compatibility bridge for previous media installations; runtime code must not depend on these paths. */
object LegacyMediaMigration {
    private const val IMPORT_MARKER = ".legacy-config-imported"
    private val CONFIG_KEYS =
        mapOf(
            "global.debug" to "media.debug",
            "command.create.remote.tabcomplete" to "media.creation.remote.suggestions",
            "command.create.remote.youtube.custom-model" to "media.creation.remote.youtube.custom-model",
            "command.create.remote.youtube.filter" to "media.creation.remote.youtube.filter",
            "command.create.remote.soundcloud.custom-model" to "media.creation.remote.soundcloud.custom-model",
            "command.create.remote.soundcloud.filter" to "media.creation.remote.soundcloud.filter",
            "command.create.remote.http.custom-model" to "media.creation.remote.http.custom-model",
            "command.create.remote.http.filter" to "media.creation.remote.http.filter",
            "disc.volume" to "media.discs.volume",
            "disc.allow-hoppers" to "media.discs.allow-hoppers",
            "disc.range.min" to "media.discs.range.min",
            "disc.range.max" to "media.discs.range.max",
            "disc.range.default" to "media.discs.range.default",
            "horn.volume" to "media.horns.volume",
            "horn.range" to "media.horns.range",
            "horn.max-length-seconds" to "media.horns.max-length-seconds",
            "horn.cooldown-ticks" to "media.horns.cooldown-ticks",
            "horn.cache.enabled" to "media.horns.cache.enabled",
            "horn.cache.size" to "media.horns.cache.size",
            "providers.yt-dlp-path" to "media.providers.yt-dlp-path",
            "providers.ffmpeg-path" to "media.providers.ffmpeg-path",
            "providers.yt-dlp-cookies" to "media.providers.yt-dlp-cookies",
            "providers.yt-dlp-proxy" to "media.providers.yt-dlp-proxy",
        )

    @JvmStatic
    fun run(plugin: CrabUtilities, mediaFolder: File) {
        val legacyFolders =
            listOf(File(plugin.dataFolder, "customdiscs"), File(plugin.dataFolder.parentFile, "CustomDiscs"))
        try {
            Files.createDirectories(mediaFolder.toPath())
            for (legacyFolder in legacyFolders) copyRuntimeData(legacyFolder, mediaFolder)
            importConfig(plugin, mediaFolder, legacyFolders)
        } catch (e: IOException) {
            plugin.getSLF4JLogger().warn("Could not migrate legacy media data: {}", e.message)
        }
    }

    private fun copyRuntimeData(sourceFolder: File, mediaFolder: File) {
        if (!sourceFolder.isDirectory) return
        for (directory in listOf("bin", "horn-cache")) copyMissingFiles(
            sourceFolder.toPath().resolve(directory),
            mediaFolder.toPath().resolve(directory),
        )
    }

    private fun copyMissingFiles(sourceRoot: Path, destinationRoot: Path) {
        if (!Files.isDirectory(sourceRoot) || Files.isSymbolicLink(sourceRoot)) return
        Files.walk(sourceRoot).use { paths ->
            for (source in paths.toList()) {
                if (Files.isSymbolicLink(source)) continue
                val destination = destinationRoot.resolve(sourceRoot.relativize(source))
                if (Files.isDirectory(source)) Files.createDirectories(destination)
                else if (Files.notExists(destination)) {
                    Files.createDirectories(destination.parent)
                    Files.copy(source, destination, StandardCopyOption.COPY_ATTRIBUTES)
                }
            }
        }
    }

    private fun importConfig(plugin: CrabUtilities, mediaFolder: File, legacyFolders: List<File>) {
        val marker = mediaFolder.toPath().resolve(IMPORT_MARKER)
        if (Files.exists(marker)) return
        val legacyConfig = legacyFolders.map { File(it, "config.yml") }.firstOrNull { it.isFile }
        if (legacyConfig != null) {
            val oldConfig = YamlConfiguration.loadConfiguration(legacyConfig)
            val importedValues = LinkedHashMap<String, Any?>()
            for ((key, value) in CONFIG_KEYS) if (oldConfig.contains(key)) importedValues[value] = oldConfig.get(key)
            if (oldConfig.isConfigurationSection("horn.range")) {
                importedValues["media.horns.range"] =
                    oldConfig.getInt("horn.range.default", plugin.config.getInt("media.horns.range", 128))
            }
            plugin.saveModuleConfigValues(importedValues)
            plugin.getSLF4JLogger().info("Imported legacy media settings into Crab Utilities modules/media.yml")
        }
        Files.createFile(marker)
    }
}
