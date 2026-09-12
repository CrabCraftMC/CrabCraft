package crabcraft.net.crabUtilities.config

import org.bukkit.configuration.file.YamlConfiguration
import java.nio.charset.StandardCharsets

object ModuleConfigManagerRegressionTest {

    @JvmStatic
    fun main(args: Array<String>) {
        verifyBundledLayoutAndMergedView()
        verifyDefaultMergePreservesCustomValues()
        verifyValueStagingDoesNotMutateCachedConfiguration()
        verifyPathOwnership()
    }

    private fun verifyValueStagingDoesNotMutateCachedConfiguration() {
        val cached = YamlConfiguration()
        cached.set("media.discs.volume", 1.0)

        val staged = ModuleConfigManager.copyWithValues(cached, mapOf("media.discs.volume" to 0.35))

        check(cached.getDouble("media.discs.volume") == 1.0,
            "staging a module save mutated the cached configuration")
        check(staged.getDouble("media.discs.volume") == 0.35,
            "staged module values were not applied to the copy")
    }

    private fun verifyBundledLayoutAndMergedView() {
        val core = load("config.yml")
        val merged = load("config.yml")

        for (module in ModuleConfigManager.MODULES) {
            val config = load("modules/" + module.fileName())
            for (section in module.sections()) {
                check(!core.contains(section, true), "$section still appears in the core config")
                check(config.isConfigurationSection(section), module.fileName() + " does not own " + section)
            }
            ModuleConfigManager.overlayModule(merged, module, config)
        }

        check(merged.getString("redis.host", "") == "localhost",
            "core configuration disappeared from the merged view")
        check(merged.getBoolean("mod-protocols.jade.enabled"),
            "integration configuration disappeared from the merged view")
        check(!merged.getBoolean("mod-protocols.accurate-block-placement.enabled"),
            "accurate block placement is not opt-in in the merged view")
        check(merged.getString("media.providers.yt-dlp-path", "") == "auto",
            "media configuration disappeared from the merged view")
        check(!merged.getBoolean("tweaks.view-distance.enabled"),
            "view-distance tweak is not opt-in in the merged view")
    }

    private fun verifyDefaultMergePreservesCustomValues() {
        val custom = YamlConfiguration()
        custom.set("media.discs.volume", 0.35)

        val changed = ModuleConfigManager.mergeDefaults(custom, load("modules/media.yml"))

        check(changed, "missing media defaults were not added")
        check(custom.getDouble("media.discs.volume") == 0.35,
            "custom media value was overwritten by a default")
        check(custom.getString("media.providers.yt-dlp-path", "") == "auto",
            "missing media provider default was not added")
    }

    private fun verifyPathOwnership() {
        check(moduleName("media.providers.ffmpeg-path") == "media",
            "media path was routed to the wrong module")
        check(moduleName("mod-protocols.jade.enabled") == "integrations",
            "Jade path was routed to the wrong module")
        check(moduleName("bluemap.sign-markers.enabled") == "integrations",
            "BlueMap path was routed to the wrong module")
        check(moduleName("public-chat.enabled") == "chat",
            "public chat path was routed to the wrong module")
        check(moduleName("restricted-area.enabled") == "gameplay",
            "restricted area path was routed to the wrong module")
        check(moduleName("tweaks.view-distance.enabled") == "tweaks",
            "tweak path was routed to the wrong module")
        check(ModuleConfigManager.moduleForPathStatic("redis.host") == null,
            "core Redis path was routed to a module")

        val names = ModuleConfigManager.MODULES.map { it.name() }
        check(names == listOf("integrations", "chat", "voicechat", "media", "gameplay", "tweaks"),
            "reload module names changed unexpectedly")
    }

    private fun moduleName(path: String): String {
        val module = ModuleConfigManager.moduleForPathStatic(path)
        check(module != null, "module path is not owned: $path")
        return module!!.name()
    }

    private fun load(resource: String): YamlConfiguration {
        ModuleConfigManagerRegressionTest::class.java.classLoader.getResourceAsStream(resource).use { input ->
            check(input != null, "bundled configuration is missing: $resource")
            val config = YamlConfiguration()
            config.loadFromString(String(input!!.readAllBytes(), StandardCharsets.UTF_8))
            return config
        }
    }

    private fun check(condition: Boolean, message: String) {
        if (!condition) {
            throw AssertionError(message)
        }
    }
}
