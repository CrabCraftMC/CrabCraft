package crabcraft.net.crabUtilities.bingo;

import io.papermc.paper.event.block.VaultChangeStateEvent;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import org.bukkit.Bukkit;
import org.bukkit.DyeColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.data.type.Vault;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Fox;
import org.bukkit.entity.Player;
import org.bukkit.entity.Sheep;
import org.bukkit.entity.SulfurCube;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityBreedEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

/** Event-driven detectors for the core Bingo #3 tasks. */
public final class BingoCardThreeCoreListener implements BingoDetector {
    private static final long MAX_PERSISTENT_ATTRIBUTION_MILLIS = 7L * 24 * 60 * 60 * 1_000;

    private final JavaPlugin plugin;
    private final BiPredicate<Player, BingoTask> tracking;
    private final BiConsumer<Player, BingoTask> completion;
    private final NamespacedKey sulfurOwnerKey;
    private final NamespacedKey sulfurFedAtKey;
    private final NamespacedKey foxTotemOwnerKey;
    private final NamespacedKey foxTotemDroppedAtKey;
    private final NamespacedKey foxTotemPickedUpByKey;
    private final Map<UUID, Long> playerGenerations = new HashMap<>();
    private final Map<UUID, Long> playerResetAtMillis = new HashMap<>();
    private long detectorGeneration;
    private long clearedAtMillis;

    public BingoCardThreeCoreListener(
            JavaPlugin plugin,
            BiPredicate<Player, BingoTask> tracking,
            BiConsumer<Player, BingoTask> completion) {
        this.plugin = plugin;
        this.tracking = tracking;
        this.completion = completion;
        this.sulfurOwnerKey = new NamespacedKey(plugin, "bingo_card3_sulfur_owner");
        this.sulfurFedAtKey = new NamespacedKey(plugin, "bingo_card3_sulfur_fed_at");
        this.foxTotemOwnerKey = new NamespacedKey(plugin, "bingo_card3_fox_totem_owner");
        this.foxTotemDroppedAtKey = new NamespacedKey(plugin, "bingo_card3_fox_totem_dropped_at");
        this.foxTotemPickedUpByKey = new NamespacedKey(plugin, "bingo_card3_fox_totem_fox");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSulfurCubeInteracted(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof SulfurCube cube)) return;

        Player player = event.getPlayer();
        ItemStack used = player.getInventory().getItem(event.getHand());
        if (used.getType() == Material.TNT
                && !cube.canExplode()
                && tracking.test(player, BingoTask.SULFUR_CUBE_TNT_IGNITE)) {
            UUID playerId = player.getUniqueId();
            UUID cubeId = cube.getUniqueId();
            AttemptToken token = tokenFor(playerId);
            Bukkit.getScheduler().runTask(
                    plugin, () -> confirmSulfurCubeFed(playerId, cubeId, token));
            return;
        }

