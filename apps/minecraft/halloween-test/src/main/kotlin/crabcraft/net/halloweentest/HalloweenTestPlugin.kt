package crabcraft.net.halloweentest

import crabcraft.net.crabUtilities.halloween.HalloweenManager
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.PotionMeta
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.potion.PotionType

/** Isolated, loopback-only manual test server using the production detector and command output. */
class HalloweenTestPlugin : JavaPlugin(), Listener {
    private var manager: HalloweenManager? = null

    override fun onEnable() {
        check(server.ip == "127.0.0.1") { "The Halloween test harness must bind to 127.0.0.1" }
        saveDefaultConfig()
        val tracker = HalloweenManager(this)
        manager = tracker
        tracker.start()
        requireNotNull(getCommand("halloween")).setExecutor { sender, _, _, _ ->
            if (sender is Player) tracker.showProgress(sender) else sender.sendMessage("Run /halloween in-game.")
            true
        }
        server.pluginManager.registerEvents(this, this)
    }

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        val player = event.player
        // Safe only because onEnable enforces a loopback-bound, isolated test server.
        player.setOp(true)
        if (player.hasPlayedBefore()) return
        player.gameMode = GameMode.SURVIVAL
        player.inventory.setHelmet(ItemStack(Material.CARVED_PUMPKIN))
        for (material in
            listOf(
                Material.NETHERITE_SWORD,
                Material.NETHERITE_CHESTPLATE,
                Material.NETHERITE_LEGGINGS,
                Material.NETHERITE_BOOTS,
                Material.BELL,
                Material.STONE_PRESSURE_PLATE,
                Material.ZOMBIE_SPAWN_EGG,
                Material.SKELETON_SPAWN_EGG,
                Material.SPIDER_SPAWN_EGG,
                Material.CREEPER_SPAWN_EGG,
                Material.WITCH_SPAWN_EGG,
                Material.ZOMBIE_VILLAGER_SPAWN_EGG,
                Material.GOLDEN_APPLE,
            )) {
            player.inventory.addItem(ItemStack(material, if (material.name.endsWith("SPAWN_EGG")) 16 else 1))
        }
        val weakness = ItemStack(Material.SPLASH_POTION, 4)
        val potion = weakness.itemMeta as PotionMeta
        potion.basePotionType = PotionType.WEAKNESS
        weakness.itemMeta = potion
        player.inventory.addItem(weakness, ItemStack(Material.COOKED_BEEF, 64))
        player.world.time = 18000L
    }

    override fun onDisable() {
        manager?.shutdown()
    }
}
