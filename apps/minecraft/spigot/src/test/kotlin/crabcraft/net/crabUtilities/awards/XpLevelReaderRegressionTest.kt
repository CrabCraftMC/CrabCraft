package crabcraft.net.crabUtilities.awards

import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtIo
import java.nio.file.Files
import java.nio.file.Path

internal object XpLevelReaderRegressionTest {
    @JvmStatic
    @Throws(Exception::class)
    fun main(args: Array<String>) {
        val directory = Files.createTempDirectory("crabcraft-xp-level")
        val valid = directory.resolve("valid.dat")
        val zero = directory.resolve("zero.dat")
        val missingTag = directory.resolve("missing-tag.dat")
        val negative = directory.resolve("negative.dat")
        val malformed = directory.resolve("malformed.dat")
        try {
            writeLevel(valid, 42)
            check(XpLevelReader.read(valid).orElse(-1) == 42, "valid XP level was not read")
            writeLevel(zero, 0)
            val zeroLevel = XpLevelReader.read(zero)
            check(zeroLevel.isPresent && zeroLevel.asInt == 0, "level zero was treated as missing")
            NbtIo.writeCompressed(CompoundTag(), missingTag)
            check(XpLevelReader.read(missingTag).isEmpty, "missing XpLevel was treated as zero")
            writeLevel(negative, -1)
            check(XpLevelReader.read(negative).isEmpty, "negative XP level was accepted")
            check(XpLevelReader.read(directory.resolve("absent.dat")).isEmpty, "missing player data returned a level")
            Files.writeString(malformed, "not compressed NBT")
            check(XpLevelReader.read(malformed).isEmpty, "malformed player data escaped the reader")
        } finally {
            for (file in listOf(valid, zero, missingTag, negative, malformed)) Files.deleteIfExists(file)
            Files.deleteIfExists(directory)
        }
    }

    private fun writeLevel(path: Path, level: Int) {
        val playerData = CompoundTag()
        playerData.putInt("XpLevel", level)
        NbtIo.writeCompressed(playerData, path)
    }

    private fun check(condition: Boolean, message: String) {
        if (!condition) throw AssertionError(message)
    }
}
