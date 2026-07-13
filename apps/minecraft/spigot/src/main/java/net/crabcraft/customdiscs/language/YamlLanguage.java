package net.crabcraft.customdiscs.language;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.simpleyaml.configuration.file.YamlFile;
import net.crabcraft.customdiscs.CustomDiscs;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Objects;

public class YamlLanguage {
  private static final MiniMessage MINIMESSAGE = MiniMessage.miniMessage();
  private final YamlFile language = new YamlFile();

  public void load() {
    var plugin = CustomDiscs.getPlugin();
    var locale = plugin.getCDConfig().getLocale();

    try {
      var langDir = plugin.getDataFolder().toPath().resolve("language");
      Files.createDirectories(langDir);
      var langFile = langDir.resolve("%s.yml".formatted(locale)).toFile();
      var resourcePath = "customdiscs/language/%s.yml".formatted(languageExists(locale) ? locale : Language.ENGLISH.getLabel());

      if (!langFile.exists()) {
        saveResourceSafely(resourcePath, langFile);
      }

      language.load(langFile);

      // Add any keys that exist in the bundled defaults but are missing from the on-disk
      // file, so newly added strings appear after a plugin update without deleting the file.
      // Existing values are left untouched, so any customisations the user made are preserved.
      if (mergeMissingDefaults(resourcePath)) {
        language.save(langFile);
      }
    } catch (Throwable e) {
      CustomDiscs.error("Error while loading language: ", e);
    }
  }

  private boolean mergeMissingDefaults(String resourcePath) throws IOException {
    var defaults = new YamlFile();
    defaults.load(() -> getClass().getClassLoader().getResourceAsStream(resourcePath));

    boolean changed = false;
    for (String key : defaults.getKeys(true)) {
      if (defaults.isConfigurationSection(key)) continue; // copy leaf values only
      if (!language.contains(key)) {
        language.set(key, defaults.get(key));
        changed = true;
      }
    }
    return changed;
  }

  private void saveResourceSafely(String resourcePath, File outFile) throws IOException {
    try (InputStream in = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
      if (in == null) throw new IOException("Resource not found: %s".formatted(resourcePath));
      Files.copy(in, outFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }
  }

  private String getFormattedString(String key, Object... replace) {
    var result = language.getString("language.%s".formatted(key), "<%s>".formatted(key));
    for (int i = 0; i < replace.length; i++) {
      result = result.replace("{%d}".formatted(i), (String) replace[i]);
    }
    return result;
  }

  public Component component(String key, Object... replace) {
    return MINIMESSAGE.deserialize(getFormattedString(key, replace));
  }

  public Component component(String key, Component replacement) {
    return MINIMESSAGE.deserialize(getFormattedString(key))
      .append(Component.space())
      .append(replacement);
  }

  public Component PComponent(String key, Object... replace) {
    return MINIMESSAGE.deserialize(string("prefix") + getFormattedString(key, replace));
  }

  public String string(String key, Object... replace) {
    return getFormattedString(key, replace);
  }

  public boolean languageExists(String label) {
    var inputStream = this.getClass().getClassLoader().getResourceAsStream("customdiscs/language/%s.yml".formatted(label));
    return !Objects.isNull(inputStream);
  }
}
