package crabcraft.net.crabUtilities.awards

import com.google.gson.JsonArray
import com.google.gson.JsonParser
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtAccounter
import net.minecraft.nbt.NbtIo
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.Comparator
import java.util.Random
import java.util.UUID

object EatingHistoryImportRegressionTest {
    @JvmStatic
    @Throws(Exception::class)
    fun main(args: Array<String>) {
        val random = Random(795_316L)
        val world = Files.createTempDirectory("eating-history-regression-")
        try {
            val players = Files.createDirectories(world.resolve("players/data"))
            val lock = Files.createFile(world.resolve("session.lock"))
            val uuid = UUID(random.nextLong(), random.nextLong())
            val save = players.resolve("$uuid.dat")
            val progress = EatingAwardTracker.Progress()
            progress.trackingStartedAt = 1_500_000L + random.nextInt(900_000)
            progress.cakeSlicesAtStart = (1 + random.nextInt(50)).toLong()
            val meals = (1 + random.nextInt(90)).toLong()
            val baseline = progress.cakeSlicesAtStart + 1 + random.nextInt(500)
            progress.meals["eat_veggie"] = meals
            val playerData = CompoundTag()
            playerData.putString("unrelated", UUID.randomUUID().toString())
            val values = CompoundTag()
            values.putString(EatingAwardTracker.DATA_KEY.toString(), EatingAwardTracker.encode(progress))
            playerData.put("BukkitValues", values)
            NbtIo.writeCompressed(playerData, save)
            val original = Files.readAllBytes(save)
            val exported = world.resolve("export.json")
            EatingHistoryImport.main(arrayOf(world.toString(), exported.toString(), "--export"))
            val row = JsonParser.parseString(Files.readString(exported)).asJsonArray[0].asJsonObject
            check(row.getAsJsonObject("historicalScores").isEmpty, "Export invented verified historical totals")
            row.addProperty("source", "Wholly synthetic consumption ledger")
            row.getAsJsonObject("historicalScores").addProperty("eat_veggie", baseline)
            val rows = JsonArray()
            rows.add(row)
            val input = world.resolve("verified.json")
            Files.writeString(input, rows.toString())
            EatingHistoryImport.main(arrayOf(world.toString(), input.toString()))
            check(original.contentEquals(Files.readAllBytes(save)), "Preview wrote player data")
            row.addProperty("trackingStartedAt", progress.trackingStartedAt + 1)
            Files.writeString(input, rows.toString())
            expectFailure { EatingHistoryImport.main(arrayOf(world.toString(), input.toString(), "--apply")) }
            check(original.contentEquals(Files.readAllBytes(save)), "Wrong cutoff modified a save")
            row.addProperty("trackingStartedAt", progress.trackingStartedAt)
            row.getAsJsonObject("historicalScores").addProperty("eat_veggie", 0.5)
            Files.writeString(input, rows.toString())
            expectFailure { EatingHistoryImport.main(arrayOf(world.toString(), input.toString(), "--apply")) }
            row.getAsJsonObject("historicalScores").addProperty("eat_veggie", baseline)
            row.getAsJsonObject("historicalScores").addProperty("place_glass", 0)
            Files.writeString(input, rows.toString())
            expectFailure { EatingHistoryImport.main(arrayOf(world.toString(), input.toString(), "--apply")) }
            check(original.contentEquals(Files.readAllBytes(save)), "Invalid award modified a save")
            row.getAsJsonObject("historicalScores").remove("place_glass")
            Files.writeString(input, rows.toString())
            FileChannel.open(lock, StandardOpenOption.WRITE).use { channel ->
                channel.lock().use {
                    expectFailure { EatingHistoryImport.main(arrayOf(world.toString(), input.toString(), "--apply")) }
                }
            }
            for (attempt in 0 until 2) {
                EatingHistoryImport.main(arrayOf(world.toString(), input.toString(), "--apply"))
                val imported = NbtIo.readCompressed(save, NbtAccounter.defaultQuota())
                val result = EatingAwardTracker.decode(imported.getCompound("BukkitValues").orElseThrow()
                    .getString(EatingAwardTracker.DATA_KEY.toString()).orElseThrow())
                check(result.scores(progress.cakeSlicesAtStart + 1)["eat_veggie"] == baseline + meals + 1,
                    "Import/retry discarded tracked meals or double-counted the baseline")
                check(imported.getString("unrelated") == playerData.getString("unrelated"), "Unrelated player data changed")
            }
            Files.list(world).use { backups ->
                for (backup in backups.filter { it.fileName.toString().startsWith("eating-history-backup-") }.toList()) {
                    check(Files.isRegularFile(backup.resolve(save.fileName)), "Original save backup is missing")
                }
            }
        } finally {
            Files.walk(world).use { paths ->
                for (path in paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path)
            }
        }
    }

    private fun expectFailure(action: ThrowingRunnable) {
        try { action.run() } catch (expected: Exception) { return }
        throw AssertionError("Invalid or unsafe import was accepted")
    }

    private fun check(condition: Boolean, message: String) {
        if (!condition) throw AssertionError(message)
    }

    private fun interface ThrowingRunnable {
        @Throws(Exception::class)
        fun run()
    }
}
