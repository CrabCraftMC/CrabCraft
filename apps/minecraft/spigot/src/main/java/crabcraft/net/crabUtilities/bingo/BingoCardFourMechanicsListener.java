package crabcraft.net.crabUtilities.bingo;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.PistonMoveReaction;
import org.bukkit.block.data.FaceAttachable;
import org.bukkit.block.data.type.Switch;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.SculkBloomEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.world.StructureGrowEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffectType;

/** Event-driven detectors for the redstone and growth tasks on Bingo #4. */
public final class BingoCardFourMechanicsListener implements BingoDetector {
    private static final int MAX_TRANSIENT_KEYS = 4_096;
    private static final int MAX_OWNERS_PER_WINDOW = 8;
    private static final int PISTON_WINDOW_TICKS = 1;
    private static final int SCULK_WINDOW_TICKS = 1;
    private static final double SCULK_BLOOM_DISTANCE_SQUARED = 4.0;

    private final JavaPlugin plugin;
    private final BiPredicate<Player, BingoTask> tracking;
    private final BiConsumer<Player, BingoTask> completion;
    private final Map<BlockKey, List<TimedAttempt>> leverAttempts = new LinkedHashMap<>();
    private final Deque<SculkKillAttempt> sculkKillAttempts = new ArrayDeque<>();
    private final Map<UUID, Long> playerGenerations = new HashMap<>();
    private long detectorGeneration;
    private int lastPruneTick = Integer.MIN_VALUE;

