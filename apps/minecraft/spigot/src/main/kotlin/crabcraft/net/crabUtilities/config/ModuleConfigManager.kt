package crabcraft.net.crabUtilities.config

import crabcraft.net.crabUtilities.CrabUtilities
import java.io.IOException
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Locale
import org.bukkit.configuration.Configuration
import org.bukkit.configuration.InvalidConfigurationException
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.configuration.file.YamlConfiguration

class ModuleConfigManager(private val plugin: CrabUtilities) {
    private val dataFolder = plugin.getDataFolder().toPath()
    private val modulesByName = MODULES.associateBy { it.name }
    private val modulesBySection =
        MODULES.flatMap { module ->
                module.sections.map { it to module }
            }
            .toMap()
    private val moduleConfigs = linkedMapOf<ModuleSpec, YamlConfiguration>()

    fun initialise() = reloadAll()

    fun reload(target: String) {
        when (val normalised = target.lowercase(Locale.ROOT)) {
            "all" -> reloadAll()
            CORE -> reloadCore()
            else ->
                reloadModule(
                    modulesByName[normalised] ?: throw IllegalArgumentException("Unknown configuration module: $target")
                )
        }
    }

    fun reloadTargets(): List<String> = java.util.List.copyOf(listOf("all", CORE) + modulesByName.keys)

    fun saveValues(values: Map<String, *>) {
        if (values.isEmpty()) return
        var targetModule: ModuleSpec? = null
        for (path in values.keys) {
            val module =
                moduleForPath(path)
                    ?: throw IllegalArgumentException("Configuration path is not owned by a module: $path")
            if (targetModule != null && targetModule != module) {
                throw IllegalArgumentException("Configuration values must belong to one module")
            }
            targetModule = module
        }
        val module = targetModule!!
        val current = moduleConfigs[module] ?: throw IllegalStateException("Module configuration has not been loaded")
        val staged = copyWithValues(current, values)
        try {
            saveAtomically(staged, modulePath(module))
        } catch (error: IOException) {
            throw ModuleConfigException(configurationErrorMessage(error), error)
        }
        moduleConfigs[module] = staged
        values.forEach { (path, value) -> plugin.getConfig().set(path, value) }
    }

    private fun reloadAll() = withConfigurationErrors {
        ensureModuleFiles()
        val core = prepare(dataFolder.resolve("config.yml"), "config.yml")
        val preparedModules = MODULES.associateWith { prepare(modulePath(it), resourcePath(it)) }
        saveIfChanged(core)
        preparedModules.values.forEach(::saveIfChanged)
        plugin.reloadConfig()
        moduleConfigs.clear()
        for ((module, prepared) in preparedModules) {
            moduleConfigs[module] = prepared.config
            overlayModule(plugin.getConfig(), module, prepared.config)
        }
        warnAboutIgnoredLegacySections(core.config)
    }

    private fun reloadCore() = withConfigurationErrors {
        val core = prepare(dataFolder.resolve("config.yml"), "config.yml")
        saveIfChanged(core)
        plugin.reloadConfig()
        moduleConfigs.forEach { (module, config) -> overlayModule(plugin.getConfig(), module, config) }
        warnAboutIgnoredLegacySections(core.config)
    }

    private fun reloadModule(module: ModuleSpec) = withConfigurationErrors {
        ensureModuleFile(module)
        val prepared = prepare(modulePath(module), resourcePath(module))
        saveIfChanged(prepared)
        moduleConfigs[module] = prepared.config
        overlayModule(plugin.getConfig(), module, prepared.config)
    }

    private inline fun withConfigurationErrors(action: () -> Unit) {
        try {
            action()
        } catch (error: IOException) {
            throw ModuleConfigException(configurationErrorMessage(error), error)
        } catch (error: InvalidConfigurationException) {
            throw ModuleConfigException(configurationErrorMessage(error), error)
        }
    }

    private fun prepare(path: Path, defaultResource: String): PreparedConfiguration {
        val config = load(path)
        return PreparedConfiguration(path, config, mergeDefaults(config, loadResource(defaultResource)))
    }

    private fun ensureModuleFiles() {
        Files.createDirectories(dataFolder.resolve(MODULE_DIRECTORY))
        MODULES.forEach(::ensureModuleFile)
    }

    private fun ensureModuleFile(module: ModuleSpec) {
        if (!Files.exists(modulePath(module))) plugin.saveResource(resourcePath(module), false)
    }

    private fun load(path: Path): YamlConfiguration {
        val config = YamlConfiguration()
        try {
            config.load(path.toFile())
        } catch (error: IOException) {
            throw invalidConfiguration(path, error)
        } catch (error: InvalidConfigurationException) {
            throw invalidConfiguration(path, error)
        }
        return config
    }

