package crabcraft.net.crabUtilities.velocity;

import org.slf4j.Logger;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class JoinedPlayersStore {

    private static final String FILE_NAME = "joined-players.txt";

    private final Path filePath;
    private final Logger logger;
    private final Set<UUID> joinedPlayers = new HashSet<>();

    public JoinedPlayersStore(Path dataDirectory, Logger logger) {
        this.filePath = dataDirectory.resolve(FILE_NAME);
        this.logger = logger;
        load();
    }

    private void load() {
        if (!Files.exists(filePath)) {
            logger.info("No {} found, starting with empty joined players set.", FILE_NAME);
            return;
        }

        try {
            for (String line : Files.readAllLines(filePath)) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) {
                    try {
                        joinedPlayers.add(UUID.fromString(trimmed));
                    } catch (IllegalArgumentException ignored) {
                    }
                }
            }
            logger.info("Loaded {} joined players from {}.", joinedPlayers.size(), FILE_NAME);
        } catch (IOException e) {
            logger.error("Failed to load " + FILE_NAME, e);
        }
    }

    public boolean isNew(UUID uuid) {
        return !joinedPlayers.contains(uuid);
    }

    public void markJoined(UUID uuid) {
        if (!joinedPlayers.add(uuid)) return;

        try (BufferedWriter writer = Files.newBufferedWriter(filePath,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            writer.write(uuid.toString());
            writer.newLine();
        } catch (IOException e) {
            logger.error("Failed to append to " + FILE_NAME, e);
        }
    }
}
