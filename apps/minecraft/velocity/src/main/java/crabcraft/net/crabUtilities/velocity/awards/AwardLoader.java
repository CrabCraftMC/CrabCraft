package crabcraft.net.crabUtilities.velocity.awards;

import com.google.gson.Gson;
import org.slf4j.Logger;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Loads every {@code /awards/&lt;id&gt;.json} from the plugin JAR's classpath
 * into {@link AwardDefinition} objects at plugin start.
 *
 * Supports both JAR-packaged resources (production) and exploded class
 * directories (IDE / test runs).
 */
public final class AwardLoader {

    private static final String RESOURCE_DIR = "/awards";
    private static final Gson GSON = new Gson();

    private AwardLoader() {}

    public static Map<String, AwardDefinition> loadAll(Logger logger) {
        URL dirUrl = AwardLoader.class.getResource(RESOURCE_DIR);
        if (dirUrl == null) {
            logger.error("No {} resource directory found on classpath", RESOURCE_DIR);
            return Collections.emptyMap();
        }

        List<String> fileNames;
        try {
            fileNames = listAwardFileNames(dirUrl);
        } catch (Exception e) {
            logger.error("Failed to enumerate award definitions", e);
            return Collections.emptyMap();
        }

        Map<String, AwardDefinition> out = new HashMap<>();
        for (String fileName : fileNames) {
            String resourcePath = RESOURCE_DIR + "/" + fileName;
            try (InputStream in = AwardLoader.class.getResourceAsStream(resourcePath)) {
                if (in == null) {
                    logger.warn("Award resource missing after enumeration: {}", resourcePath);
                    continue;
                }
                AwardDefinition def;
                try (BufferedReader reader =
                             new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                    def = GSON.fromJson(reader, AwardDefinition.class);
                }
                if (def == null || def.id == null || def.reader == null) {
                    logger.warn("Invalid award definition: {}", fileName);
                    continue;
                }
                out.put(def.id, def);
            } catch (Exception e) {
                logger.warn("Failed to load award definition {}", fileName, e);
            }
        }
        return Collections.unmodifiableMap(out);
    }

    private static List<String> listAwardFileNames(URL dirUrl) throws Exception {
        String protocol = dirUrl.getProtocol();
        if ("file".equals(protocol)) {
            Path dir = Path.of(dirUrl.toURI());
            try (Stream<Path> stream = Files.list(dir)) {
                return stream
                        .filter(p -> p.getFileName().toString().endsWith(".json"))
                        .map(p -> p.getFileName().toString())
                        .sorted()
                        .toList();
            }
        }

        if ("jar".equals(protocol)) {
            URI uri = dirUrl.toURI();
            // Try opening an existing filesystem for the jar first, to avoid
            // clashing with another consumer that already opened one.
            FileSystem fs;
            try {
                fs = FileSystems.getFileSystem(uri);
            } catch (Exception ignored) {
                fs = FileSystems.newFileSystem(uri, Map.of());
            }
            Path dir = fs.getPath(RESOURCE_DIR);
            List<String> names = new ArrayList<>();
            try (Stream<Path> stream = Files.list(dir)) {
                stream.filter(p -> p.getFileName().toString().endsWith(".json"))
                        .forEach(p -> names.add(p.getFileName().toString()));
            }
            Collections.sort(names);
            return names;
        }

        throw new IllegalStateException("Unsupported award resource URL protocol: " + protocol);
    }
}
