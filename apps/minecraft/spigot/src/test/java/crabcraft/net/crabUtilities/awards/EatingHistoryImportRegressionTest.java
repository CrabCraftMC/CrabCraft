package crabcraft.net.crabUtilities.awards;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Random;
import java.util.UUID;

public final class EatingHistoryImportRegressionTest {
    public static void main(String[] args) throws Exception {
        Random random = new Random(795_316L);
        var world = Files.createTempDirectory("eating-history-regression-");
        try {
            var players = Files.createDirectories(world.resolve("players/data"));
            var lock = Files.createFile(world.resolve("session.lock"));
            UUID uuid = new UUID(random.nextLong(), random.nextLong());
            var save = players.resolve(uuid + ".dat");
            var progress = new EatingAwardTracker.Progress();
            progress.trackingStartedAt = 1_500_000L + random.nextInt(900_000);
            progress.cakeSlicesAtStart = 1 + random.nextInt(50);
            long meals = 1 + random.nextInt(90);
            long baseline = progress.cakeSlicesAtStart + 1 + random.nextInt(500);
            progress.meals.put("eat_veggie", meals);
            CompoundTag playerData = new CompoundTag();
            playerData.putString("unrelated", UUID.randomUUID().toString());
            CompoundTag values = new CompoundTag();
            values.putString(EatingAwardTracker.DATA_KEY.toString(), EatingAwardTracker.encode(progress));
            playerData.put("BukkitValues", values);
            NbtIo.writeCompressed(playerData, save);
            byte[] original = Files.readAllBytes(save);

            var exported = world.resolve("export.json");
            EatingHistoryImport.main(new String[]{world.toString(), exported.toString(), "--export"});
            var row = com.google.gson.JsonParser.parseString(Files.readString(exported)).getAsJsonArray().get(0).getAsJsonObject();
            check(row.getAsJsonObject("historicalScores").isEmpty(), "Export invented verified historical totals");
            row.addProperty("source", "Wholly synthetic consumption ledger");
            row.getAsJsonObject("historicalScores").addProperty("eat_veggie", baseline);
            var rows = new JsonArray();
            rows.add(row);
            var input = world.resolve("verified.json");
            Files.writeString(input, rows.toString());
            EatingHistoryImport.main(new String[]{world.toString(), input.toString()});
            check(Arrays.equals(original, Files.readAllBytes(save)), "Preview wrote player data");

            row.addProperty("trackingStartedAt", progress.trackingStartedAt + 1);
            Files.writeString(input, rows.toString());
            expectFailure(() -> EatingHistoryImport.main(new String[]{world.toString(), input.toString(), "--apply"}));
            check(Arrays.equals(original, Files.readAllBytes(save)), "Wrong cutoff modified a save");
            row.addProperty("trackingStartedAt", progress.trackingStartedAt);
            row.getAsJsonObject("historicalScores").addProperty("eat_veggie", 0.5);
            Files.writeString(input, rows.toString());
            expectFailure(() -> EatingHistoryImport.main(new String[]{world.toString(), input.toString(), "--apply"}));
            row.getAsJsonObject("historicalScores").addProperty("eat_veggie", baseline);
            row.getAsJsonObject("historicalScores").addProperty("place_glass", 0);
            Files.writeString(input, rows.toString());
            expectFailure(() -> EatingHistoryImport.main(new String[]{world.toString(), input.toString(), "--apply"}));
            check(Arrays.equals(original, Files.readAllBytes(save)), "Invalid award modified a save");
            row.getAsJsonObject("historicalScores").remove("place_glass");
            Files.writeString(input, rows.toString());
            try (var channel = FileChannel.open(lock, StandardOpenOption.WRITE); var held = channel.lock()) {
                expectFailure(() -> EatingHistoryImport.main(new String[]{world.toString(), input.toString(), "--apply"}));
            }
            for (int attempt = 0; attempt < 2; attempt++) {
                EatingHistoryImport.main(new String[]{world.toString(), input.toString(), "--apply"});
                var imported = NbtIo.readCompressed(save, NbtAccounter.defaultQuota());
                var result = EatingAwardTracker.decode(imported.getCompound("BukkitValues").orElseThrow()
                        .getString(EatingAwardTracker.DATA_KEY.toString()).orElseThrow());
                check(result.scores(progress.cakeSlicesAtStart + 1).get("eat_veggie") == baseline + meals + 1,
                        "Import/retry discarded tracked meals or double-counted the baseline");
                check(imported.getString("unrelated").equals(playerData.getString("unrelated")), "Unrelated player data changed");
            }
            try (var backups = Files.list(world)) {
                for (var backup : backups.filter(path -> path.getFileName().toString().startsWith("eating-history-backup-")).toList()) {
                    check(Files.isRegularFile(backup.resolve(save.getFileName())), "Original save backup is missing");
                }
            }
        } finally {
            try (var paths = Files.walk(world)) {
                for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    private static void expectFailure(ThrowingRunnable action) throws Exception {
        try { action.run(); } catch (Exception expected) { return; }
        throw new AssertionError("Invalid or unsafe import was accepted");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private interface ThrowingRunnable { void run() throws Exception; }
}
