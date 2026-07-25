package crabcraft.net.crabUtilities.awards;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.OptionalInt;

/** Reads a player's current saved experience level from vanilla player data. */
public final class XpLevelReader {

    private XpLevelReader() {}

    public static OptionalInt read(Path playerDataFile) {
        if (!Files.isRegularFile(playerDataFile)) return OptionalInt.empty();

        try {
            CompoundTag playerData = NbtIo.readCompressed(
                    playerDataFile, NbtAccounter.defaultQuota());
            Optional<Integer> level = playerData.getInt("XpLevel");
            if (level.isEmpty() || level.get() < 0) return OptionalInt.empty();
            return OptionalInt.of(level.get());
        } catch (IOException | RuntimeException e) {
            return OptionalInt.empty();
        }
    }
}
