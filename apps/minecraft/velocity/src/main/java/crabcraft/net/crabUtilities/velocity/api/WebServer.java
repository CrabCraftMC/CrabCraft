package crabcraft.net.crabUtilities.velocity.api;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import crabcraft.net.crabUtilities.velocity.CrabUtilitiesVelocity;
import crabcraft.net.crabUtilities.velocity.NicknameCache;

import java.io.IOException;
import java.net.URI;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

public class WebServer {

    private static final Gson GSON = new Gson();

    private static final int RATE_LIMIT = 60;
    private static final long RATE_WINDOW_MS = 60_000;

    private static final Pattern USERNAME = Pattern.compile("^[a-zA-Z0-9_]{3,16}$");
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final Pattern AWARD_ID_PATTERN = Pattern.compile("^[a-z0-9_]+$");

    private static final String ERROR_SCHEMA =
            "{\"type\":\"object\",\"properties\":{\"error\":{\"type\":\"string\"}}}";

    private static final String PLAYER_SCHEMA = "{"
            + "\"type\":\"object\","
            + "\"properties\":{"
            + "\"username\":{\"type\":\"string\",\"description\":\"Minecraft username\"},"
            + "\"uuid\":{\"type\":\"string\",\"format\":\"uuid\",\"description\":\"Player UUID\"},"
            + "\"nickname\":{\"type\":\"string\",\"nullable\":true,\"description\":\"Plain-text display name\"},"
            + "\"nickname_raw\":{\"type\":\"string\",\"nullable\":true,\"description\":\"Raw (formatted) display name\"},"
            + "\"ping\":{\"type\":\"integer\",\"description\":\"Player latency in milliseconds\"},"
            + "\"server\":{\"type\":\"string\",\"nullable\":true,\"description\":\"Backend server the player is on\"}"
            + "}"
            + "}";

    private static final String COMMON_ERRORS =
            "\"405\":{\"description\":\"Method not allowed\",\"content\":{\"application/json\":{\"schema\":" + ERROR_SCHEMA + "}}},"
            + "\"429\":{\"description\":\"Rate limit exceeded\",\"content\":{\"application/json\":{\"schema\":" + ERROR_SCHEMA + "}}}";

    private static final String OPENAPI_JSON = "{"
            + "\"openapi\":\"3.0.3\","
            + "\"info\":{"
            + "\"title\":\"CrabCraft API\","
            + "\"version\":\"1.1.0\","
            + "\"description\":\"Live player data from the CrabCraft proxy.\""
            + "},"
            + "\"paths\":{"

            + "\"/ping\":{"
            + "\"get\":{"
            + "\"summary\":\"Health check\","
            + "\"description\":\"Simple health check to verify the API is running.\","
            + "\"operationId\":\"ping\","
            + "\"responses\":{"
            + "\"200\":{"
            + "\"description\":\"API is healthy\","
            + "\"content\":{\"application/json\":{\"schema\":{\"type\":\"object\",\"properties\":{\"status\":{\"type\":\"string\",\"example\":\"ok\"}}}}}"
            + "},"
            + COMMON_ERRORS
            + "}"
            + "}"
            + "},"

            + "\"/status\":{"
            + "\"get\":{"
            + "\"summary\":\"Server status\","
            + "\"description\":\"Returns an overview of the proxy server including player count and version.\","
            + "\"operationId\":\"getStatus\","
            + "\"responses\":{"
            + "\"200\":{"
            + "\"description\":\"Successful response\","
            + "\"content\":{\"application/json\":{\"schema\":{\"type\":\"object\",\"properties\":{"
            + "\"online\":{\"type\":\"boolean\",\"description\":\"Whether the proxy is online\"},"
            + "\"players\":{\"type\":\"object\",\"properties\":{"
            + "\"online\":{\"type\":\"integer\",\"description\":\"Current player count\"},"
            + "\"max\":{\"type\":\"integer\",\"description\":\"Maximum player slots\"}"
            + "}},"
            + "\"version\":{\"type\":\"string\",\"description\":\"Proxy server version\"}"
            + "}}}}"
            + "},"
            + COMMON_ERRORS
            + "}"
            + "}"
            + "},"

