package crabcraft.net.crabUtilities.awards

import net.minecraft.nbt.NbtAccounter
import net.minecraft.nbt.NbtIo
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.OptionalInt

/** Reads a player's current saved experience level from vanilla player data. */
object XpLevelReader {
    @JvmStatic
    fun read(playerDataFile: Path): OptionalInt {
        if (!Files.isRegularFile(playerDataFile)) return OptionalInt.empty()
        try {
            val playerData = NbtIo.readCompressed(playerDataFile, NbtAccounter.defaultQuota())
            val level = playerData.getInt("XpLevel")
            if (level.isEmpty || level.get() < 0) return OptionalInt.empty()
            return OptionalInt.of(level.get())
        } catch (e: IOException) {
            return OptionalInt.empty()
        } catch (e: RuntimeException) {
            return OptionalInt.empty()
        }
    }
}
