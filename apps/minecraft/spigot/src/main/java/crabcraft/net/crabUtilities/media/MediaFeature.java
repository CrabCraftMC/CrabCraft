package crabcraft.net.crabUtilities.media;

import crabcraft.net.crabUtilities.CrabUtilities;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import crabcraft.net.crabUtilities.media.audio.AudioEngine;
import crabcraft.net.crabUtilities.media.command.MediaCommand;
import crabcraft.net.crabUtilities.media.command.DiscCommand;
import crabcraft.net.crabUtilities.media.command.HornCommand;
import crabcraft.net.crabUtilities.media.event.HornPlaybackListener;
import crabcraft.net.crabUtilities.media.event.JukeboxPlaybackListener;
import crabcraft.net.crabUtilities.media.file.LegacyMediaMigration;
import crabcraft.net.crabUtilities.media.file.MediaConfig;
import crabcraft.net.crabUtilities.media.language.MediaMessages;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.Arrays;

/**
 * Disc, horn, and shared audio support owned by Crab Utilities.
 */
public final class MediaFeature {
  private static MediaFeature instance;

  private final CrabUtilities javaPlugin;
  private final File dataFolder;
  private final MediaConfig config;
  private final MediaMessages language = new MediaMessages();
  private AutoCloseable packetEventsRegistration;
  private boolean audioStarted;
  private boolean enabled;

  private MediaFeature(CrabUtilities javaPlugin) {
    this.javaPlugin = javaPlugin;
    this.dataFolder = new File(javaPlugin.getDataFolder(), "media");
    this.config = new MediaConfig(javaPlugin);
  }

  /** Creates and enables the module during the main plugin's enable phase. */
  public static synchronized void enable(CrabUtilities javaPlugin) {
    if (instance == null) {
      instance = new MediaFeature(javaPlugin);
    }
    MediaFeature feature = get();
    if (feature.enabled) return;
    if (!feature.integrationAvailable("voicechat")) {
      warn("Media playback disabled: Simple Voice Chat must be installed");
      return;
    }

    if (!feature.dataFolder.exists() && !feature.dataFolder.mkdirs()) {
      warn("Failed to create media data directory {}", feature.dataFolder.getAbsolutePath());
    }
    LegacyMediaMigration.run(feature.javaPlugin, feature.dataFolder);
    Thread provisioner = new Thread(
      AudioEngine.getInstance()::provision,
      "CrabUtilities-MediaInit");
    provisioner.setDaemon(true);
    provisioner.start();
    feature.audioStarted = true;

    if (!feature.integrationAvailable("packetevents")) {
      warn("Disc and horn interactions disabled because PacketEvents is not installed; shared audio remains available");
      return;
    }

    feature.registerEvents();
    feature.registerCommands();
    feature.packetEventsRegistration = JukeboxPacketFilter.install();
    feature.enabled = true;

    info("Crab Utilities media feature enabled");
  }

  public static synchronized void disable() {
    if (instance == null) return;
    if (instance.packetEventsRegistration != null) {
      try {
        instance.packetEventsRegistration.close();
      } catch (Exception e) {
        warn("PacketEvents listener shutdown failed: {}", e.getMessage());
      }
      instance.packetEventsRegistration = null;
    }
    if (instance.audioStarted) {
      AudioEngine.getInstance().shutdown();
      instance.audioStarted = false;
    }
    instance.enabled = false;
    info("Crab Utilities media feature disabled");
  }

  public static MediaFeature get() {
    if (instance == null) throw new IllegalStateException("Media feature has not been loaded");
    return instance;
  }

  public CrabUtilities getJavaPlugin() { return javaPlugin; }
  public File getDataFolder() { return dataFolder; }
  public MediaConfig getMediaConfig() { return config; }
  public MediaMessages getMessages() { return language; }
  public org.bukkit.Server getServer() { return javaPlugin.getServer(); }
  public boolean isEnabled() { return enabled; }

  public boolean refreshConfiguration() {
    boolean mediaPolicyReloaded = !audioStarted || AudioEngine.getInstance().reloadMediaPolicy();
    return mediaPolicyReloaded;
  }

  private boolean integrationAvailable(String name) {
    return javaPlugin.getServer().getPluginManager().isPluginEnabled(name);
  }

  @SuppressWarnings("UnstableApiUsage")
  private void registerCommands() {
    javaPlugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
      var commands = event.registrar();
      commands.register("disc", "Create and edit playable music discs.", java.util.List.of(), new DiscCommand());
      commands.register("horn", "Create and edit playable goat horns.", java.util.List.of(), new HornCommand());
      commands.register("cd", "Crab Utilities media commands.", java.util.List.of(), new MediaCommand());
    });
  }

  private void registerEvents() {
    var manager = javaPlugin.getServer().getPluginManager();
    manager.registerEvents(new JukeboxPlaybackListener(), javaPlugin);
    manager.registerEvents(new HornPlaybackListener(), javaPlugin);
  }

  public static void sendMessage(CommandSender sender, Component component) {
    sender.sendMessage(component);
  }

  public static void debug(@NotNull String message, Object... format) {
    if (get().getMediaConfig().isDebug()) {
      get().javaPlugin.getSLF4JLogger().info(message, format);
    }
  }

  public static void info(@NotNull String message, Object... format) {
    get().javaPlugin.getSLF4JLogger().info(message, format);
  }

  public static void warn(@NotNull String message, Object... format) {
    get().javaPlugin.getSLF4JLogger().warn(message, format);
  }

  public static void error(@NotNull String message, @Nullable Throwable e, Object... format) {
    Object[] arguments = Arrays.copyOf(format, format.length + 1);
    arguments[format.length] = e;
    get().javaPlugin.getSLF4JLogger().error(message, arguments);
  }

  public static void error(@NotNull String message, Object... format) {
    get().javaPlugin.getSLF4JLogger().error(message, format);
  }
}