            + "\"/servers\":{"
            + "\"get\":{"
            + "\"summary\":\"List backend servers\","
            + "\"description\":\"Returns all registered backend servers with their player counts.\","
            + "\"operationId\":\"getServers\","
            + "\"responses\":{"
            + "\"200\":{"
            + "\"description\":\"Successful response\","
            + "\"content\":{\"application/json\":{\"schema\":{\"type\":\"object\",\"properties\":{"
            + "\"servers\":{\"type\":\"array\",\"items\":{\"type\":\"object\",\"properties\":{"
            + "\"name\":{\"type\":\"string\",\"description\":\"Server name\"},"
            + "\"players\":{\"type\":\"integer\",\"description\":\"Number of players on this server\"}"
            + "}}}"
            + "}}}}"
            + "},"
            + COMMON_ERRORS
            + "}"
            + "}"
            + "},"

            + "\"/players\":{"
            + "\"get\":{"
            + "\"summary\":\"List online players\","
            + "\"description\":\"Returns all players currently connected to the proxy.\","
            + "\"operationId\":\"getPlayers\","
            + "\"responses\":{"
            + "\"200\":{"
            + "\"description\":\"Successful response\","
            + "\"content\":{\"application/json\":{\"schema\":{\"type\":\"object\",\"properties\":{"
            + "\"count\":{\"type\":\"integer\",\"description\":\"Number of online players\"},"
            + "\"players\":{\"type\":\"array\",\"items\":" + PLAYER_SCHEMA + "}"
            + "}}}}"
            + "},"
            + COMMON_ERRORS
            + "}"
            + "}"
            + "},"

            + "\"/players/{name}\":{"
            + "\"get\":{"
            + "\"summary\":\"Look up a player\","
            + "\"description\":\"Returns details for a specific online player by username.\","
            + "\"operationId\":\"getPlayer\","
            + "\"parameters\":[{\"name\":\"name\",\"in\":\"path\",\"required\":true,\"schema\":{\"type\":\"string\"},\"description\":\"Player username (3-16 alphanumeric characters or underscores)\"}],"
            + "\"responses\":{"
            + "\"200\":{"
            + "\"description\":\"Player found\","
            + "\"content\":{\"application/json\":{\"schema\":" + PLAYER_SCHEMA + "}}"
            + "},"
            + "\"400\":{\"description\":\"Invalid username format\",\"content\":{\"application/json\":{\"schema\":" + ERROR_SCHEMA + "}}},"
            + "\"404\":{\"description\":\"Player not online\",\"content\":{\"application/json\":{\"schema\":" + ERROR_SCHEMA + "}}},"
            + COMMON_ERRORS
            + "}"
            + "}"
            + "}"
            + ","

            + "\"/awards\":{"
            + "\"get\":{"
            + "\"summary\":\"List all awards\","
            + "\"description\":\"Returns all enabled awards with the #1 holder for each and available servers.\","
            + "\"operationId\":\"getAwards\","
            + "\"parameters\":["
            + "{\"name\":\"season\",\"in\":\"query\",\"schema\":{\"type\":\"string\"},\"description\":\"Season ID (defaults to current)\"},"
            + "{\"name\":\"server\",\"in\":\"query\",\"schema\":{\"type\":\"string\"},\"description\":\"Server ID (defaults to aggregate)\"}"
            + "],"
            + "\"responses\":{"
            + "\"200\":{\"description\":\"All awards with leaders\"},"
            + "\"404\":{\"description\":\"No current season\",\"content\":{\"application/json\":{\"schema\":" + ERROR_SCHEMA + "}}},"
            + COMMON_ERRORS
            + "}"
            + "}"
            + "},"

