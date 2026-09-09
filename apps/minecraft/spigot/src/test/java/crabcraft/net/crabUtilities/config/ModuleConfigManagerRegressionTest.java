package crabcraft.net.crabUtilities.config;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

public final class ModuleConfigManagerRegressionTest {

    private ModuleConfigManagerRegressionTest() {
    }

    public static void main(String[] args) throws Exception {
        verifyBundledLayoutAndMergedView();
        verifyDefaultMergePreservesCustomValues();
        verifyValueStagingDoesNotMutateCachedConfiguration();
        verifyPathOwnership();
    }

    private static void verifyValueStagingDoesNotMutateCachedConfiguration() {
        YamlConfiguration cached = new YamlConfiguration();
        cached.set("media.discs.volume", 1.0D);

        YamlConfiguration staged = ModuleConfigManager.copyWithValues(
                cached,
                Map.of("media.discs.volume", 0.35D));

        check(cached.getDouble("media.discs.volume") == 1.0D,
                "staging a module save mutated the cached configuration");
        check(staged.getDouble("media.discs.volume") == 0.35D,
                "staged module values were not applied to the copy");
    }

    private static void verifyBundledLayoutAndMergedView() throws Exception {
        YamlConfiguration core = load("config.yml");
        YamlConfiguration merged = load("config.yml");

        for (ModuleConfigManager.ModuleSpec module : ModuleConfigManager.MODULES) {
            YamlConfiguration config = load("modules/" + module.fileName());
            for (String section : module.sections()) {
                check(!core.contains(section, true),
                        section + " still appears in the core config");
                check(config.isConfigurationSection(section),
                        module.fileName() + " does not own " + section);
            }
            ModuleConfigManager.overlayModule(merged, module, config);
        }

        check(merged.getString("redis.host", "").equals("localhost"),
                "core configuration disappeared from the merged view");
        check(merged.getBoolean("mod-protocols.jade.enabled"),
                "integration configuration disappeared from the merged view");
        check(!merged.getBoolean("mod-protocols.accurate-block-placement.enabled"),
                "accurate block placement is not opt-in in the merged view");
        check(merged.getString("media.providers.yt-dlp-path", "").equals("auto"),
                "media configuration disappeared from the merged view");
        check(!merged.getBoolean("tweaks.view-distance.enabled"),
                "view-distance tweak is not opt-in in the merged view");
    }

    private static void verifyDefaultMergePreservesCustomValues() throws Exception {
        YamlConfiguration custom = new YamlConfiguration();
        custom.set("media.discs.volume", 0.35D);

        boolean changed = ModuleConfigManager.mergeDefaults(
                custom,
                load("modules/media.yml"));

        check(changed, "missing media defaults were not added");
        check(custom.getDouble("media.discs.volume") == 0.35D,
                "custom media value was overwritten by a default");
        check(custom.getString("media.providers.yt-dlp-path", "").equals("auto"),
                "missing media provider default was not added");
    }

    private static void verifyPathOwnership() {
        check(moduleName("media.providers.ffmpeg-path").equals("media"),
                "media path was routed to the wrong module");
        check(moduleName("mod-protocols.jade.enabled").equals("integrations"),
                "Jade path was routed to the wrong module");
        check(moduleName("bluemap.sign-markers.enabled").equals("integrations"),
                "BlueMap path was routed to the wrong module");
        check(moduleName("public-chat.enabled").equals("chat"),
                "public chat path was routed to the wrong module");
        check(moduleName("restricted-area.enabled").equals("gameplay"),
                "restricted area path was routed to the wrong module");
        check(moduleName("tweaks.view-distance.enabled").equals("tweaks"),
                "tweak path was routed to the wrong module");
        check(ModuleConfigManager.moduleForPathStatic("redis.host") == null,
                "core Redis path was routed to a module");

        List<String> names = ModuleConfigManager.MODULES.stream()
                .map(ModuleConfigManager.ModuleSpec::name)
                .toList();
        check(names.equals(List.of(
                        "integrations",
                        "chat",
                        "voicechat",
                        "media",
                        "gameplay",
                        "tweaks")),
                "reload module names changed unexpectedly");
    }

    private static String moduleName(String path) {
        ModuleConfigManager.ModuleSpec module =
                ModuleConfigManager.moduleForPathStatic(path);
        check(module != null, "module path is not owned: " + path);
        return module.name();
    }

    private static YamlConfiguration load(String resource) throws Exception {
        try (InputStream input = ModuleConfigManagerRegressionTest.class
                .getClassLoader()
                .getResourceAsStream(resource)) {
            check(input != null, "bundled configuration is missing: " + resource);
            YamlConfiguration config = new YamlConfiguration();
            config.loadFromString(new String(input.readAllBytes(), StandardCharsets.UTF_8));
            return config;
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
