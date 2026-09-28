package crabcraft.net.crabUtilities.awards

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.OptionalInt
import net.minecraft.nbt.NbtAccounter
import net.minecraft.nbt.NbtIo

/** Reads a player's current saved experience level from vanilla player data. */
object XpLevelReader {
    @JvmStatic
    fun read(playerDataFile: Path): OptionalInt {
        if (!Files.isRegularFile(playerDataFile)) return OptionalInt.empty()
        return try {
            val level = NbtIo.readCompressed(playerDataFile, NbtAccounter.defaultQuota()).getInt("XpLevel")
            if (level.isEmpty || level.get() < 0) OptionalInt.empty() else OptionalInt.of(level.get())
        } catch (_: IOException) {
            OptionalInt.empty()
        } catch (_: RuntimeException) {
            OptionalInt.empty()
        }
    }
}