            + "\"/awards/crowns\":{"
            + "\"get\":{"
            + "\"summary\":\"Crown leaderboard\","
            + "\"description\":\"Hall of Fame ranking by crown score (gold*4 + silver*2 + bronze).\","
            + "\"operationId\":\"getCrownLeaderboard\","
            + "\"parameters\":["
            + "{\"name\":\"season\",\"in\":\"query\",\"schema\":{\"type\":\"string\"},\"description\":\"Season ID (defaults to current)\"},"
            + "{\"name\":\"server\",\"in\":\"query\",\"schema\":{\"type\":\"string\"},\"description\":\"Server ID (defaults to aggregate)\"},"
            + "{\"name\":\"limit\",\"in\":\"query\",\"schema\":{\"type\":\"integer\",\"default\":100,\"maximum\":100},\"description\":\"Max entries\"},"
            + "{\"name\":\"offset\",\"in\":\"query\",\"schema\":{\"type\":\"integer\",\"default\":0},\"description\":\"Number of entries to skip\"}"
            + "],"
            + "\"responses\":{"
            + "\"200\":{\"description\":\"Crown score leaderboard with total, offset, limit\"},"
            + "\"404\":{\"description\":\"No current season\",\"content\":{\"application/json\":{\"schema\":" + ERROR_SCHEMA + "}}},"
            + COMMON_ERRORS
            + "}"
            + "}"
            + "},"

            + "\"/awards/player/{uuid}\":{"
            + "\"get\":{"
            + "\"summary\":\"Player awards\","
            + "\"description\":\"Returns a player's scores and rank across all awards plus crown summary.\","
            + "\"operationId\":\"getPlayerAwards\","
            + "\"parameters\":["
            + "{\"name\":\"uuid\",\"in\":\"path\",\"required\":true,\"schema\":{\"type\":\"string\",\"format\":\"uuid\"},\"description\":\"Player UUID\"},"
            + "{\"name\":\"season\",\"in\":\"query\",\"schema\":{\"type\":\"string\"},\"description\":\"Season ID (defaults to current)\"},"
            + "{\"name\":\"server\",\"in\":\"query\",\"schema\":{\"type\":\"string\"},\"description\":\"Server ID (defaults to aggregate)\"}"
            + "],"
            + "\"responses\":{"
            + "\"200\":{\"description\":\"Player award scores and crown data\"},"
            + "\"400\":{\"description\":\"Invalid UUID format\",\"content\":{\"application/json\":{\"schema\":" + ERROR_SCHEMA + "}}},"
            + "\"404\":{\"description\":\"Player not found or no season\",\"content\":{\"application/json\":{\"schema\":" + ERROR_SCHEMA + "}}},"
            + COMMON_ERRORS
            + "}"
            + "}"
            + "},"

            + "\"/awards/{id}\":{"
            + "\"get\":{"
            + "\"summary\":\"Award leaderboard\","
            + "\"description\":\"Returns the leaderboard for a single award.\","
            + "\"operationId\":\"getAwardLeaderboard\","
            + "\"parameters\":["
            + "{\"name\":\"id\",\"in\":\"path\",\"required\":true,\"schema\":{\"type\":\"string\"},\"description\":\"Award ID (e.g. aviate, kill_any)\"},"
            + "{\"name\":\"season\",\"in\":\"query\",\"schema\":{\"type\":\"string\"},\"description\":\"Season ID (defaults to current)\"},"
            + "{\"name\":\"server\",\"in\":\"query\",\"schema\":{\"type\":\"string\"},\"description\":\"Server ID (defaults to aggregate)\"},"
            + "{\"name\":\"limit\",\"in\":\"query\",\"schema\":{\"type\":\"integer\",\"default\":100,\"maximum\":100},\"description\":\"Max entries\"},"
            + "{\"name\":\"offset\",\"in\":\"query\",\"schema\":{\"type\":\"integer\",\"default\":0},\"description\":\"Number of entries to skip\"}"
            + "],"
            + "\"responses\":{"
            + "\"200\":{\"description\":\"Award metadata and leaderboard with total, offset, limit\"},"
            + "\"400\":{\"description\":\"Invalid award ID format\",\"content\":{\"application/json\":{\"schema\":" + ERROR_SCHEMA + "}}},"
            + "\"404\":{\"description\":\"Award not found or no season\",\"content\":{\"application/json\":{\"schema\":" + ERROR_SCHEMA + "}}},"
            + COMMON_ERRORS
            + "}"
            + "}"
            + "}"
            + ","

