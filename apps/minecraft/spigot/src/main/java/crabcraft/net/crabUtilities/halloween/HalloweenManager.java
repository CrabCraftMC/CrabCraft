package crabcraft.net.crabUtilities.halloween;

import com.google.gson.JsonParser;
import com.google.gson.JsonObject;
import crabcraft.net.crabUtilities.CrabMessages;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import crabcraft.net.crabUtilities.bingo.BingoCardTwoListener;
import crabcraft.net.crabUtilities.bingo.BingoTask;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.entity.ZombieVillager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTransformEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.JedisPubSub;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

/** Sends ordered Halloween actions; the bot stores hunt progress and awards the role. */
public final class HalloweenManager implements Listener {
    private static final String STREAM = "crabcraft:halloween:actions";
    private static final String ACTIVE_KEY = "crabcraft:halloween:active-event";
    private static final Set<String> HUNT_MOBS = Set.of("zombie", "skeleton", "spider", "creeper", "witch");
    private static final String PUBLISH_SCRIPT = """
            if redis.call('EXISTS', KEYS[1]) == 1 then return 0 end
            redis.call('XADD', KEYS[2], '*',
                'event_id', ARGV[1], 'minecraft_uuid', ARGV[2],
                'action', ARGV[3], 'occurred_at', ARGV[4])
            redis.call('SET', KEYS[1], '1', 'EX', 604800)
            return 1
            """;

    private final JavaPlugin plugin;
    private final Set<String> excludedWorlds;
    private final ConcurrentLinkedQueue<PendingAction> pending = new ConcurrentLinkedQueue<>();
    private final BingoCardTwoListener bellDetector;
    private JedisPool pool;
    private BukkitTask refreshTask;
    private BukkitTask flushTask;
    private Thread completionThread;
    private volatile JedisPubSub completionSubscriber;
    private volatile boolean running;
    private boolean failureLogged;
    private ActiveEvent activeEvent;
    private final Set<UUID> progressRequests = new HashSet<>();

    public HalloweenManager(JavaPlugin plugin) {
        this.plugin = plugin;
        excludedWorlds = Set.copyOf(plugin.getConfig().getStringList("halloween.excluded-worlds"));
        bellDetector = new BingoCardTwoListener(plugin,
                (player, task) -> task == BingoTask.CREEPER_RINGS_BELL && eligible(player),
                (player, task) -> record(player, "trick_or_treat"));
    }

