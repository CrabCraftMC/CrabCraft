package crabcraft.net.crabUtilities.awards

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtAccounter
import net.minecraft.nbt.NbtIo

/** One-off offline import of independently verified, pre-tracking eating totals. */
class EatingHistoryImport {
    private data class Update(val file: Path, val playerData: CompoundTag)

    companion object {
        @JvmStatic
        @Throws(Exception::class)
        fun main(args: Array<String>) {
            require(args.size in 2..3 && (args.size != 3 || args[2] == "--apply" || args[2] == "--export")) {
                "Usage: <world-directory> <history.json> [--apply|--export]"
            }
            val world = Path.of(args[0]).toAbsolutePath()
            val input = Path.of(args[1]).toAbsolutePath()
            val players = world.resolve("players/data")
            require(Files.isDirectory(players) && Files.isRegularFile(world.resolve("session.lock"))) {
                "Expected a Minecraft 26.2 world with players/data and session.lock"
            }
            FileChannel.open(world.resolve("session.lock"), StandardOpenOption.WRITE).use { channel ->
                channel.tryLock().use { lock ->
                    check(lock != null) { "Stop the Minecraft server before importing or exporting history" }
                    if (args.size == 3 && args[2] == "--export") {
                        export(players, input)
                        return
                    }
                    val rows = JsonParser.parseString(Files.readString(input)).asJsonArray
                    val seen = HashSet<UUID>()
                    val updates = ArrayList<Update>()
                    for (element in rows) {
                        val row = element.asJsonObject
                        val uuid = UUID.fromString(row.get("uuid").asString)
                        require(seen.add(uuid)) { "Duplicate player: $uuid" }
                        val file = players.resolve("$uuid.dat")
                        val playerData = NbtIo.readCompressed(file, NbtAccounter.defaultQuota())
                        val values = playerData.getCompound("BukkitValues").orElseThrow()
                        val progress =
                            EatingAwardTracker.decode(
                                values.getString(EatingAwardTracker.DATA_KEY.toString()).orElseThrow()
                            )
                        require(nonNegativeInteger(row.get("trackingStartedAt")) == progress.trackingStartedAt) {
                            "Historical cutoff does not match tracking start for $uuid"
                        }
                        val scores = row.getAsJsonObject("historicalScores")
                        if (scores == null || scores.isEmpty) continue
                        require(row.has("source") && row.get("source").asString.isNotBlank()) {
                            "Record the verified historical data source for $uuid"
                        }
                        for ((award, value) in scores.entrySet()) {
                            require(award in EatingAwardTracker.FOODS) { "Not an eating award: $award" }
                            val baseline = nonNegativeInteger(value)
                            require(award != "eat_veggie" || baseline >= progress.cakeSlicesAtStart) {
                                "Green Diet history must include its recorded cake slices for $uuid"
                            }
                            Math.addExact(baseline, progress.meals.getOrDefault(award, 0L))
                            progress.historicalScores[award] = baseline
                        }
                        values.putString(EatingAwardTracker.DATA_KEY.toString(), EatingAwardTracker.encode(progress))
                        playerData.put("BukkitValues", values)
                        updates.add(Update(file, playerData))
                        println("$uuid: ${scores.size()} historical totals; tracked meals preserved")
                    }
                    if (args.size < 3) {
                        println("Preview complete: ${updates.size} player saves. No changes written.")
                        return
                    }
                    require(updates.isNotEmpty()) { "No historical totals supplied" }
                    // Validate every input before creating backups or changing any save.
                    val backup = Files.createTempDirectory(world, "eating-history-backup-")
                    for (update in updates) Files.copy(
                        update.file,
                        backup.resolve(update.file.fileName),
                        StandardCopyOption.COPY_ATTRIBUTES,
                    )
                    println("Original saves backed up to $backup")
                    for (update in updates) {
                        val temporary = Files.createTempFile(players, ".eating-history-", ".dat")
                        try {
                            NbtIo.writeCompressed(update.playerData, temporary)
                            Files.move(
                                temporary,
                                update.file,
                                StandardCopyOption.ATOMIC_MOVE,
                                StandardCopyOption.REPLACE_EXISTING,
                            )
                        } finally {
                            Files.deleteIfExists(temporary)
                        }
                    }
                    println("Imported ${updates.size} player saves. Restart the server to publish corrected totals.")
                }
            }
        }

        private fun export(players: Path, output: Path) {
            val rows = JsonArray()
            Files.list(players).use { files ->
                for (file in files.filter { it.fileName.toString().endsWith(".dat") }.sorted().toList()) {
                    val data = NbtIo.readCompressed(file, NbtAccounter.defaultQuota()).getCompound("BukkitValues")
                    if (data.isEmpty) continue
                    val saved = data.get().getString(EatingAwardTracker.DATA_KEY.toString())
                    if (saved.isEmpty) continue
                    val progress = EatingAwardTracker.decode(saved.get())
                    val row = JsonObject()
                    row.addProperty("uuid", file.fileName.toString().replaceFirst(Regex("\\.dat$"), ""))
                    row.addProperty("trackingStartedAt", progress.trackingStartedAt)
                    row.addProperty("cakeSlicesAtStart", progress.cakeSlicesAtStart)
                    row.add("itemUsesAtStart", GsonBuilder().create().toJsonTree(progress.itemUsesAtStart))
                    row.addProperty("source", "")
                    row.add("historicalScores", JsonObject())
                    rows.add(row)
                }
            }
            Files.writeString(
                output,
                GsonBuilder().setPrettyPrinting().create().toJson(rows) + "\n",
                StandardOpenOption.CREATE_NEW,
            )
            println("Exported ${rows.size()} tracking cutoffs. Fill only independently verified historical scores.")
        }

        private fun nonNegativeInteger(value: JsonElement?): Long {
            require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isNumber) {
                "Historical counts and cutoffs must be non-negative integers"
            }
            val number = value.asBigDecimal.longValueExact()
            require(number >= 0) { "Historical counts and cutoffs must be non-negative integers" }
            return number
        }
    }
}
