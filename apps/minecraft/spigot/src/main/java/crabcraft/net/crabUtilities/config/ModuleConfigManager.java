package crabcraft.net.crabUtilities.config;

import crabcraft.net.crabUtilities.CrabUtilities;
import org.bukkit.configuration.Configuration;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public final class ModuleConfigManager {

    static final List<ModuleSpec> MODULES = List.of(
            new ModuleSpec(
                    "integrations",
                    "integrations.yml",
                    List.of("mod-protocols", "bluemap")),
            new ModuleSpec("chat", "chat.yml", List.of("global-chat")),
            new ModuleSpec("voicechat", "voicechat.yml", List.of("voicechat")),
            new ModuleSpec("media", "media.yml", List.of("media")),
            new ModuleSpec("gameplay", "gameplay.yml", List.of("phantoms")),
            new ModuleSpec("tweaks", "tweaks.yml", List.of("tweaks")));

    private static final String CORE = "core";
    private static final String MODULE_DIRECTORY = "modules";

    private final CrabUtilities plugin;
    private final Path dataFolder;
    private final Map<String, ModuleSpec> modulesByName = new LinkedHashMap<>();
    private final Map<String, ModuleSpec> modulesBySection = new LinkedHashMap<>();
    private final Map<ModuleSpec, YamlConfiguration> moduleConfigs = new LinkedHashMap<>();

    public ModuleConfigManager(CrabUtilities plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.dataFolder = plugin.getDataFolder().toPath();
        for (ModuleSpec module : MODULES) {
            modulesByName.put(module.name(), module);
            for (String section : module.sections()) {
                modulesBySection.put(section, module);
            }
        }
    }

    public void initialise() {
        reloadAll();
    }

    public void reload(String target) {
        String normalised = target.toLowerCase(Locale.ROOT);
        if (normalised.equals("all")) {
            reloadAll();
            return;
        }
        if (normalised.equals(CORE)) {
            reloadCore();
            return;
        }

        ModuleSpec module = modulesByName.get(normalised);
        if (module == null) {
            throw new IllegalArgumentException("Unknown configuration module: " + target);
        }
        reloadModule(module);
    }

    public List<String> reloadTargets() {
        List<String> targets = new ArrayList<>();
        targets.add("all");
        targets.add(CORE);
        targets.addAll(modulesByName.keySet());
        return List.copyOf(targets);
    }

    public void saveValues(Map<String, ?> values) {
        if (values.isEmpty()) {
            return;
        }

        ModuleSpec targetModule = null;
        for (Map.Entry<String, ?> entry : values.entrySet()) {
            ModuleSpec module = moduleForPath(entry.getKey());
            if (module == null) {
                throw new IllegalArgumentException(
                        "Configuration path is not owned by a module: "
                                + entry.getKey());
            }
            if (targetModule != null && !targetModule.equals(module)) {
                throw new IllegalArgumentException(
                        "Configuration values must belong to one module");
            }
            targetModule = module;
        }

        YamlConfiguration current = moduleConfigs.get(targetModule);
        if (current == null) {
            throw new IllegalStateException("Module configuration has not been loaded");
        }
        YamlConfiguration staged = copyWithValues(current, values);

        try {
            saveAtomically(staged, modulePath(targetModule));
        } catch (IOException e) {
            throw new ModuleConfigException(configurationErrorMessage(e), e);
        }

        moduleConfigs.put(targetModule, staged);
        values.forEach((path, value) -> plugin.getConfig().set(path, value));
    }

    static YamlConfiguration copyWithValues(
            YamlConfiguration source,
            Map<String, ?> values) {
        YamlConfiguration copy = new YamlConfiguration();
        try {
            copy.loadFromString(source.saveToString());
        } catch (InvalidConfigurationException impossible) {
            throw new IllegalStateException(
                    "Could not copy valid module configuration",
                    impossible);
        }
        values.forEach(copy::set);
        return copy;
    }

    static boolean mergeDefaults(
            FileConfiguration target,
            Configuration defaults) {
        boolean changed = false;
        for (String key : defaults.getKeys(true)) {
            if (!target.contains(key, true)) {
                if (defaults.isConfigurationSection(key)) {
                    target.createSection(key);
                } else {
                    target.set(key, defaults.get(key));
                }
                changed = true;
            }
            if (target.getComments(key).isEmpty()
                    && !defaults.getComments(key).isEmpty()) {
                target.setComments(key, defaults.getComments(key));
                changed = true;
            }
            if (target.getInlineComments(key).isEmpty()
                    && !defaults.getInlineComments(key).isEmpty()) {
                target.setInlineComments(key, defaults.getInlineComments(key));
                changed = true;
            }
        }
        return changed;
    }

    static void overlayModule(
            FileConfiguration target,
            ModuleSpec module,
            YamlConfiguration config) {
        for (String section : module.sections()) {
            target.set(section, config.get(section));
            copyComments(config, target, section);
        }
    }

    static ModuleSpec moduleForPathStatic(String path) {
        int separator = path.indexOf('.');
        String section = separator < 0 ? path : path.substring(0, separator);
        return MODULES.stream()
                .filter(module -> module.sections().contains(section))
                .findFirst()
                .orElse(null);
    }

    private void reloadAll() {
        try {
            ensureModuleFiles();

            PreparedConfiguration core = prepare(
                    dataFolder.resolve("config.yml"),
                    "config.yml");
            Map<ModuleSpec, PreparedConfiguration> preparedModules =
                    new LinkedHashMap<>();
            for (ModuleSpec module : MODULES) {
                preparedModules.put(
                        module,
                        prepare(
                                modulePath(module),
                                resourcePath(module)));
            }

            saveIfChanged(core);
            for (PreparedConfiguration prepared : preparedModules.values()) {
                saveIfChanged(prepared);
            }

            plugin.reloadConfig();
            moduleConfigs.clear();
            for (Map.Entry<ModuleSpec, PreparedConfiguration> entry
                    : preparedModules.entrySet()) {
                YamlConfiguration config = entry.getValue().config();
                moduleConfigs.put(entry.getKey(), config);
                overlayModule(plugin.getConfig(), entry.getKey(), config);
            }
            warnAboutIgnoredLegacySections(core.config());
        } catch (IOException | InvalidConfigurationException e) {
            throw new ModuleConfigException(configurationErrorMessage(e), e);
        }
    }

    private void reloadCore() {
        try {
            PreparedConfiguration core = prepare(
                    dataFolder.resolve("config.yml"),
                    "config.yml");
            saveIfChanged(core);
            plugin.reloadConfig();
            moduleConfigs.forEach(
                    (module, config) -> overlayModule(plugin.getConfig(), module, config));
            warnAboutIgnoredLegacySections(core.config());
        } catch (IOException | InvalidConfigurationException e) {
            throw new ModuleConfigException(configurationErrorMessage(e), e);
        }
    }

    private void reloadModule(ModuleSpec module) {
        try {
            ensureModuleFile(module);
            PreparedConfiguration prepared = prepare(
                    modulePath(module),
                    resourcePath(module));
            saveIfChanged(prepared);
            moduleConfigs.put(module, prepared.config());
            overlayModule(plugin.getConfig(), module, prepared.config());
        } catch (IOException | InvalidConfigurationException e) {
            throw new ModuleConfigException(configurationErrorMessage(e), e);
        }
    }

    private PreparedConfiguration prepare(
            Path path,
            String defaultResource)
            throws IOException, InvalidConfigurationException {
        YamlConfiguration config = load(path);
        YamlConfiguration defaults = loadResource(defaultResource);
        return new PreparedConfiguration(path, config, mergeDefaults(config, defaults));
    }

    private void ensureModuleFiles() throws IOException {
        Files.createDirectories(dataFolder.resolve(MODULE_DIRECTORY));
        for (ModuleSpec module : MODULES) {
            ensureModuleFile(module);
        }
    }

    private void ensureModuleFile(ModuleSpec module) {
        Path path = modulePath(module);
        if (!Files.exists(path)) {
            plugin.saveResource(resourcePath(module), false);
        }
    }

    private YamlConfiguration load(Path path)
            throws IOException, InvalidConfigurationException {
        YamlConfiguration config = new YamlConfiguration();
        try {
            config.load(path.toFile());
        } catch (IOException | InvalidConfigurationException e) {
            throw new ModuleConfigException(
                    "Invalid configuration in "
                            + dataFolder.relativize(path)
                            + ": "
                            + e.getMessage(),
                    e);
        }
        return config;
    }

    private YamlConfiguration loadResource(String resource)
            throws IOException, InvalidConfigurationException {
        YamlConfiguration config = new YamlConfiguration();
        try (var input = plugin.getResource(resource)) {
            if (input == null) {
                throw new IOException("Bundled configuration is missing: " + resource);
            }
            config.load(new InputStreamReader(input, StandardCharsets.UTF_8));
        }
        return config;
    }

    private void saveIfChanged(PreparedConfiguration prepared)
            throws IOException {
        if (prepared.changed()) {
            saveAtomically(prepared.config(), prepared.path());
        }
    }

    private void warnAboutIgnoredLegacySections(Configuration core) {
        List<String> ignored = modulesBySection.keySet().stream()
                .filter(section -> core.contains(section, true))
                .toList();
        if (!ignored.isEmpty()) {
            plugin.getLogger().warning(
                    "Module sections in config.yml are ignored: "
                            + String.join(", ", ignored)
                            + ". Move customised values into the matching modules/*.yml files.");
        }
    }

    private ModuleSpec moduleForPath(String path) {
        int separator = path.indexOf('.');
        String section = separator < 0 ? path : path.substring(0, separator);
        return modulesBySection.get(section);
    }

    private Path modulePath(ModuleSpec module) {
        return dataFolder.resolve(MODULE_DIRECTORY).resolve(module.fileName());
    }

    private String resourcePath(ModuleSpec module) {
        return MODULE_DIRECTORY + "/" + module.fileName();
    }

    private static void copyComments(
            Configuration source,
            FileConfiguration target,
            String path) {
        if (!source.getComments(path).isEmpty()) {
            target.setComments(path, source.getComments(path));
        }
        if (!source.getInlineComments(path).isEmpty()) {
            target.setInlineComments(path, source.getInlineComments(path));
        }
    }

    private static void saveAtomically(
            YamlConfiguration config,
            Path destination) throws IOException {
        Files.createDirectories(destination.getParent());
        Path temporary = Files.createTempFile(
                destination.getParent(),
                "." + destination.getFileName(),
                ".tmp");
        try {
            Files.writeString(
                    temporary,
                    config.saveToString(),
                    StandardCharsets.UTF_8);
            try {
                Files.move(
                        temporary,
                        destination,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(
                        temporary,
                        destination,
                        StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private String configurationErrorMessage(Exception error) {
        return "Could not load CrabUtilities configuration: " + error.getMessage();
    }

    record ModuleSpec(String name, String fileName, List<String> sections) {
    }

    private record PreparedConfiguration(
            Path path,
            YamlConfiguration config,
            boolean changed) {
    }
}
