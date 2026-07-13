package net.crabcraft.customdiscs;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockEntityData;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEffect;
import com.tcoded.folialib.FoliaLib;
import crabcraft.net.crabUtilities.CrabUtilities;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.crabcraft.customdiscs.api.CustomDiscsAPI;
import net.crabcraft.customdiscs.audio.AudioEngine;
import net.crabcraft.customdiscs.command.CustomDiscsCommand;
import net.crabcraft.customdiscs.command.DiscCommand;
import net.crabcraft.customdiscs.command.HornCommand;
import net.crabcraft.customdiscs.event.HopperHandler;
import net.crabcraft.customdiscs.event.HornHandler;
import net.crabcraft.customdiscs.event.JukeboxHandler;
import net.crabcraft.customdiscs.event.PlayerHandler;
import net.crabcraft.customdiscs.file.CDConfig;
import net.crabcraft.customdiscs.language.YamlLanguage;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.ServicePriority;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NonNull;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;

/**
 * Internal CustomDiscs feature facade. CrabUtilities owns the actual plugin
 * lifecycle; this class preserves the existing CustomDiscs API and keeps the
 * feature's configuration and data isolated inside the master plugin folder.
 */
public final class CustomDiscs {
  private static CustomDiscs instance;

  private final CrabUtilities javaPlugin;
  private final File dataFolder;
  private final CDConfig cDConfig;
  private final YamlLanguage language = new YamlLanguage();
  private final FoliaLib foliaLib;
  private final CustomDiscsAPI apiProvider = new CustomDiscsAPIImpl();
  private boolean audioStarted;
  private boolean enabled;

  private CustomDiscs(CrabUtilities javaPlugin) {
    this.javaPlugin = javaPlugin;
    this.dataFolder = new File(javaPlugin.getDataFolder(), "customdiscs");
    migrateLegacyData();
    this.cDConfig = new CDConfig(new File(dataFolder, "config.yml"));
    this.foliaLib = new FoliaLib(javaPlugin);
  }

  /** Called from CrabUtilities.onLoad so API consumers can resolve the service early. */
  public static synchronized void load(CrabUtilities javaPlugin) {
    if (instance != null) return;
    instance = new CustomDiscs(javaPlugin);
    javaPlugin.getServer().getServicesManager().register(
      CustomDiscsAPI.class, instance.apiProvider, javaPlugin, ServicePriority.Normal);
  }

  public static synchronized void enable() {
    CustomDiscs feature = getPlugin();
    if (feature.enabled) return;
    if (!feature.integrationAvailable("voicechat")) {
      warn("Custom audio disabled: Simple Voice Chat must be installed");
      return;
    }

    if (!feature.dataFolder.exists() && !feature.dataFolder.mkdirs()) {
      warn("Failed to create custom-discs data directory {}", feature.dataFolder.getAbsolutePath());
    }
    feature.cDConfig.load();
    feature.language.load();

    Thread provisioner = new Thread(AudioEngine.getInstance()::provision, "CD-AudioInit");
    provisioner.setDaemon(true);
    provisioner.start();
    feature.audioStarted = true;

    if (!feature.integrationAvailable("packetevents")) {
      warn("Custom discs disabled because PacketEvents is not installed; shared audio remains available");
      return;
    }

    feature.registerEvents();
    feature.registerCommands();
    feature.registerPacketListener();
    feature.enabled = true;

    info("Custom discs feature enabled inside CrabUtilities");
  }

  public static synchronized void disable() {
    if (instance == null) return;
    if (instance.audioStarted) {
      AudioEngine.getInstance().shutdown();
      instance.audioStarted = false;
    }
    if (instance.enabled) {
      instance.foliaLib.getScheduler().cancelAllTasks();
    }
    instance.javaPlugin.getServer().getServicesManager().unregister(CustomDiscsAPI.class, instance.apiProvider);
    instance.enabled = false;
    info("Custom discs feature disabled");
  }

  public static CustomDiscs getPlugin() {
    if (instance == null) throw new IllegalStateException("CustomDiscs feature has not been loaded");
    return instance;
  }

  public CrabUtilities getJavaPlugin() { return javaPlugin; }
  public File getDataFolder() { return dataFolder; }
  public CDConfig getCDConfig() { return cDConfig; }
  public YamlLanguage getLanguage() { return language; }
  public FoliaLib getFoliaLib() { return foliaLib; }
  public org.bukkit.Server getServer() { return javaPlugin.getServer(); }
  public boolean isEnabled() { return enabled; }

