package crabcraft.net.crabUtilities.config

import crabcraft.net.crabUtilities.CrabUtilities
import org.bukkit.configuration.Configuration
import org.bukkit.configuration.InvalidConfigurationException
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.configuration.file.YamlConfiguration
import java.io.IOException
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Locale

class ModuleConfigManager(private val plugin: CrabUtilities) {

    private val dataFolder = plugin.getDataFolder().toPath()
    private val modulesByName = linkedMapOf<String, ModuleSpec>()
    private val modulesBySection = linkedMapOf<String, ModuleSpec>()
    private val moduleConfigs = linkedMapOf<ModuleSpec, YamlConfiguration>()

    init {
        for (module in MODULES) {
            modulesByName[module.name()] = module
            for (section in module.sections()) {
                modulesBySection[section] = module
            }
        }
    }

    fun initialise() {
        reloadAll()
    }

    fun reload(target: String) {
        val normalised = target.lowercase(Locale.ROOT)
        if (normalised == "all") {
            reloadAll()
            return
        }
        if (normalised == CORE) {
            reloadCore()
            return
        }

        val module = modulesByName[normalised]
            ?: throw IllegalArgumentException("Unknown configuration module: $target")
        reloadModule(module)
    }

    fun reloadTargets(): List<String> {
        val targets = mutableListOf("all", CORE)
        targets.addAll(modulesByName.keys)
        return java.util.List.copyOf(targets)
    }

    fun saveValues(values: Map<String, *>) {
        if (values.isEmpty()) {
            return
        }

        var targetModule: ModuleSpec? = null
        for ((path, _) in values) {
            val module = moduleForPath(path)
                ?: throw IllegalArgumentException("Configuration path is not owned by a module: $path")
            if (targetModule != null && targetModule != module) {
                throw IllegalArgumentException("Configuration values must belong to one module")
            }
            targetModule = module
        }

        val target = targetModule!!
        val current = moduleConfigs[target]
            ?: throw IllegalStateException("Module configuration has not been loaded")
        val staged = copyWithValues(current, values)

        try {
            saveAtomically(staged, modulePath(target))
        } catch (e: IOException) {
            throw ModuleConfigException(configurationErrorMessage(e), e)
        }

        moduleConfigs[target] = staged
        values.forEach { (path, value) -> plugin.getConfig().set(path, value) }
    }

    private fun reloadAll() {
        try {
            ensureModuleFiles()

            val core = prepare(dataFolder.resolve("config.yml"), "config.yml")
            val preparedModules = linkedMapOf<ModuleSpec, PreparedConfiguration>()
            for (module in MODULES) {
                preparedModules[module] = prepare(modulePath(module), resourcePath(module))
            }

            saveIfChanged(core)
            for (prepared in preparedModules.values) {
                saveIfChanged(prepared)
            }

            plugin.reloadConfig()
            moduleConfigs.clear()
            for ((module, prepared) in preparedModules) {
                val config = prepared.config()
                moduleConfigs[module] = config
                overlayModule(plugin.getConfig(), module, config)
            }
            warnAboutIgnoredLegacySections(core.config())
        } catch (e: IOException) {
            throw ModuleConfigException(configurationErrorMessage(e), e)
        } catch (e: InvalidConfigurationException) {
            throw ModuleConfigException(configurationErrorMessage(e), e)
        }
    }

    private fun reloadCore() {
        try {
            val core = prepare(dataFolder.resolve("config.yml"), "config.yml")
            saveIfChanged(core)
            plugin.reloadConfig()
            moduleConfigs.forEach { (module, config) -> overlayModule(plugin.getConfig(), module, config) }
            warnAboutIgnoredLegacySections(core.config())
        } catch (e: IOException) {
            throw ModuleConfigException(configurationErrorMessage(e), e)
        } catch (e: InvalidConfigurationException) {
            throw ModuleConfigException(configurationErrorMessage(e), e)
        }
    }

    private fun reloadModule(module: ModuleSpec) {
        try {
            ensureModuleFile(module)
            val prepared = prepare(modulePath(module), resourcePath(module))
            saveIfChanged(prepared)
            moduleConfigs[module] = prepared.config()
            overlayModule(plugin.getConfig(), module, prepared.config())
        } catch (e: IOException) {
            throw ModuleConfigException(configurationErrorMessage(e), e)
        } catch (e: InvalidConfigurationException) {
            throw ModuleConfigException(configurationErrorMessage(e), e)
        }
    }

    private fun prepare(path: Path, defaultResource: String): PreparedConfiguration {
        val config = load(path)
        val defaults = loadResource(defaultResource)
        return PreparedConfiguration(path, config, mergeDefaults(config, defaults))
    }