            + "\"/advancements/leaderboard\":{"
            + "\"get\":{"
            + "\"summary\":\"Advancement leaderboard\","
            + "\"description\":\"Global leaderboard ranked by advancement completion count.\","
            + "\"operationId\":\"getAdvancementLeaderboard\","
            + "\"parameters\":["
            + "{\"name\":\"season\",\"in\":\"query\",\"schema\":{\"type\":\"string\"},\"description\":\"Season ID (defaults to current)\"},"
            + "{\"name\":\"server\",\"in\":\"query\",\"schema\":{\"type\":\"string\"},\"description\":\"Server ID (defaults to aggregate)\"},"
            + "{\"name\":\"limit\",\"in\":\"query\",\"schema\":{\"type\":\"integer\",\"default\":100,\"maximum\":100},\"description\":\"Max entries\"},"
            + "{\"name\":\"offset\",\"in\":\"query\",\"schema\":{\"type\":\"integer\",\"default\":0},\"description\":\"Number of entries to skip\"}"
            + "],"
            + "\"responses\":{"
            + "\"200\":{\"description\":\"Advancement completion leaderboard with pagination\"},"
            + "\"404\":{\"description\":\"No current season\",\"content\":{\"application/json\":{\"schema\":" + ERROR_SCHEMA + "}}},"
            + COMMON_ERRORS
            + "}"
            + "}"
            + "},"

            + "\"/advancements/player/{uuid}\":{"
            + "\"get\":{"
            + "\"summary\":\"Player advancements\","
            + "\"description\":\"Returns all advancements for a player with completion status.\","
            + "\"operationId\":\"getPlayerAdvancements\","
            + "\"parameters\":["
            + "{\"name\":\"uuid\",\"in\":\"path\",\"required\":true,\"schema\":{\"type\":\"string\",\"format\":\"uuid\"},\"description\":\"Player UUID\"},"
            + "{\"name\":\"season\",\"in\":\"query\",\"schema\":{\"type\":\"string\"},\"description\":\"Season ID (defaults to current)\"},"
            + "{\"name\":\"server\",\"in\":\"query\",\"schema\":{\"type\":\"string\"},\"description\":\"Server ID (defaults to aggregate)\"}"
            + "],"
            + "\"responses\":{"
            + "\"200\":{\"description\":\"Player advancement data with completion counts\"},"
            + "\"400\":{\"description\":\"Invalid UUID format\",\"content\":{\"application/json\":{\"schema\":" + ERROR_SCHEMA + "}}},"
            + "\"404\":{\"description\":\"Player not found or no season\",\"content\":{\"application/json\":{\"schema\":" + ERROR_SCHEMA + "}}},"
            + COMMON_ERRORS
            + "}"
            + "}"
            + "}"

            + "}"
            + "}";

    private static final String SCALAR_HTML = "<!doctype html>\n"
            + "<html>\n"
            + "  <head>\n"
            + "    <meta charset=\"UTF-8\" />\n"
            + "    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\" />\n"
            + "    <title>CrabCraft API Docs</title>\n"
            + "    <style>html,body{margin:0;padding:0;}</style>\n"
            + "  </head>\n"
            + "  <body>\n"
            + "    <script id=\"api-reference\" type=\"application/json\">\n"
            + OPENAPI_JSON + "\n"
            + "    </script>\n"
            + "    <script src=\"https://cdn.jsdelivr.net/npm/@scalar/api-reference\"></script>\n"
            + "    <noscript>JavaScript is required to render the API documentation. You can still access /openapi.json and /players directly.</noscript>\n"
            + "  </body>\n"
            + "</html>\n";

    private final CrabUtilitiesVelocity plugin;
    private final int port;
    private HttpServer httpServer;

    // [count, windowStartMs] per IP
    private final ConcurrentHashMap<String, long[]> rateLimits = new ConcurrentHashMap<>();

    public WebServer(CrabUtilitiesVelocity plugin, int port) {
        this.plugin = plugin;
        this.port = port;
    }

    public boolean isRunning() {
        return httpServer != null;
    }