    public void start() {
        String password = plugin.getConfig().getString("redis.password", "");
        pool = new JedisPool(new JedisPoolConfig(),
                plugin.getConfig().getString("redis.host", "localhost"),
                plugin.getConfig().getInt("redis.port", 6379), 2_000,
                password == null || password.isEmpty() ? null : password);
        running = true;
        completionThread = new Thread(this::listenForCompletions, "Halloween completions");
        completionThread.setDaemon(true);
        completionThread.start();
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.getServer().getPluginManager().registerEvents(bellDetector, plugin);
        refreshTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::refresh, 0L, 20L * 10);
        flushTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::flush, 20L, 20L);
    }

    private void listenForCompletions() {
        while (running) {
            try (Jedis jedis = pool.getResource()) {
                completionSubscriber = new JedisPubSub() {
                    @Override
                    public void onMessage(String channel, String message) {
                        try {
                            JsonObject completion = JsonParser.parseString(message).getAsJsonObject();
                            UUID playerId = UUID.fromString(completion.get("minecraftUuid").getAsString());
                            String task = switch (completion.get("task").getAsString()) {
                                case "pumpkin_hunt" -> "Pumpkin Head’s Hunt";
                                case "trick_or_treat" -> "Trick or Treat";
                                case "back_from_the_dead" -> "Back from the Dead";
                                default -> null;
                            };
                            if (task == null || !running) return;
                            int count = completion.get("completedTasks").getAsInt();
                            Bukkit.getScheduler().runTask(plugin, () -> {
                                if (!running) return;
                                Player player = Bukkit.getPlayer(playerId);
                                if (player == null) return;
                                player.sendMessage(Component.text("Completed ", TextColor.color(0xFF8C32))
                                        .append(Component.text(task + "! ", TextColor.color(0xFFC65A)))
                                        .append(Component.text("(" + count + "/3)", TextColor.color(0xADCA88))));
                                if (count == 3) {
                                    player.sendMessage(Component.text(
                                            "You've earned an exclusive Discord role and an in-game pumpkin tag!", TextColor.color(0xFFC65A))
                                            .hoverEvent(HoverEvent.showText(CrabMessages.text(
                                                    "Link your Minecraft account on Discord to receive the role."
                                                    + "\nThe in-game tag lasts while the event is active."))));
                                }
                            });
                        } catch (Exception error) {
                            if (running) plugin.getLogger().warning("Invalid Halloween completion: " + error.getMessage());
                        }
                    }

                    @Override
                    public void onSubscribe(String channel, int subscribedChannels) {
                        if (!running) unsubscribe();
                    }
                };
                if (running) jedis.subscribe(completionSubscriber, "crabcraft:halloween:completions");
            } catch (Exception error) {
                if (running) logFailure(error);
            }
            if (running) {
                try {
                    Thread.sleep(1_000);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /** All Redis work runs asynchronously; only the requesting player receives the reply. */
    public void showProgress(Player player) {
        UUID playerId = player.getUniqueId();
        if (!running || !progressRequests.add(playerId)) return;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            JsonObject response = null;
            try (Jedis jedis = pool.getResource()) {
                JsonObject request = new JsonObject();
                String requestId = UUID.randomUUID().toString();
                request.addProperty("requestId", requestId);
                request.addProperty("playerId", playerId.toString());
                request.addProperty("expiresAt", System.currentTimeMillis() + 10_000);
                String replyKey = "crabcraft:halloween:progress-reply:" + requestId;
                jedis.rpush("crabcraft:halloween:progress-requests", request.toString());
                List<String> reply = jedis.blpop(5, replyKey);
                if (reply != null) response = JsonParser.parseString(reply.get(1)).getAsJsonObject();
            } catch (Exception error) {
                logFailure(error);
            }
            JsonObject result = response;
            if (running) Bukkit.getScheduler().runTask(plugin, () -> {
                progressRequests.remove(playerId);
                if (!running || !player.isOnline()) return;
                if (result == null) {
                    player.sendMessage(CrabMessages.error("Halloween progress is temporarily unavailable. Please try again."));
                } else if (result.has("unavailable")) {
                    player.sendMessage(CrabMessages.muted("The Halloween event is currently unavailable."));
                } else {
                    sendProgress(player, result);
                }
            });
        });
    }

    private void sendProgress(Player player, JsonObject progress) {
        long now = System.currentTimeMillis() / 1000;
        long start = progress.get("startsAt").getAsLong();
        long end = progress.get("endsAt").getAsLong();
        var date = DateTimeFormatter.ofPattern("d MMM yyyy HH:mm z", Locale.UK).withZone(ZoneId.of("Europe/London"));
        int mask = progress.get("huntMask").getAsInt();
        List<String> mobs = List.of("zombie", "skeleton", "spider", "creeper", "witch");
        List<String> remaining = new ArrayList<>();
        for (int index = 0; index < mobs.size(); index++) {
            if ((mask & (1 << index)) == 0) remaining.add(mobs.get(index));
        }
        boolean trick = progress.get("trickOrTreat").getAsBoolean();
        boolean cure = progress.get("backFromTheDead").getAsBoolean();
        int completed = (mask == 31 ? 1 : 0) + (trick ? 1 : 0) + (cure ? 1 : 0);
        String timing = now < start ? "Starts " + date.format(Instant.ofEpochSecond(start))
                : now >= end ? "The event has ended." : "Ends " + date.format(Instant.ofEpochSecond(end));
        player.sendMessage(CrabMessages.accent("Halloween · " + completed + "/3 complete")
                .append(CrabMessages.muted(" (hover)"))
                .hoverEvent(HoverEvent.showText(CrabMessages.text(timing
                        + "\nComplete all three on this account for the Discord role."
                        + "\nLink your Discord account to receive it."
                        + "\nProgress can take a few seconds to update."))));
        player.sendMessage((mask == 31 ? CrabMessages.success("✓ Pumpkin Head’s Hunt · 5/5")
                : CrabMessages.text("○ Pumpkin Head’s Hunt · " + (5 - remaining.size()) + "/5"))
                .hoverEvent(HoverEvent.showText(CrabMessages.text(
                        "Wear a carved pumpkin and kill a zombie, skeleton, spider, creeper and witch without dying."
                        + (remaining.isEmpty() ? "" : "\nRemaining: " + String.join(", ", remaining))
                        + "\nDeath only resets an unfinished hunt."))));
        player.sendMessage((trick ? CrabMessages.success("✓ Trick or Treat") : CrabMessages.text("○ Trick or Treat"))
                .hoverEvent(HoverEvent.showText(CrabMessages.text(
                        "Place a pressure plate directly beside a bell."
                        + "\nGet a creeper to step on it and ring the bell."
                        + "\nYou must place the plate yourself."))));
        player.sendMessage((cure ? CrabMessages.success("✓ Back from the Dead") : CrabMessages.text("○ Back from the Dead"))
                .hoverEvent(HoverEvent.showText(CrabMessages.text(
                        "Cure a zombie villager. Stay online in Survival and wear a carved pumpkin when the cure finishes."))));
    }

    private boolean live() {
        long now = System.currentTimeMillis() / 1000;
        return running && activeEvent != null
                && activeEvent.startsAt() <= now && now < activeEvent.endsAt();
    }

    private boolean eligible(Player player) {
        return live() && player.getGameMode() == GameMode.SURVIVAL
                && !excludedWorlds.contains(player.getWorld().getName());
    }

    private static boolean wearsPumpkin(Player player) {
        ItemStack helmet = player.getInventory().getHelmet();
        return helmet != null && helmet.getType() == Material.CARVED_PUMPKIN;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMobDeath(EntityDeathEvent event) {
        Player killer = event.getEntity().getKiller();
        String mob = event.getEntityType().name().toLowerCase(Locale.ROOT);
        if (killer != null && HUNT_MOBS.contains(mob) && eligible(killer) && wearsPumpkin(killer)
                && !excludedWorlds.contains(event.getEntity().getWorld().getName())) {
            record(killer, mob);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDeath(PlayerDeathEvent event) {
        // Removing the pumpkin or travelling to an excluded world cannot bypass a reset.
        if (live() && event.getEntity().getGameMode() == GameMode.SURVIVAL) {
            record(event.getEntity(), "death");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCure(EntityTransformEvent event) {
        if (event.getTransformReason() != EntityTransformEvent.TransformReason.CURED
                || !(event.getEntity() instanceof ZombieVillager zombie)
                || zombie.getConversionPlayer() == null
                || excludedWorlds.contains(zombie.getWorld().getName())) return;
        Player player = Bukkit.getPlayer(zombie.getConversionPlayer().getUniqueId());
        if (player != null && eligible(player) && wearsPumpkin(player)) {
            record(player, "back_from_the_dead");
        }
    }

    private void record(Player player, String action) {
        if (!live()) return;
        pending.add(new PendingAction(UUID.randomUUID().toString(), activeEvent.id(),
                player.getUniqueId().toString(), action, System.currentTimeMillis() / 1000));
    }

    private void refresh() {
        try (Jedis jedis = pool.getResource()) {
            String json = jedis.get(ACTIVE_KEY);
            ActiveEvent next = null;
            if (json != null) {
                var object = JsonParser.parseString(json).getAsJsonObject();
                next = new ActiveEvent(object.get("id").getAsString(),
                        object.get("startsAt").getAsLong(), object.get("endsAt").getAsLong());
            }
            ActiveEvent fetched = next;
            if (running) Bukkit.getScheduler().runTask(plugin, () -> {
                if (!running) return;
                if (!Objects.equals(activeEvent, fetched)) bellDetector.clear();
                activeEvent = fetched;
            });
        } catch (Exception e) {
            logFailure(e);
        }
    }

    private synchronized void flush() {
        if (pool == null || pending.isEmpty()) return;
        try (Jedis jedis = pool.getResource()) {
            PendingAction action;
            while ((action = pending.peek()) != null) {
                // Retries after a lost Redis reply cannot replay a kill after a later death.
                jedis.eval(PUBLISH_SCRIPT,
                        List.of("crabcraft:halloween:sent:" + action.id(), STREAM),
                        List.of(action.eventId(), action.playerId(), action.action(), Long.toString(action.occurredAt())));
                pending.remove(action);
            }
            failureLogged = false;
        } catch (Exception e) {
            logFailure(e);
        }
    }

    private synchronized void logFailure(Exception error) {
        if (!failureLogged) {
            plugin.getLogger().warning("Halloween Redis connection unavailable; retrying: " + error.getMessage());
            failureLogged = true;
        }
    }

    public void shutdown() {
        running = false;
        if (completionSubscriber != null && completionSubscriber.isSubscribed()) {
            try {
                completionSubscriber.unsubscribe();
            } catch (Exception error) {
                plugin.getLogger().fine("Halloween subscription already disconnected: " + error.getMessage());
            }
        }
        if (completionThread != null) completionThread.interrupt();
        if (refreshTask != null) refreshTask.cancel();
        if (flushTask != null) flushTask.cancel();
        HandlerList.unregisterAll(this);
        HandlerList.unregisterAll(bellDetector);
        bellDetector.clear();
        flush();
        if (!pending.isEmpty()) plugin.getLogger().severe("Halloween stopped with unpublished actions: " + pending.size());
        if (pool != null) pool.close();
    }

    private record ActiveEvent(String id, long startsAt, long endsAt) {}
    private record PendingAction(String id, String eventId, String playerId, String action, long occurredAt) {}
}