    private fun ensureModuleFiles() {
        Files.createDirectories(dataFolder.resolve(MODULE_DIRECTORY))
        for (module in MODULES) {
            ensureModuleFile(module)
        }
    }

    private fun ensureModuleFile(module: ModuleSpec) {
        val path = modulePath(module)
        if (!Files.exists(path)) {
            plugin.saveResource(resourcePath(module), false)
        }
    }

    private fun load(path: Path): YamlConfiguration {
        val config = YamlConfiguration()
        try {
            config.load(path.toFile())
        } catch (e: IOException) {
            throw ModuleConfigException("Invalid configuration in ${dataFolder.relativize(path)}: ${e.message}", e)
        } catch (e: InvalidConfigurationException) {
            throw ModuleConfigException("Invalid configuration in ${dataFolder.relativize(path)}: ${e.message}", e)
        }
        return config
    }

    private fun loadResource(resource: String): YamlConfiguration {
        val config = YamlConfiguration()
        plugin.getResource(resource).use { input ->
            if (input == null) {
                throw IOException("Bundled configuration is missing: $resource")
            }
            config.load(InputStreamReader(input, StandardCharsets.UTF_8))
        }
        return config
    }

    private fun saveIfChanged(prepared: PreparedConfiguration) {
        if (prepared.changed()) {
            saveAtomically(prepared.config(), prepared.path())
        }
    }

    private fun warnAboutIgnoredLegacySections(core: Configuration) {
        val ignored = modulesBySection.keys.filter { section -> core.contains(section, true) }
        if (ignored.isNotEmpty()) {
            plugin.getLogger().warning(
                "Module sections in config.yml are ignored: " + ignored.joinToString(", ") +
                    ". Move customised values into the matching modules/*.yml files."
            )
        }
    }

    private fun moduleForPath(path: String): ModuleSpec? {
        val separator = path.indexOf('.')
        val section = if (separator < 0) path else path.substring(0, separator)
        return modulesBySection[section]
    }

    private fun modulePath(module: ModuleSpec): Path =
        dataFolder.resolve(MODULE_DIRECTORY).resolve(module.fileName())

    private fun resourcePath(module: ModuleSpec): String = "$MODULE_DIRECTORY/${module.fileName()}"

    private fun configurationErrorMessage(error: Exception): String =
        "Could not load CrabUtilities configuration: ${error.message}"

    data class ModuleSpec(
        private val name: String,
        private val fileName: String,
        private val sections: List<String>
    ) {
        fun name(): String = name
        fun fileName(): String = fileName
        fun sections(): List<String> = sections
    }

    private data class PreparedConfiguration(
        private val path: Path,
        private val config: YamlConfiguration,
        private val changed: Boolean
    ) {
        fun path(): Path = path
        fun config(): YamlConfiguration = config
        fun changed(): Boolean = changed
    }

    companion object {
        @JvmField
        val MODULES: List<ModuleSpec> = listOf(
            ModuleSpec("integrations", "integrations.yml", listOf("mod-protocols", "bluemap")),
            ModuleSpec("chat", "chat.yml", listOf("global-chat", "public-chat")),
            ModuleSpec("voicechat", "voicechat.yml", listOf("voicechat")),
            ModuleSpec("media", "media.yml", listOf("media")),
            ModuleSpec("gameplay", "gameplay.yml", listOf("phantoms", "restricted-area")),
            ModuleSpec("tweaks", "tweaks.yml", listOf("tweaks"))
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
                    if (defaults.isConfigurationSection(key)) {
                        target.createSection(key)
                    } else {
                        target.set(key, defaults.get(key))
                    }
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
            for (section in module.sections()) {
                target.set(section, config.get(section))
                copyComments(config, target, section)
            }
        }

        @JvmStatic
        fun moduleForPathStatic(path: String): ModuleSpec? {
            val separator = path.indexOf('.')
            val section = if (separator < 0) path else path.substring(0, separator)
            return MODULES.firstOrNull { module -> module.sections().contains(section) }
        }

        private fun copyComments(source: Configuration, target: FileConfiguration, path: String) {
            if (source.getComments(path).isNotEmpty()) {
                target.setComments(path, source.getComments(path))
            }
            if (source.getInlineComments(path).isNotEmpty()) {
                target.setInlineComments(path, source.getInlineComments(path))
            }
        }

        private fun saveAtomically(config: YamlConfiguration, destination: Path) {
            Files.createDirectories(destination.parent)
            val temporary = Files.createTempFile(destination.parent, ".${destination.fileName}", ".tmp")
            try {
                Files.writeString(temporary, config.saveToString(), StandardCharsets.UTF_8)
                try {
                    Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } catch (ignored: AtomicMoveNotSupportedException) {
                    Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                Files.deleteIfExists(temporary)
            }
        }
    }
}
