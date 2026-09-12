package crabcraft.net.crabUtilities.bluemap

import com.destroystokyo.paper.event.block.BlockDestroyEvent
import crabcraft.net.crabUtilities.CrabMessages
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Tag
import org.bukkit.block.Block
import org.bukkit.block.sign.Side
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockExplodeEvent
import org.bukkit.event.block.SignChangeEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.event.world.WorldLoadEvent

/** Commits marker mutations at MONITOR, once the cancellation state is known. */
class SignMarkerListener(private val service: SignMarkerService, private val keyword: String) : Listener {
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onSignChangeRecolor(event: SignChangeEvent) {
        if (event.getSide() != Side.FRONT || !isKeyword(event.line(0))) return
        if (event.getPlayer().hasPermission(PERMISSION)) event.line(0, CrabMessages.accent(keyword))
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onSignChange(event: SignChangeEvent) {
        // Back-side edits cannot remove a marker carried by the front.
        if (event.getSide() != Side.FRONT) return
        val block = event.getBlock()
        val player = event.getPlayer()
        if (!isKeyword(event.line(0))) {
            if (service.removeMarker(block)) player.sendMessage(CrabMessages.success("Map marker removed."))
            return
        }
        if (!player.hasPermission(PERMISSION)) { player.sendMessage(CrabMessages.error("You don't have permission to create map markers.")); return }
        val label = StringBuilder()
        val lines = event.lines()
        for (i in 1 until lines.size) {
            val line = plain(lines[i]).trim()
            if (line.isEmpty()) continue
            if (label.isNotEmpty()) label.append(' ')
            label.append(line)
        }
        val markerLabel = if (label.isEmpty()) "Marker" else label.toString()
        if (service.addMarker(block, markerLabel)) player.sendMessage(CrabMessages.success("Map marker \"$markerLabel\" added to BlueMap."))
        else player.sendMessage(CrabMessages.warning("Map marker \"$markerLabel\" saved — it will show once BlueMap has a map for this world."))
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockBreak(event: BlockBreakEvent) { removeIfMarkerSign(event.getBlock()) }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockDestroy(event: BlockDestroyEvent) { removeIfMarkerSign(event.getBlock()) }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockExplode(event: BlockExplodeEvent) { event.blockList().forEach(::removeIfMarkerSign) }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onEntityExplode(event: EntityExplodeEvent) { event.blockList().forEach(::removeIfMarkerSign) }
    @EventHandler
    fun onWorldLoad(event: WorldLoadEvent) { service.worldLoaded(event.getWorld()) }
    private fun removeIfMarkerSign(block: Block) { if (Tag.ALL_SIGNS.isTagged(block.getType())) service.removeMarker(block) }
    private fun isKeyword(line: Component?): Boolean = plain(line).trim().equals(keyword, true)
    companion object {
        const val PERMISSION = "crabutilities.bluemap.marker"
        private fun plain(component: Component?): String = if (component == null) "" else PlainTextComponentSerializer.plainText().serialize(component)
    }
}
