package crabcraft.net.crabUtilities.awards;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.UUID;

/** One-off offline import of independently verified, pre-tracking eating totals. */
public final class EatingHistoryImport {

    public static void main(String[] args) throws Exception {
        if (args.length < 2 || args.length > 3
                || (args.length == 3 && !args[2].equals("--apply") && !args[2].equals("--export"))) {
            throw new IllegalArgumentException("Usage: <world-directory> <history.json> [--apply|--export]");
        }
        Path world = Path.of(args[0]).toAbsolutePath();
        Path input = Path.of(args[1]).toAbsolutePath();
        Path players = world.resolve("players/data");
        if (!Files.isDirectory(players) || !Files.isRegularFile(world.resolve("session.lock"))) {
            throw new IllegalArgumentException("Expected a Minecraft 26.2 world with players/data and session.lock");
        }
        // Never edit player saves while Minecraft may have newer copies in memory.
        try (var channel = FileChannel.open(world.resolve("session.lock"), StandardOpenOption.WRITE);
             var lock = channel.tryLock()) {
            if (lock == null) throw new IllegalStateException("Stop the Minecraft server before importing or exporting history");
            if (args.length == 3 && args[2].equals("--export")) {
                export(players, input);
                return;
            }
            var rows = JsonParser.parseString(Files.readString(input)).getAsJsonArray();
            var seen = new HashSet<UUID>();
            var updates = new ArrayList<Update>();
            for (JsonElement element : rows) {
                JsonObject row = element.getAsJsonObject();
                UUID uuid = UUID.fromString(row.get("uuid").getAsString());
                if (!seen.add(uuid)) throw new IllegalArgumentException("Duplicate player: " + uuid);
                Path file = players.resolve(uuid + ".dat");
                CompoundTag playerData = NbtIo.readCompressed(file, NbtAccounter.defaultQuota());
                var values = playerData.getCompound("BukkitValues").orElseThrow();
                var progress = EatingAwardTracker.decode(values.getString(EatingAwardTracker.DATA_KEY.toString()).orElseThrow());
                if (nonNegativeInteger(row.get("trackingStartedAt")) != progress.trackingStartedAt) {
                    throw new IllegalArgumentException("Historical cutoff does not match tracking start for " + uuid);
                }
                JsonObject scores = row.getAsJsonObject("historicalScores");
                if (scores == null || scores.isEmpty()) continue;
                if (!row.has("source") || row.get("source").getAsString().isBlank()) {
                    throw new IllegalArgumentException("Record the verified historical data source for " + uuid);
                }
                for (var entry : scores.entrySet()) {
                    if (!EatingAwardTracker.FOODS.containsKey(entry.getKey())) {
                        throw new IllegalArgumentException("Not an eating award: " + entry.getKey());
                    }
                    long baseline = nonNegativeInteger(entry.getValue());
                    if (entry.getKey().equals("eat_veggie") && baseline < progress.cakeSlicesAtStart) {
                        throw new IllegalArgumentException("Green Diet history must include its recorded cake slices for " + uuid);
                    }
                    Math.addExact(baseline, progress.meals.getOrDefault(entry.getKey(), 0L));
                    progress.historicalScores.put(entry.getKey(), baseline);
                }
                values.putString(EatingAwardTracker.DATA_KEY.toString(), EatingAwardTracker.encode(progress));
                playerData.put("BukkitValues", values);
                updates.add(new Update(file, playerData));
                System.out.println(uuid + ": " + scores.size() + " historical totals; tracked meals preserved");
            }
            if (args.length < 3) {
                System.out.println("Preview complete: " + updates.size() + " player saves. No changes written.");
                return;
            }
            if (updates.isEmpty()) throw new IllegalArgumentException("No historical totals supplied");
            // Validate every input before creating backups or changing any save.
            Path backup = Files.createTempDirectory(world, "eating-history-backup-");
            for (Update update : updates) {
                Files.copy(update.file, backup.resolve(update.file.getFileName()), StandardCopyOption.COPY_ATTRIBUTES);
            }
            System.out.println("Original saves backed up to " + backup);
            for (Update update : updates) {
                Path temporary = Files.createTempFile(players, ".eating-history-", ".dat");
                try {
                    NbtIo.writeCompressed(update.playerData, temporary);
                    Files.move(temporary, update.file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } finally {
                    Files.deleteIfExists(temporary);
                }
            }
            System.out.println("Imported " + updates.size() + " player saves. Restart the server to publish corrected totals.");
        }
    }

    private static void export(Path players, Path output) throws Exception {
        JsonArray rows = new JsonArray();
        try (var files = Files.list(players)) {
            for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".dat")).sorted().toList()) {
                var data = NbtIo.readCompressed(file, NbtAccounter.defaultQuota()).getCompound("BukkitValues");
                if (data.isEmpty()) continue;
                var saved = data.get().getString(EatingAwardTracker.DATA_KEY.toString());
                if (saved.isEmpty()) continue;
                var progress = EatingAwardTracker.decode(saved.get());
                JsonObject row = new JsonObject();
                row.addProperty("uuid", file.getFileName().toString().replaceFirst("\\.dat$", ""));
                row.addProperty("trackingStartedAt", progress.trackingStartedAt);
                row.addProperty("cakeSlicesAtStart", progress.cakeSlicesAtStart);
                row.add("itemUsesAtStart", new GsonBuilder().create().toJsonTree(progress.itemUsesAtStart));
                row.addProperty("source", "");
                row.add("historicalScores", new JsonObject());
                rows.add(row);
            }
        }
        Files.writeString(output, new GsonBuilder().setPrettyPrinting().create().toJson(rows) + "\n", StandardOpenOption.CREATE_NEW);
        System.out.println("Exported " + rows.size() + " tracking cutoffs. Fill only independently verified historical scores.");
    }

    private static long nonNegativeInteger(JsonElement value) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("Historical counts and cutoffs must be non-negative integers");
        }
        long number = value.getAsBigDecimal().longValueExact();
        if (number < 0) throw new IllegalArgumentException("Historical counts and cutoffs must be non-negative integers");
        return number;
    }

    private record Update(Path file, CompoundTag playerData) {}
}
