package crabcraft.net.crabUtilities.bingo;

import io.papermc.paper.event.player.PlayerToggleEntityAgeLockEvent;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import org.bukkit.Bukkit;
import org.bukkit.Instrument;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Frog;
import org.bukkit.entity.Hoglin;
import org.bukkit.entity.LightningStrike;
import org.bukkit.entity.MagmaCube;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.NotePlayEvent;
import org.bukkit.event.entity.CreeperPowerEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.entity.EntityUnleashEvent;
import org.bukkit.event.entity.PlayerLeashEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.weather.LightningStrikeEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

/** Event-driven detectors for the multi-stage challenge tasks in Bingo #3. */
public final class BingoCardThreeChallengeListener implements BingoDetector {
    private static final int MAX_TRANSIENT_ENTRIES = 4_096;
    private static final int NOTE_PLAY_CORRELATION_TICKS = 1;
    private static final long MAX_PERSISTENT_ATTRIBUTION_MILLIS = 7L * 24 * 60 * 60 * 1_000;
    private static final Set<Instrument> COPPER_TRUMPETS = EnumSet.of(
            Instrument.TRUMPET,
            Instrument.TRUMPET_EXPOSED,
            Instrument.TRUMPET_WEATHERED,
            Instrument.TRUMPET_OXIDIZED);

    private final JavaPlugin plugin;
    private final BiPredicate<Player, BingoTask> tracking;
    private final BiConsumer<Player, BingoTask> completion;
    private final NamespacedKey chargedCreeperOwnerKey;
    private final NamespacedKey chargedCreeperAtKey;
    private final NamespacedKey frogLeashOwnerKey;
    private final NamespacedKey frogLeashedAtKey;
    private final Map<UUID, UUID> channelledLightningOwners = new HashMap<>();
    private final Map<BlockKey, TimedPlayer> noteBlockAttempts = new HashMap<>();
    private final Map<UUID, EnumSet<Instrument>> copperTrumpetsByPlayer = new HashMap<>();
    private final Map<UUID, Long> playerGenerations = new HashMap<>();
    private final Map<UUID, Long> playerResetAtMillis = new HashMap<>();
    private long detectorGeneration;
    private long clearedAtMillis;

    public BingoCardThreeChallengeListener(
            JavaPlugin plugin,
            BiPredicate<Player, BingoTask> tracking,
            BiConsumer<Player, BingoTask> completion) {
        this.plugin = plugin;
        this.tracking = tracking;
        this.completion = completion;
        this.chargedCreeperOwnerKey = new NamespacedKey(plugin, "bingo_card3_creeper_owner");
        this.chargedCreeperAtKey = new NamespacedKey(plugin, "bingo_card3_creeper_charged_at");
        this.frogLeashOwnerKey = new NamespacedKey(plugin, "bingo_card3_frog_leash_owner");
        this.frogLeashedAtKey = new NamespacedKey(plugin, "bingo_card3_frog_leashed_at");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        detectLeashedFrogFroglight(event);
        detectChargedCreeperHead(event);
    }