    private boolean isRateLimited(String ip) {
        long now = System.currentTimeMillis();
        long[] window = rateLimits.computeIfAbsent(ip, k -> new long[]{0, now});
        synchronized (window) {
            if (now - window[1] > RATE_WINDOW_MS) {
                window[0] = 0;
                window[1] = now;
            }
            if (window[0] >= RATE_LIMIT) return true;
            window[0]++;
            return false;
        }
    }

    private JsonObject buildPlayerJson(Player player) {
        NicknameCache cache = plugin.getNicknameCache();
        JsonObject obj = new JsonObject();
        obj.addProperty("username", player.getUsername());
        obj.addProperty("uuid", player.getUniqueId().toString());
        obj.addProperty("nickname", cache.getPlainNickname(player.getUniqueId()));
        obj.addProperty("nickname_raw", cache.getRawNickname(player.getUniqueId()));
        obj.addProperty("ping", player.getPing());
        obj.addProperty("server", player.getCurrentServer()
                .map(conn -> conn.getServerInfo().getName())
                .orElse(null));
        return obj;
    }

    private static void sendJson(HttpExchange exchange, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }

    private static void sendError(HttpExchange exchange, int code, String message) throws IOException {
        JsonObject err = new JsonObject();
        err.addProperty("error", message);
        byte[] body = GSON.toJson(err).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(code, body.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }

    private static java.util.Map<String, String> parseQuery(URI uri) {
        java.util.Map<String, String> params = new java.util.HashMap<>();
        String query = uri.getRawQuery();
        if (query == null || query.isEmpty()) return params;
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                params.put(
                    java.net.URLDecoder.decode(pair.substring(0, eq), java.nio.charset.StandardCharsets.UTF_8),
                    java.net.URLDecoder.decode(pair.substring(eq + 1), java.nio.charset.StandardCharsets.UTF_8)
                );
            }
        }
        return params;
    }

    public boolean start() {
        if (httpServer != null) {
            plugin.getLogger().warn("Web API is already running.");
            return false;
        }
        try {
            httpServer = HttpServer.create(new InetSocketAddress(port), 0);

            httpServer.createContext("/", exchange -> {
                if (!"GET".equals(exchange.getRequestMethod())) {
                    sendError(exchange, 405, "method not allowed");
                    return;
                }
                String path = exchange.getRequestURI().getPath();
                if (!"/".equals(path)) {
                    sendError(exchange, 404, "not found");
                    return;
                }
                byte[] body = SCALAR_HTML.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(body);
                }
            });

            httpServer.createContext("/openapi.json", exchange -> {
                if (!"GET".equals(exchange.getRequestMethod())) {
                    sendError(exchange, 405, "method not allowed");
                    return;
                }
                byte[] body = OPENAPI_JSON.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(body);
                }
            });

            httpServer.createContext("/ping", exchange -> {
                if (!"GET".equals(exchange.getRequestMethod())) {
                    sendError(exchange, 405, "method not allowed");
                    return;
                }
                if (isRateLimited(exchange.getRemoteAddress().getHostString())) {
                    sendError(exchange, 429, "rate limit exceeded");
                    return;
                }
                sendJson(exchange, "{\"status\":\"ok\"}");
            });

            httpServer.createContext("/status", exchange -> {
                if (!"GET".equals(exchange.getRequestMethod())) {
                    sendError(exchange, 405, "method not allowed");
                    return;
                }
                if (isRateLimited(exchange.getRemoteAddress().getHostString())) {
                    sendError(exchange, 429, "rate limit exceeded");
                    return;
                }
                JsonObject players = new JsonObject();
                players.addProperty("online", plugin.getServer().getPlayerCount());
                players.addProperty("max", plugin.getServer().getConfiguration().getShowMaxPlayers());

                JsonObject response = new JsonObject();
                response.addProperty("online", true);
                response.add("players", players);
                response.addProperty("version", plugin.getServer().getVersion().toString());

                sendJson(exchange, GSON.toJson(response));
            });

            httpServer.createContext("/servers", exchange -> {
                if (!"GET".equals(exchange.getRequestMethod())) {
                    sendError(exchange, 405, "method not allowed");
                    return;
                }
                if (isRateLimited(exchange.getRemoteAddress().getHostString())) {
                    sendError(exchange, 429, "rate limit exceeded");
                    return;
                }
                JsonArray servers = new JsonArray();
                for (RegisteredServer rs : plugin.getServer().getAllServers()) {
                    JsonObject obj = new JsonObject();
                    obj.addProperty("name", rs.getServerInfo().getName());
                    obj.addProperty("players", rs.getPlayersConnected().size());
                    servers.add(obj);
                }

                JsonObject response = new JsonObject();
                response.add("servers", servers);

                sendJson(exchange, GSON.toJson(response));
            });

            httpServer.createContext("/players", exchange -> {
                if (!"GET".equals(exchange.getRequestMethod())) {
                    sendError(exchange, 405, "method not allowed");
                    return;
                }
                if (isRateLimited(exchange.getRemoteAddress().getHostString())) {
                    sendError(exchange, 429, "rate limit exceeded");
                    return;
                }

                String path = exchange.getRequestURI().getPath();
                // Handle /players/{name}
                if (path.length() > "/players/".length()) {
                    String name = path.substring("/players/".length());
                    if (!USERNAME.matcher(name).matches()) {
                        sendError(exchange, 400, "invalid username");
                        return;
                    }
                    Optional<Player> target = plugin.getServer().getPlayer(name);
                    if (target.isPresent()) {
                        sendJson(exchange, GSON.toJson(buildPlayerJson(target.get())));
                    } else {
                        sendError(exchange, 404, "player not online");
                    }
                    return;
                }

                JsonArray players = new JsonArray();
                for (Player player : plugin.getServer().getAllPlayers()) {
                    players.add(buildPlayerJson(player));
                }

                JsonObject response = new JsonObject();
                response.addProperty("count", players.size());
                response.add("players", players);

                sendJson(exchange, GSON.toJson(response));
            });

            httpServer.createContext("/awards/crowns", exchange -> {
                if (!"GET".equals(exchange.getRequestMethod())) {
                    sendError(exchange, 405, "method not allowed");
                    return;
                }
                if (isRateLimited(exchange.getRemoteAddress().getHostString())) {
                    sendError(exchange, 429, "rate limit exceeded");
                    return;
                }
                var params = parseQuery(exchange.getRequestURI());
                int limit = 100;
                int offset = 0;
                try { limit = Integer.parseInt(params.getOrDefault("limit", "100")); } catch (NumberFormatException ignored) {}
                try { offset = Integer.parseInt(params.getOrDefault("offset", "0")); } catch (NumberFormatException ignored) {}
                var result = plugin.getAwardQueryService().getCrownLeaderboard(
                        params.get("season"), params.get("server"), limit, offset);
                if (result == null) {
                    sendError(exchange, 404, "no current season");
                    return;
                }
                sendJson(exchange, GSON.toJson(result));
            });

            httpServer.createContext("/awards/player/", exchange -> {
                if (!"GET".equals(exchange.getRequestMethod())) {
                    sendError(exchange, 405, "method not allowed");
                    return;
                }
                if (isRateLimited(exchange.getRemoteAddress().getHostString())) {
                    sendError(exchange, 429, "rate limit exceeded");
                    return;
                }
                String path = exchange.getRequestURI().getPath();
                String uuid = path.substring("/awards/player/".length());
                if (!UUID_PATTERN.matcher(uuid).matches()) {
                    sendError(exchange, 400, "invalid uuid format");
                    return;
                }
                var params = parseQuery(exchange.getRequestURI());
                var result = plugin.getAwardQueryService().getPlayerAwards(
                        uuid, params.get("season"), params.get("server"));
                if (result == null) {
                    sendError(exchange, 404, "no current season");
                    return;
                }
                if (result.has("notFound")) {
                    sendError(exchange, 404, "player has no award data");
                    return;
                }
                sendJson(exchange, GSON.toJson(result));
            });

            httpServer.createContext("/awards", exchange -> {
                if (!"GET".equals(exchange.getRequestMethod())) {
                    sendError(exchange, 405, "method not allowed");
                    return;
                }
                if (isRateLimited(exchange.getRemoteAddress().getHostString())) {
                    sendError(exchange, 429, "rate limit exceeded");
                    return;
                }
                var params = parseQuery(exchange.getRequestURI());
                String path = exchange.getRequestURI().getPath();

                // /awards/{id} — single award leaderboard
                if (path.length() > "/awards/".length()) {
                    String awardId = path.substring("/awards/".length());
                    if (!AWARD_ID_PATTERN.matcher(awardId).matches()) {
                        sendError(exchange, 400, "invalid award id format");
                        return;
                    }
                    int limit = 100;
                    int offset = 0;
                    try { limit = Integer.parseInt(params.getOrDefault("limit", "100")); } catch (NumberFormatException ignored) {}
                    try { offset = Integer.parseInt(params.getOrDefault("offset", "0")); } catch (NumberFormatException ignored) {}
                    var result = plugin.getAwardQueryService().getAwardLeaderboard(
                            awardId, params.get("season"), params.get("server"), limit, offset);
                    if (result == null) {
                        sendError(exchange, 404, "no current season");
                        return;
                    }
                    if (result.has("notFound")) {
                        sendError(exchange, 404, "award not found");
                        return;
                    }
                    sendJson(exchange, GSON.toJson(result));
                    return;
                }

                // /awards — list all awards with leaders
                var result = plugin.getAwardQueryService().getAllAwards(
                        params.get("season"), params.get("server"));
                if (result == null) {
                    sendError(exchange, 404, "no current season");
                    return;
                }
                sendJson(exchange, GSON.toJson(result));
            });

            httpServer.createContext("/advancements/leaderboard", exchange -> {
                if (!"GET".equals(exchange.getRequestMethod())) {
                    sendError(exchange, 405, "method not allowed");
                    return;
                }
                if (isRateLimited(exchange.getRemoteAddress().getHostString())) {
                    sendError(exchange, 429, "rate limit exceeded");
                    return;
                }
                var params = parseQuery(exchange.getRequestURI());
                int limit = 100;
                int offset = 0;
                try { limit = Integer.parseInt(params.getOrDefault("limit", "100")); } catch (NumberFormatException ignored) {}
                try { offset = Integer.parseInt(params.getOrDefault("offset", "0")); } catch (NumberFormatException ignored) {}
                var result = plugin.getAdvancementQueryService().getAdvancementLeaderboard(
                        params.get("season"), params.get("server"), limit, offset);
                if (result == null) {
                    sendError(exchange, 404, "no current season");
                    return;
                }
                sendJson(exchange, GSON.toJson(result));
            });

            httpServer.createContext("/advancements/player/", exchange -> {
                if (!"GET".equals(exchange.getRequestMethod())) {
                    sendError(exchange, 405, "method not allowed");
                    return;
                }
                if (isRateLimited(exchange.getRemoteAddress().getHostString())) {
                    sendError(exchange, 429, "rate limit exceeded");
                    return;
                }
                String path = exchange.getRequestURI().getPath();
                String uuid = path.substring("/advancements/player/".length());
                if (!UUID_PATTERN.matcher(uuid).matches()) {
                    sendError(exchange, 400, "invalid uuid format");
                    return;
                }
                var params = parseQuery(exchange.getRequestURI());
                var result = plugin.getAdvancementQueryService().getPlayerAdvancements(
                        uuid, params.get("season"), params.get("server"));
                if (result == null) {
                    sendError(exchange, 404, "no current season");
                    return;
                }
                if (result.has("notFound")) {
                    sendError(exchange, 404, "player has no advancement data");
                    return;
                }
                sendJson(exchange, GSON.toJson(result));
            });

            httpServer.start();
            plugin.getLogger().info("Web API started on port {}", port);
            return true;
        } catch (IOException e) {
            plugin.getLogger().error("Failed to start Web API on port {}", port, e);
            return false;
        }
    }

    public void stop() {
        if (httpServer != null) {
            httpServer.stop(0);
            httpServer = null;
            plugin.getLogger().info("Web API stopped.");
        }
    }
}
