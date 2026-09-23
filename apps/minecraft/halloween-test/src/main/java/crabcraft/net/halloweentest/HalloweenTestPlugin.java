package crabcraft.net.halloweentest;

import crabcraft.net.crabUtilities.halloween.HalloweenManager;
import java.util.Objects;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionType;
import org.bukkit.plugin.java.JavaPlugin;

/** Isolated, loopback-only manual test server using the production detector and command output. */
public final class HalloweenTestPlugin extends JavaPlugin implements Listener {
    private HalloweenManager manager;

    @Override
    public void onEnable() {
        if (!getServer().getIp().equals("127.0.0.1")) {
            throw new IllegalStateException("The Halloween test harness must bind to 127.0.0.1");
        }
        saveDefaultConfig();
        manager = new HalloweenManager(this);
        manager.start();
        Objects.requireNonNull(getCommand("halloween")).setExecutor((sender, command, label, args) -> {
            if (sender instanceof Player player) manager.showProgress(player);
            else sender.sendMessage("Run /halloween in-game.");
            return true;
        });
        getServer().getPluginManager().registerEvents(this, this);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        // Safe only because onEnable enforces a loopback-bound, isolated test server.
        player.setOp(true);
        if (player.hasPlayedBefore()) return;
        player.setGameMode(GameMode.SURVIVAL);
        player.getInventory().setHelmet(new ItemStack(Material.CARVED_PUMPKIN));
        for (Material material : new Material[]{Material.NETHERITE_SWORD, Material.NETHERITE_CHESTPLATE,
                Material.NETHERITE_LEGGINGS, Material.NETHERITE_BOOTS, Material.BELL,
                Material.STONE_PRESSURE_PLATE, Material.ZOMBIE_SPAWN_EGG, Material.SKELETON_SPAWN_EGG,
                Material.SPIDER_SPAWN_EGG, Material.CREEPER_SPAWN_EGG, Material.WITCH_SPAWN_EGG,
                Material.ZOMBIE_VILLAGER_SPAWN_EGG, Material.GOLDEN_APPLE}) {
            player.getInventory().addItem(new ItemStack(material, material.name().endsWith("SPAWN_EGG") ? 16 : 1));
        }
        ItemStack weakness = new ItemStack(Material.SPLASH_POTION, 4);
        PotionMeta potion = (PotionMeta) weakness.getItemMeta();
        potion.setBasePotionType(PotionType.WEAKNESS);
        weakness.setItemMeta(potion);
        player.getInventory().addItem(weakness, new ItemStack(Material.COOKED_BEEF, 64));
        player.getWorld().setTime(18000);
    }

    @Override
    public void onDisable() {
        if (manager != null) manager.shutdown();
    }
}