    private void detectLeashedFrogFroglight(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof MagmaCube cube)
                || cube.getSize() != 1
                || !(damageSourceEntity(event) instanceof Frog frog)
                || !frog.isLeashed()
                || event.getDrops().stream()
                        .map(ItemStack::getType)
                        .noneMatch(BingoCardThreeChallengeListener::isFroglight)) {
            return;
        }

        PersistentMarker marker = markerFrom(
                frog.getPersistentDataContainer(), frogLeashOwnerKey, frogLeashedAtKey);
        if (marker == null || !markerIsCurrent(marker)) return;

        Entity leashHolder = frog.getLeashHolder();
        if (leashHolder instanceof Player directHolder) {
            if (marker.playerId().equals(directHolder.getUniqueId())
                    && tracking.test(directHolder, BingoTask.LEASHED_FROG_FROGLIGHT)) {
                completion.accept(directHolder, BingoTask.LEASHED_FROG_FROGLIGHT);
            }
            return;
        }

        Player player = Bukkit.getPlayer(marker.playerId());
        if (player != null && tracking.test(player, BingoTask.LEASHED_FROG_FROGLIGHT)) {
            completion.accept(player, BingoTask.LEASHED_FROG_FROGLIGHT);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFrogLeashed(PlayerLeashEntityEvent event) {
        if (!(event.getEntity() instanceof Frog frog)) return;

        clearFrogLeashAttribution(frog);
        Player player = event.getPlayer();
        if (!tracking.test(player, BingoTask.LEASHED_FROG_FROGLIGHT)) return;

        PersistentDataContainer data = frog.getPersistentDataContainer();
        data.set(frogLeashOwnerKey, PersistentDataType.STRING, player.getUniqueId().toString());
        data.set(frogLeashedAtKey, PersistentDataType.LONG, System.currentTimeMillis());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFrogUnleashed(EntityUnleashEvent event) {
        if (event.getEntity() instanceof Frog frog) {
            clearFrogLeashAttribution(frog);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLightningStruck(LightningStrikeEvent event) {
        if (event.getCause() != LightningStrikeEvent.Cause.TRIDENT) return;

        LightningStrike lightning = event.getLightning();
        Player player = lightning.getCausingPlayer();
        if (player == null && lightning.getCausingEntity() instanceof Player causingPlayer) {
            player = causingPlayer;
        }
        if (player == null || !tracking.test(player, BingoTask.CHARGED_CREEPER_MOB_HEAD)) {
            return;
        }

        putBounded(
                channelledLightningOwners,
                lightning.getUniqueId(),
                player.getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCreeperPowered(CreeperPowerEvent event) {
        Creeper creeper = event.getEntity();
        clearChargedCreeperAttribution(creeper);
        if (event.getCause() != CreeperPowerEvent.PowerCause.LIGHTNING
                || event.getLightning() == null) {
            return;
        }

        UUID ownerId = channelledLightningOwners.get(event.getLightning().getUniqueId());
        if (ownerId == null) return;

        PersistentDataContainer data = creeper.getPersistentDataContainer();
        data.set(chargedCreeperOwnerKey, PersistentDataType.STRING, ownerId.toString());
        data.set(chargedCreeperAtKey, PersistentDataType.LONG, System.currentTimeMillis());
    }

    private void detectChargedCreeperHead(EntityDeathEvent event) {
        Material expectedHead = correspondingMobHead(event.getEntityType());
        if (expectedHead == null
                || event.getDrops().stream().map(ItemStack::getType).noneMatch(expectedHead::equals)
                || !(damageSourceEntity(event) instanceof Creeper creeper)
                || !creeper.isPowered()) {
            return;
        }

        PersistentMarker marker = markerFrom(
                creeper.getPersistentDataContainer(),
                chargedCreeperOwnerKey,
                chargedCreeperAtKey);
        if (marker == null || !markerIsCurrent(marker)) return;

        Player player = Bukkit.getPlayer(marker.playerId());
        if (player != null && tracking.test(player, BingoTask.CHARGED_CREEPER_MOB_HEAD)) {
            completion.accept(player, BingoTask.CHARGED_CREEPER_MOB_HEAD);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHoglinAgeLockToggled(PlayerToggleEntityAgeLockEvent event) {
        Player player = event.getPlayer();
        if (event.getEntity() instanceof Hoglin hoglin
                && !hoglin.isAdult()
                && event.getItem().getType() == Material.GOLDEN_DANDELION
                && event.isAgeLocked()
                && tracking.test(player, BingoTask.GOLDEN_DANDELION_HOGLIN)) {
            UUID playerId = player.getUniqueId();
            UUID hoglinId = hoglin.getUniqueId();
            AttemptToken token = attemptToken(playerId);
            Bukkit.getScheduler().runTask(
                    plugin, () -> confirmHoglinAgeLocked(playerId, hoglinId, token));
        }
    }

    private void confirmHoglinAgeLocked(UUID playerId, UUID hoglinId, AttemptToken token) {
        Player player = Bukkit.getPlayer(playerId);
        Entity entity = Bukkit.getEntity(hoglinId);
        if (player != null
                && entity instanceof Hoglin hoglin
                && !hoglin.isAdult()
                && hoglin.getAgeLock()
                && isCurrent(playerId, token)
                && tracking.test(player, BingoTask.GOLDEN_DANDELION_HOGLIN)) {
            completion.accept(player, BingoTask.GOLDEN_DANDELION_HOGLIN);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onNoteBlockInteracted(PlayerInteractEvent event) {
        Action action = event.getAction();
        Block block = event.getClickedBlock();
        if ((action != Action.LEFT_CLICK_BLOCK && action != Action.RIGHT_CLICK_BLOCK)
                || block == null
                || block.getType() != Material.NOTE_BLOCK
                || event.useInteractedBlock() == Event.Result.DENY
                || !tracking.test(event.getPlayer(), BingoTask.FOUR_COPPER_TRUMPET_SOUNDS)) {
            return;
        }

        BlockKey key = BlockKey.from(block);
        TimedPlayer attempt = new TimedPlayer(
                event.getPlayer().getUniqueId(), Bukkit.getCurrentTick());
        putBounded(noteBlockAttempts, key, attempt);
        Bukkit.getScheduler().runTaskLater(
                plugin, () -> noteBlockAttempts.remove(key, attempt), NOTE_PLAY_CORRELATION_TICKS + 1L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onNotePlayed(NotePlayEvent event) {
        Instrument instrument = event.getInstrument();
        if (!COPPER_TRUMPETS.contains(instrument)) return;

        TimedPlayer attempt = noteBlockAttempts.remove(BlockKey.from(event.getBlock()));
        if (attempt == null
                || !isFresh(attempt.tick(), Bukkit.getCurrentTick(), NOTE_PLAY_CORRELATION_TICKS)) {
            return;
        }

        Player player = Bukkit.getPlayer(attempt.playerId());
        if (player == null || !tracking.test(player, BingoTask.FOUR_COPPER_TRUMPET_SOUNDS)) {
            return;
        }

        EnumSet<Instrument> instruments = copperTrumpetsByPlayer.computeIfAbsent(
                attempt.playerId(), ignored -> EnumSet.noneOf(Instrument.class));
        if (instruments.add(instrument) && instruments.containsAll(COPPER_TRUMPETS)) {
            completion.accept(player, BingoTask.FOUR_COPPER_TRUMPET_SOUNDS);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityRemoved(EntityRemoveEvent event) {
        channelledLightningOwners.remove(event.getEntity().getUniqueId());
    }

    @Override
    public void resetPlayer(UUID playerId) {
        playerGenerations.merge(playerId, 1L, Long::sum);
        channelledLightningOwners.values().removeIf(playerId::equals);
        noteBlockAttempts.values().removeIf(value -> value.playerId().equals(playerId));
        copperTrumpetsByPlayer.remove(playerId);
        playerResetAtMillis.put(playerId, System.currentTimeMillis());
    }

    @Override
    public void clear() {
        detectorGeneration++;
        channelledLightningOwners.clear();
        noteBlockAttempts.clear();
        copperTrumpetsByPlayer.clear();
        playerGenerations.clear();
        playerResetAtMillis.clear();
        clearedAtMillis = System.currentTimeMillis();
    }

    private AttemptToken attemptToken(UUID playerId) {
        return new AttemptToken(detectorGeneration, playerGenerations.getOrDefault(playerId, 0L));
    }

    private boolean isCurrent(UUID playerId, AttemptToken token) {
        return token.detectorGeneration() == detectorGeneration
                && token.playerGeneration() == playerGenerations.getOrDefault(playerId, 0L);
    }

    private PersistentMarker markerFrom(
            PersistentDataContainer data,
            NamespacedKey ownerKey,
            NamespacedKey timestampKey) {
        String owner = data.get(ownerKey, PersistentDataType.STRING);
        Long timestamp = data.get(timestampKey, PersistentDataType.LONG);
        if (owner == null || timestamp == null) return null;
        try {
            return new PersistentMarker(UUID.fromString(owner), timestamp);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private boolean markerIsCurrent(PersistentMarker marker) {
        long now = System.currentTimeMillis();
        long playerResetAt = playerResetAtMillis.getOrDefault(marker.playerId(), 0L);
        return marker.timestamp() > clearedAtMillis
                && marker.timestamp() > playerResetAt
                && marker.timestamp() <= now
                && now - marker.timestamp() <= MAX_PERSISTENT_ATTRIBUTION_MILLIS;
    }

    private void clearChargedCreeperAttribution(Creeper creeper) {
        PersistentDataContainer data = creeper.getPersistentDataContainer();
        data.remove(chargedCreeperOwnerKey);
        data.remove(chargedCreeperAtKey);
    }

    private void clearFrogLeashAttribution(Frog frog) {
        PersistentDataContainer data = frog.getPersistentDataContainer();
        data.remove(frogLeashOwnerKey);
        data.remove(frogLeashedAtKey);
    }

    private static Entity damageSourceEntity(EntityDeathEvent event) {
        Entity causing = event.getDamageSource().getCausingEntity();
        return causing != null ? causing : event.getDamageSource().getDirectEntity();
    }

    static boolean isFroglight(Material material) {
        return material == Material.OCHRE_FROGLIGHT
                || material == Material.VERDANT_FROGLIGHT
                || material == Material.PEARLESCENT_FROGLIGHT;
    }

    static Material correspondingMobHead(EntityType entityType) {
        return switch (entityType) {
            case CREEPER -> Material.CREEPER_HEAD;
            case PIGLIN -> Material.PIGLIN_HEAD;
            case SKELETON -> Material.SKELETON_SKULL;
            case WITHER_SKELETON -> Material.WITHER_SKELETON_SKULL;
            case ZOMBIE -> Material.ZOMBIE_HEAD;
            default -> null;
        };
    }

    private static boolean isFresh(int earlier, int current, int maximumAge) {
        int age = current - earlier;
        return age >= 0 && age <= maximumAge;
    }

    private static <K, V> void putBounded(Map<K, V> map, K key, V value) {
        if (!map.containsKey(key) && map.size() >= MAX_TRANSIENT_ENTRIES) {
            map.remove(map.keySet().iterator().next());
        }
        map.put(key, value);
    }

    private record BlockKey(UUID worldId, int x, int y, int z) {
        static BlockKey from(Block block) {
            return new BlockKey(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
        }
    }

    private record TimedPlayer(UUID playerId, int tick) {}

    private record AttemptToken(long detectorGeneration, long playerGeneration) {}

    private record PersistentMarker(UUID playerId, long timestamp) {}
}