        if ((used.getType() == Material.FLINT_AND_STEEL || used.getType() == Material.FIRE_CHARGE)
                && cube.canExplode()
                && cube.getFuseTicks() < 0
                && isSulfurCubeOwnedBy(cube, player.getUniqueId())
                && tracking.test(player, BingoTask.SULFUR_CUBE_TNT_IGNITE)) {
            UUID playerId = player.getUniqueId();
            UUID cubeId = cube.getUniqueId();
            AttemptToken token = tokenFor(playerId);
            Bukkit.getScheduler().runTask(
                    plugin, () -> confirmSulfurCubeIgnited(playerId, cubeId, token));
        }
    }

    private void confirmSulfurCubeFed(UUID playerId, UUID cubeId, AttemptToken token) {
        Player player = Bukkit.getPlayer(playerId);
        Entity entity = Bukkit.getEntity(cubeId);
        if (player == null
                || !(entity instanceof SulfurCube cube)
                || !isCurrent(playerId, token)
                || !hasAbsorbedTnt(cube.getEquipment().getItem(EquipmentSlot.BODY).getType())
                || !tracking.test(player, BingoTask.SULFUR_CUBE_TNT_IGNITE)) {
            return;
        }

        PersistentDataContainer data = cube.getPersistentDataContainer();
        data.set(sulfurOwnerKey, PersistentDataType.STRING, playerId.toString());
        data.set(sulfurFedAtKey, PersistentDataType.LONG, System.currentTimeMillis());
    }

    static boolean hasAbsorbedTnt(Material bodyItem) {
        // Paper updates canExplode() during the later entity tick, after scheduled tasks run.
        // The BODY item is the immediate, reliable proof that the direct feed succeeded.
        return bodyItem == Material.TNT;
    }

    private void confirmSulfurCubeIgnited(UUID playerId, UUID cubeId, AttemptToken token) {
        Player player = Bukkit.getPlayer(playerId);
        Entity entity = Bukkit.getEntity(cubeId);
        if (player == null
                || !(entity instanceof SulfurCube cube)
                || !isCurrent(playerId, token)
                || cube.getFuseTicks() < 0
                || !isSulfurCubeOwnedBy(cube, playerId)
                || !tracking.test(player, BingoTask.SULFUR_CUBE_TNT_IGNITE)) {
            return;
        }

        clearSulfurAttribution(cube);
        completion.accept(player, BingoTask.SULFUR_CUBE_TNT_IGNITE);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSheepBred(EntityBreedEvent event) {
        if (!(event.getBreeder() instanceof Player player)
                || !(event.getEntity() instanceof Sheep child)
                || !(event.getMother() instanceof Sheep mother)
                || !(event.getFather() instanceof Sheep father)) {
            return;
        }

        DyeColor motherColour = mother.getColor();
        DyeColor fatherColour = father.getColor();
        DyeColor childColour = child.getColor();
        if (motherColour != fatherColour
                && childColour != motherColour
                && childColour != fatherColour
                && tracking.test(player, BingoTask.BREED_THIRD_COLOUR_SHEEP)) {
            completion.accept(player, BingoTask.BREED_THIRD_COLOUR_SHEEP);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVaultStateChanged(VaultChangeStateEvent event) {
        Player player = event.getPlayer();
        if (player != null
                && event.getNewState() == Vault.State.UNLOCKING
                && event.getBlock().getBlockData() instanceof Vault vault
                && vault.isOminous()
                && tracking.test(player, BingoTask.UNLOCK_OMINOUS_VAULT)) {
            completion.accept(player, BingoTask.UNLOCK_OMINOUS_VAULT);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTotemDropped(PlayerDropItemEvent event) {
        ItemStack totem = event.getItemDrop().getItemStack();
        if (totem.getType() != Material.TOTEM_OF_UNDYING
                || !tracking.test(event.getPlayer(), BingoTask.FOX_USES_TOTEM)) {
            return;
        }

        UUID playerId = event.getPlayer().getUniqueId();
        long droppedAt = System.currentTimeMillis();
        totem.editPersistentDataContainer(data -> {
            data.set(foxTotemOwnerKey, PersistentDataType.STRING, playerId.toString());
            data.set(foxTotemDroppedAtKey, PersistentDataType.LONG, droppedAt);
            data.remove(foxTotemPickedUpByKey);
        });
        event.getItemDrop().setItemStack(totem);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTotemPickedUp(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Fox fox)) return;

        ItemStack totem = event.getItem().getItemStack();
        PersistentMarker marker = markerFrom(
                totem, foxTotemOwnerKey, foxTotemDroppedAtKey);
        if (totem.getType() != Material.TOTEM_OF_UNDYING
                || marker == null
                || !markerIsCurrent(marker)) {
            return;
        }

        Player player = Bukkit.getPlayer(marker.playerId());
        if (player == null || !tracking.test(player, BingoTask.FOX_USES_TOTEM)) return;

        UUID foxId = fox.getUniqueId();
        AttemptToken token = tokenFor(marker.playerId());
        Bukkit.getScheduler().runTask(
                plugin, () -> confirmFoxTotemPickup(marker, foxId, token));
    }

    private void confirmFoxTotemPickup(
            PersistentMarker expectedMarker, UUID foxId, AttemptToken token) {
        Player player = Bukkit.getPlayer(expectedMarker.playerId());
        Entity entity = Bukkit.getEntity(foxId);
        if (player == null
                || !(entity instanceof Fox fox)
                || !isCurrent(expectedMarker.playerId(), token)
                || !markerIsCurrent(expectedMarker)
                || !tracking.test(player, BingoTask.FOX_USES_TOTEM)) {
            return;
        }

        ItemStack heldTotem = fox.getEquipment().getItemInMainHand();
        PersistentMarker equippedMarker = markerFrom(
                heldTotem, foxTotemOwnerKey, foxTotemDroppedAtKey);
        if (heldTotem.getType() != Material.TOTEM_OF_UNDYING
                || !expectedMarker.equals(equippedMarker)) {
            return;
        }

        heldTotem.editPersistentDataContainer(data -> data.set(
                foxTotemPickedUpByKey,
                PersistentDataType.STRING,
                foxId.toString()));
        fox.getEquipment().setItemInMainHand(heldTotem);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFoxResurrected(EntityResurrectEvent event) {
        if (!(event.getEntity() instanceof Fox fox) || event.getHand() == null) return;

        ItemStack totem = fox.getEquipment().getItem(event.getHand());
        PersistentMarker marker = markerFrom(
                totem, foxTotemOwnerKey, foxTotemDroppedAtKey);
        String pickedUpBy = totem.getPersistentDataContainer()
                .get(foxTotemPickedUpByKey, PersistentDataType.STRING);
        if (totem.getType() != Material.TOTEM_OF_UNDYING
                || marker == null
                || !fox.getUniqueId().toString().equals(pickedUpBy)) {
            return;
        }

        Player player = Bukkit.getPlayer(marker.playerId());
        if (player != null
                && markerIsCurrent(marker)
                && tracking.test(player, BingoTask.FOX_USES_TOTEM)) {
            completion.accept(player, BingoTask.FOX_USES_TOTEM);
        }
    }

    @Override
    public void resetPlayer(UUID playerId) {
        playerGenerations.merge(playerId, 1L, Long::sum);
        playerResetAtMillis.put(playerId, System.currentTimeMillis());
    }

    @Override
    public void clear() {
        detectorGeneration++;
        playerGenerations.clear();
        playerResetAtMillis.clear();
        clearedAtMillis = System.currentTimeMillis();
    }

    private boolean isSulfurCubeOwnedBy(SulfurCube cube, UUID playerId) {
        PersistentDataContainer data = cube.getPersistentDataContainer();
        String owner = data.get(sulfurOwnerKey, PersistentDataType.STRING);
        Long fedAt = data.get(sulfurFedAtKey, PersistentDataType.LONG);
        return owner != null
                && owner.equals(playerId.toString())
                && fedAt != null
                && markerIsCurrent(new PersistentMarker(playerId, fedAt));
    }

    private void clearSulfurAttribution(SulfurCube cube) {
        PersistentDataContainer data = cube.getPersistentDataContainer();
        data.remove(sulfurOwnerKey);
        data.remove(sulfurFedAtKey);
    }

    private PersistentMarker markerFrom(
            ItemStack item, NamespacedKey ownerKey, NamespacedKey timestampKey) {
        String owner = item.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
        Long timestamp = item.getPersistentDataContainer().get(timestampKey, PersistentDataType.LONG);
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

    private AttemptToken tokenFor(UUID playerId) {
        return new AttemptToken(detectorGeneration, playerGenerations.getOrDefault(playerId, 0L));
    }

    private boolean isCurrent(UUID playerId, AttemptToken token) {
        return token.detectorGeneration() == detectorGeneration
                && token.playerGeneration() == playerGenerations.getOrDefault(playerId, 0L);
    }

    private record AttemptToken(long detectorGeneration, long playerGeneration) {}

    private record PersistentMarker(UUID playerId, long timestamp) {}
}