    private fun invalidConfiguration(path: Path, error: Exception) =
        ModuleConfigException(
            "Invalid configuration in ${dataFolder.relativize(path)}: ${error.message}",
            error,
        )

    private fun loadResource(resource: String): YamlConfiguration {
        val config = YamlConfiguration()
        plugin.getResource(resource).use { input ->
            if (input == null) throw IOException("Bundled configuration is missing: $resource")
            config.load(InputStreamReader(input, StandardCharsets.UTF_8))
        }
        return config
    }

    private fun saveIfChanged(prepared: PreparedConfiguration) {
        if (prepared.changed) saveAtomically(prepared.config, prepared.path)
    }

    private fun warnAboutIgnoredLegacySections(core: Configuration) {
        val ignored = modulesBySection.keys.filter { core.contains(it, true) }
        if (ignored.isNotEmpty()) {
            plugin
                .getLogger()
                .warning(
                    "Module sections in config.yml are ignored: ${ignored.joinToString(", ")}" +
                        ". Move customised values into the matching modules/*.yml files."
                )
        }
    }

    private fun moduleForPath(path: String) = modulesBySection[path.substringBefore('.')]

    private fun modulePath(module: ModuleSpec): Path = dataFolder.resolve(MODULE_DIRECTORY).resolve(module.fileName)

    private fun resourcePath(module: ModuleSpec) = "$MODULE_DIRECTORY/${module.fileName}"

    private fun configurationErrorMessage(error: Exception) =
        "Could not load CrabUtilities configuration: ${error.message}"

    data class ModuleSpec(val name: String, val fileName: String, val sections: List<String>) {
        fun name() = name

        fun fileName() = fileName

        fun sections() = sections
    }

    private data class PreparedConfiguration(val path: Path, val config: YamlConfiguration, val changed: Boolean)

    companion object {
        @JvmField
        val MODULES: List<ModuleSpec> =
            java.util.List.of(
                ModuleSpec("integrations", "integrations.yml", listOf("mod-protocols", "bluemap")),
                ModuleSpec("chat", "chat.yml", listOf("global-chat", "public-chat")),
                ModuleSpec("voicechat", "voicechat.yml", listOf("voicechat")),
                ModuleSpec("media", "media.yml", listOf("media")),
                ModuleSpec("gameplay", "gameplay.yml", listOf("phantoms", "restricted-area")),
                ModuleSpec("tweaks", "tweaks.yml", listOf("tweaks")),
            )
        private const val CORE = "core"
        private const val MODULE_DIRECTORY = "modules"

        @JvmStatic
        fun copyWithValues(source: YamlConfiguration, values: Map<String, *>): YamlConfiguration {
            val copy = YamlConfiguration()
            try {
                copy.loadFromString(source.saveToString())
            } catch (impossible: InvalidConfigurationException) {
                throw IllegalStateException("Could not copy valid module configuration", impossible)
            }
            values.forEach { (path, value) -> copy.set(path, value) }
            return copy
        }

        @JvmStatic
        fun mergeDefaults(target: FileConfiguration, defaults: Configuration): Boolean {
            var changed = false
            for (key in defaults.getKeys(true)) {
                if (!target.contains(key, true)) {
                    if (defaults.isConfigurationSection(key)) target.createSection(key)
                    else target.set(key, defaults.get(key))
                    changed = true
                }
                if (target.getComments(key).isEmpty() && defaults.getComments(key).isNotEmpty()) {
                    target.setComments(key, defaults.getComments(key))
                    changed = true
                }
                if (target.getInlineComments(key).isEmpty() && defaults.getInlineComments(key).isNotEmpty()) {
                    target.setInlineComments(key, defaults.getInlineComments(key))
                    changed = true
                }
            }
            return changed
        }

        @JvmStatic
        fun overlayModule(target: FileConfiguration, module: ModuleSpec, config: YamlConfiguration) {
            for (section in module.sections) {
                target.set(section, config.get(section))
                if (config.getComments(section).isNotEmpty()) target.setComments(section, config.getComments(section))
                if (config.getInlineComments(section).isNotEmpty()) {
                    target.setInlineComments(section, config.getInlineComments(section))
                }
            }
        }

        @JvmStatic
        fun moduleForPathStatic(path: String): ModuleSpec? = MODULES.firstOrNull {
            path.substringBefore('.') in it.sections
        }

        private fun saveAtomically(config: YamlConfiguration, destination: Path) {
            Files.createDirectories(destination.parent)
            val temporary = Files.createTempFile(destination.parent, ".${destination.fileName}", ".tmp")
            try {
                Files.writeString(temporary, config.saveToString(), StandardCharsets.UTF_8)
                try {
                    Files.move(
                        temporary,
                        destination,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                } catch (ignored: AtomicMoveNotSupportedException) {
                    Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                Files.deleteIfExists(temporary)
            }
        }
    }
}