    public BingoCardFourMechanicsListener(
            JavaPlugin plugin,
            BiPredicate<Player, BingoTask> tracking,
            BiConsumer<Player, BingoTask> completion) {
        this.plugin = plugin;
        this.tracking = tracking;
        this.completion = completion;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onStructureGrown(StructureGrowEvent event) {
        Player player = event.getPlayer();
        if (player == null
                || !event.isFromBonemeal()
                || !tracking.test(player, BingoTask.TREE_WITH_BEE_NEST)) {
            return;
        }

        for (BlockState state : event.getBlocks()) {
            if (isBeeNest(state.getType())) {
                completion.accept(player, BingoTask.TREE_WITH_BEE_NEST);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHoneyBottleConsumed(PlayerItemConsumeEvent event) {
        Player player = event.getPlayer();
        if (!canStartHoneyCure(
                        event.getItem().getType(),
                        player.hasPotionEffect(PotionEffectType.POISON))
                || !tracking.test(player, BingoTask.CURE_POISON_HONEY_BOTTLE)) {
            return;
        }

        UUID playerId = player.getUniqueId();
        AttemptToken token = tokenFor(playerId);
        Bukkit.getScheduler().runTask(plugin, () -> confirmPoisonCured(playerId, token));
    }

    private void confirmPoisonCured(UUID playerId, AttemptToken token) {
        Player player = Bukkit.getPlayer(playerId);
        if (player == null
                || !isCurrent(playerId, token)
                || player.hasPotionEffect(PotionEffectType.POISON)
                || !tracking.test(player, BingoTask.CURE_POISON_HONEY_BOTTLE)) {
            return;
        }

        completion.accept(player, BingoTask.CURE_POISON_HONEY_BOTTLE);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLeverUsed(PlayerInteractEvent event) {
        if (!event.getAction().isRightClick()
                || event.getHand() != EquipmentSlot.HAND
                || event.useInteractedBlock() == Event.Result.DENY) {
            return;
        }

        Block lever = event.getClickedBlock();
        if (lever == null
                || lever.getType() != Material.LEVER
                || !(lever.getBlockData() instanceof Switch leverData)
                || leverData.isPowered()) {
            return;
        }

        Player player = event.getPlayer();
        if (!tracking.test(player, BingoTask.PISTON_PUSH_TWELVE)) return;

        Block piston = lever.getRelative(leverSupportFace(
                leverData.getAttachedFace(), leverData.getFacing()));
        if (!isPiston(piston.getType())) return;

        int tick = Bukkit.getCurrentTick();
        pruneTransientState(tick);
        addWindowAttempt(
                leverAttempts,
                BlockKey.from(piston),
                new TimedAttempt(player.getUniqueId(), tick, tokenFor(player.getUniqueId())));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonExtended(BlockPistonExtendEvent event) {
        int tick = Bukkit.getCurrentTick();
        pruneTransientState(tick);
        List<TimedAttempt> attempts = leverAttempts.remove(BlockKey.from(event.getBlock()));
        int pushedBlockCount = countPushedBlocks(event.getBlocks());
        if (!isPistonPushCorrelation(attempts, tick, pushedBlockCount)) return;

        UUID owner = singleCurrentOwner(attempts, tick, PISTON_WINDOW_TICKS);
        Player player = owner == null ? null : Bukkit.getPlayer(owner);
        if (player != null && tracking.test(player, BingoTask.PISTON_PUSH_TWELVE)) {
            completion.accept(player, BingoTask.PISTON_PUSH_TWELVE);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEnemyKilled(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof Enemy)
                || !(event.getDamageSource().getCausingEntity() instanceof Player player)
                || !tracking.test(player, BingoTask.SCULK_CATALYST_PLAYER_KILL)) {
            return;
        }

        int tick = Bukkit.getCurrentTick();
        pruneTransientState(tick);
        if (sculkKillAttempts.size() >= MAX_TRANSIENT_KEYS) {
            sculkKillAttempts.removeFirst();
        }
        sculkKillAttempts.addLast(new SculkKillAttempt(
                player.getUniqueId(),
                event.getEntity().getLocation().clone().add(0.0, 0.5, 0.0),
                tick,
                tokenFor(player.getUniqueId())));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSculkBloom(SculkBloomEvent event) {
        if (event.getCharge() <= 0) return;

        int tick = Bukkit.getCurrentTick();
        pruneTransientState(tick);
        Location bloom = event.getBlock().getLocation().toCenterLocation();
        List<SculkKillAttempt> matches = new ArrayList<>();
        for (SculkKillAttempt attempt : sculkKillAttempts) {
            if (isCurrent(attempt.playerId(), attempt.token())
                    && isWithinWindow(attempt.tick(), tick, SCULK_WINDOW_TICKS)
                    && isNearby(attempt.location(), bloom, SCULK_BLOOM_DISTANCE_SQUARED)) {
                matches.add(attempt);
            }
        }
        if (matches.isEmpty()) return;

        sculkKillAttempts.removeAll(matches);
        UUID owner = singleDistinctOwner(matches.stream()
                .map(SculkKillAttempt::playerId)
                .toList());
        Player player = owner == null ? null : Bukkit.getPlayer(owner);
        if (player != null && tracking.test(player, BingoTask.SCULK_CATALYST_PLAYER_KILL)) {
            completion.accept(player, BingoTask.SCULK_CATALYST_PLAYER_KILL);
        }
    }

    @Override
    public void resetPlayer(UUID playerId) {
        playerGenerations.merge(playerId, 1L, Long::sum);
        removePlayerAttempts(leverAttempts, playerId);
        sculkKillAttempts.removeIf(attempt -> attempt.playerId().equals(playerId));
    }

    @Override
    public void clear() {
        detectorGeneration++;
        leverAttempts.clear();
        sculkKillAttempts.clear();
        playerGenerations.clear();
        lastPruneTick = Integer.MIN_VALUE;
    }

    private void pruneTransientState(int tick) {
        if (lastPruneTick == tick) return;
        lastPruneTick = tick;
        pruneAttemptMap(leverAttempts, tick);
        sculkKillAttempts.removeIf(attempt ->
                !isCurrent(attempt.playerId(), attempt.token())
                        || !isWithinWindow(attempt.tick(), tick, SCULK_WINDOW_TICKS));
    }

    private void addWindowAttempt(
            Map<BlockKey, List<TimedAttempt>> attemptsByBlock,
            BlockKey block,
            TimedAttempt attempt) {
        if (!attemptsByBlock.containsKey(block) && attemptsByBlock.size() >= MAX_TRANSIENT_KEYS) {
            Iterator<BlockKey> oldest = attemptsByBlock.keySet().iterator();
            if (oldest.hasNext()) {
                oldest.next();
                oldest.remove();
            }
        }

        List<TimedAttempt> attempts = attemptsByBlock.computeIfAbsent(
                block, ignored -> new ArrayList<>());
        attempts.removeIf(existing -> existing.playerId().equals(attempt.playerId()));
        if (attempts.size() < MAX_OWNERS_PER_WINDOW) {
            attempts.add(attempt);
        }
    }

    private UUID singleCurrentOwner(
            List<TimedAttempt> attempts, int tick, int maximumTicks) {
        if (attempts == null) return null;
        return singleDistinctOwner(attempts.stream()
                .filter(attempt -> isCurrent(attempt.playerId(), attempt.token()))
                .filter(attempt -> isWithinWindow(
                        attempt.tick(), tick, maximumTicks))
                .map(TimedAttempt::playerId)
                .toList());
    }

    private static void pruneAttemptMap(
            Map<BlockKey, List<TimedAttempt>> attemptsByBlock, int tick) {
        pruneAttemptMap(attemptsByBlock, tick, PISTON_WINDOW_TICKS);
    }

    private static void pruneAttemptMap(
            Map<BlockKey, List<TimedAttempt>> attemptsByBlock,
            int tick,
            int maximumTicks) {
        attemptsByBlock.values().forEach(attempts -> attempts.removeIf(
                attempt -> !isWithinWindow(
                        attempt.tick(), tick, maximumTicks)));
        attemptsByBlock.values().removeIf(List::isEmpty);
    }

    private static void removePlayerAttempts(
            Map<BlockKey, List<TimedAttempt>> attemptsByBlock, UUID playerId) {
        attemptsByBlock.values().forEach(attempts -> attempts.removeIf(
                attempt -> attempt.playerId().equals(playerId)));
        attemptsByBlock.values().removeIf(List::isEmpty);
    }

    private AttemptToken tokenFor(UUID playerId) {
        return new AttemptToken(
                detectorGeneration, playerGenerations.getOrDefault(playerId, 0L));
    }

    private boolean isCurrent(UUID playerId, AttemptToken token) {
        return token.detectorGeneration() == detectorGeneration
                && token.playerGeneration()
                        == playerGenerations.getOrDefault(playerId, 0L);
    }

    static boolean isBeeNest(Material material) {
        return material == Material.BEE_NEST;
    }

    static boolean canStartHoneyCure(Material consumed, boolean poisoned) {
        return consumed == Material.HONEY_BOTTLE && poisoned;
    }

    static boolean isPiston(Material material) {
        return material == Material.PISTON || material == Material.STICKY_PISTON;
    }

    static BlockFace leverSupportFace(
            FaceAttachable.AttachedFace attachedFace, BlockFace facing) {
        return switch (attachedFace) {
            case FLOOR -> BlockFace.DOWN;
            case CEILING -> BlockFace.UP;
            case WALL -> facing.getOppositeFace();
        };
    }

    static boolean isPistonPushCorrelation(
            List<TimedAttempt> attempts, int eventTick, int pushedBlockCount) {
        return attempts != null
                && attempts.stream().anyMatch(attempt -> isPistonPushCorrelation(
                        attempt.tick(), eventTick, pushedBlockCount));
    }

    static boolean isPistonPushCorrelation(
            int armedTick, int eventTick, int pushedBlockCount) {
        return pushedBlockCount == 12
                && isWithinWindow(armedTick, eventTick, PISTON_WINDOW_TICKS);
    }

    static int countPushedBlocks(List<Block> eventBlocks) {
        return countPushedReactions(eventBlocks.stream()
                .map(Block::getPistonMoveReaction)
                .toList());
    }

    static int countPushedReactions(Collection<PistonMoveReaction> reactions) {
        return (int) reactions.stream()
                .filter(reaction -> reaction != PistonMoveReaction.BREAK)
                .count();
    }

    static boolean isWithinWindow(int armedTick, int eventTick, int maximumTicks) {
        int elapsed = eventTick - armedTick;
        return elapsed >= 0 && elapsed <= maximumTicks;
    }

    static boolean isNearby(
            Location first, Location second, double maximumDistanceSquared) {
        return first.getWorld() != null
                && second.getWorld() != null
                && isNearby(
                        first.getWorld().getUID(),
                        first.getX(),
                        first.getY(),
                        first.getZ(),
                        second.getWorld().getUID(),
                        second.getX(),
                        second.getY(),
                        second.getZ(),
                        maximumDistanceSquared);
    }

    static boolean isNearby(
            UUID firstWorld,
            double firstX,
            double firstY,
            double firstZ,
            UUID secondWorld,
            double secondX,
            double secondY,
            double secondZ,
            double maximumDistanceSquared) {
        if (!firstWorld.equals(secondWorld)) return false;
        double x = firstX - secondX;
        double y = firstY - secondY;
        double z = firstZ - secondZ;
        return x * x + y * y + z * z <= maximumDistanceSquared;
    }

    static UUID singleDistinctOwner(Collection<UUID> owners) {
        UUID owner = null;
        for (UUID candidate : owners) {
            if (owner == null) {
                owner = candidate;
            } else if (!owner.equals(candidate)) {
                return null;
            }
        }
        return owner;
    }

    private record BlockKey(UUID worldId, int x, int y, int z) {
        private static BlockKey from(Block block) {
            return new BlockKey(
                    block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
        }
    }

    private record AttemptToken(long detectorGeneration, long playerGeneration) {}

    private record TimedAttempt(UUID playerId, int tick, AttemptToken token) {}

    private record SculkKillAttempt(
            UUID playerId, Location location, int tick, AttemptToken token) {}
}
