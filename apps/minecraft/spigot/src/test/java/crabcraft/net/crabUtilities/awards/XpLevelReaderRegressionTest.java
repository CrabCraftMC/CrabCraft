package crabcraft.net.crabUtilities.awards;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.OptionalInt;

final class XpLevelReaderRegressionTest {

    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("crabcraft-xp-level");
        Path valid = directory.resolve("valid.dat");
        Path zero = directory.resolve("zero.dat");
        Path missingTag = directory.resolve("missing-tag.dat");
        Path negative = directory.resolve("negative.dat");
        Path malformed = directory.resolve("malformed.dat");

        try {
            writeLevel(valid, 42);
            check(XpLevelReader.read(valid).orElse(-1) == 42,
                    "valid XP level was not read");

            writeLevel(zero, 0);
            OptionalInt zeroLevel = XpLevelReader.read(zero);
            check(zeroLevel.isPresent() && zeroLevel.getAsInt() == 0,
                    "level zero was treated as missing");

            NbtIo.writeCompressed(new CompoundTag(), missingTag);
            check(XpLevelReader.read(missingTag).isEmpty(),
                    "missing XpLevel was treated as zero");

            writeLevel(negative, -1);
            check(XpLevelReader.read(negative).isEmpty(),
                    "negative XP level was accepted");

            check(XpLevelReader.read(directory.resolve("absent.dat")).isEmpty(),
                    "missing player data returned a level");

            Files.writeString(malformed, "not compressed NBT");
            check(XpLevelReader.read(malformed).isEmpty(),
                    "malformed player data escaped the reader");
        } finally {
            for (Path file : List.of(valid, zero, missingTag, negative, malformed)) {
                Files.deleteIfExists(file);
            }
            Files.deleteIfExists(directory);
        }
    }

    private static void writeLevel(Path path, int level) throws Exception {
        CompoundTag playerData = new CompoundTag();
        playerData.putInt("XpLevel", level);
        NbtIo.writeCompressed(playerData, path);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