  private boolean integrationAvailable(String name) {
    return javaPlugin.getServer().getPluginManager().isPluginEnabled(name);
  }

  @SuppressWarnings("UnstableApiUsage")
  private void registerCommands() {
    javaPlugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
      var commands = event.registrar();
      commands.register("disc", "Create custom music discs.", java.util.List.of(), new DiscCommand());
      commands.register("horn", "Create custom goat horns.", java.util.List.of(), new HornCommand());
      commands.register("customdiscs", "CustomDiscs commands.", java.util.List.of("cd"),
        new CustomDiscsCommand());
    });
  }

  private void registerEvents() {
    var manager = javaPlugin.getServer().getPluginManager();
    manager.registerEvents(new JukeboxHandler(), javaPlugin);
    manager.registerEvents(PlayerHandler.getInstance(), javaPlugin);
    manager.registerEvents(new HopperHandler(), javaPlugin);
    manager.registerEvents(new HornHandler(), javaPlugin);
  }

  private void registerPacketListener() {
    PacketEvents.getAPI().getEventManager().registerListener(new PacketListener() {
      @Override
      public void onPacketSend(@NonNull PacketSendEvent event) {
        if (event.getPacketType() == PacketType.Play.Server.EFFECT) {
          var packet = new WrapperPlayServerEffect(event);
          if (packet.getType() == 1010) {
            var pos = packet.getPosition();
            var player = (org.bukkit.entity.Player) event.getPlayer();
            var block = player.getWorld().getBlockAt(pos.getX(), pos.getY(), pos.getZ());
            if (AudioEngine.getInstance().isPlaying(block)) event.setCancelled(true);
          }
        }

        if (event.getPacketType() == PacketType.Play.Server.BLOCK_ENTITY_DATA) {
          var packet = new WrapperPlayServerBlockEntityData(event);
          var pos = packet.getPosition();
          var player = (org.bukkit.entity.Player) event.getPlayer();
          var block = player.getWorld().getBlockAt(pos.getX(), pos.getY(), pos.getZ());
          if (AudioEngine.getInstance().isPlaying(block)) event.setCancelled(true);
        }
      }
    }, PacketListenerPriority.HIGHEST);
  }

  private void migrateLegacyData() {
    File legacy = new File(javaPlugin.getDataFolder().getParentFile(), "CustomDiscs");
    if (dataFolder.exists() || !legacy.isDirectory()) return;
    try (var paths = Files.walk(legacy.toPath())) {
      paths.forEach(source -> {
        var relative = legacy.toPath().relativize(source);
        var destination = dataFolder.toPath().resolve(relative);
        try {
          if (Files.isDirectory(source)) Files.createDirectories(destination);
          else Files.copy(source, destination, StandardCopyOption.COPY_ATTRIBUTES);
        } catch (IOException e) {
          throw new LegacyMigrationException(e);
        }
      });
      javaPlugin.getSLF4JLogger().info(
        "Copied legacy CustomDiscs data into {}", dataFolder.getAbsolutePath());
    } catch (IOException | LegacyMigrationException e) {
      javaPlugin.getSLF4JLogger().warn(
        "Could not migrate legacy CustomDiscs data: {}", e.getMessage());
    }
  }

  private static final class LegacyMigrationException extends RuntimeException {
    private LegacyMigrationException(IOException cause) { super(cause); }
  }

  public static void sendMessage(CommandSender sender, Component component) {
    sender.sendMessage(component);
  }

  public static void debug(@NotNull String message, Object... format) {
    if (getPlugin().getCDConfig().isDebug()) getPlugin().javaPlugin.getSLF4JLogger().info(message, format);
  }

  public static void info(@NotNull String message, Object... format) {
    getPlugin().javaPlugin.getSLF4JLogger().info(message, format);
  }

  public static void warn(@NotNull String message, Object... format) {
    getPlugin().javaPlugin.getSLF4JLogger().warn(message, format);
  }

  public static void error(@NotNull String message, @Nullable Throwable e, Object... format) {
    Object[] arguments = Arrays.copyOf(format, format.length + 1);
    arguments[format.length] = e;
    getPlugin().javaPlugin.getSLF4JLogger().error(message, arguments);
  }

  public static void error(@NotNull String message, Object... format) {
    getPlugin().javaPlugin.getSLF4JLogger().error(message, format);
  }
}
