package crabcraft.net.crabUtilities.velocity.api

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import com.velocitypowered.api.proxy.Player
import crabcraft.net.crabUtilities.velocity.CrabUtilitiesVelocity
import crabcraft.net.crabUtilities.velocity.litebans.LiteBansInfractionService.LiteBansUnavailableException
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

open class WebServer(private val plugin: CrabUtilitiesVelocity, private val port: Int) {
    private val chatConnectionLimiter =
        ChatConnectionLimiter(
            plugin.getConfig().getPublicChatMaxConnections(),
            plugin.getConfig().getPublicChatMaxConnectionsPerIp(),
        )
    private var httpServer: HttpServer? = null
    private var httpExecutor: ExecutorService? = null
    private var cloudflareIpRefresher: ScheduledExecutorService? = null
    private var publicChatBroker: PublicChatBroker? = null
    // [count, windowStartMs] per IP
    private val rateLimits = ConcurrentHashMap<String, LongArray>()

    open fun isRunning(): Boolean = httpServer != null

    private fun isRateLimited(exchange: HttpExchange): Boolean {
        val ip = ClientIpResolver.resolve(exchange)
        val now = System.currentTimeMillis()
        val window =
            rateLimits[ip]
                ?: synchronized(rateLimits) {
                    rateLimits[ip]
                        ?: run {
                            if (rateLimits.size >= MAX_RATE_LIMIT_ENTRIES) {
                                rateLimits.entries.removeIf { now - it.value[1] > RATE_WINDOW_MS }
                            }
                            if (rateLimits.size >= MAX_RATE_LIMIT_ENTRIES) return true
                            longArrayOf(0L, now).also { rateLimits[ip] = it }
                        }
                }
        synchronized(window) {
            if (now - window[1] > RATE_WINDOW_MS) {
                window[0] = 0L
                window[1] = now
            }
            if (window[0] >= RATE_LIMIT) return true
            window[0]++
            return false
        }
    }

    private fun buildPlayerJson(player: Player): JsonObject {
        val cache = plugin.getNicknameCache()
        return JsonObject().apply {
            addProperty("username", player.getUsername())
            addProperty("uuid", player.getUniqueId().toString())
            addProperty("nickname", cache.getPlainNickname(player.getUniqueId()))
            addProperty("nickname_raw", cache.getRawNickname(player.getUniqueId()))
            addProperty("ping", player.getPing())
            addProperty("server", player.getCurrentServer().map { it.getServerInfo().getName() }.orElse(null))
            val streak = plugin.getLoginStreakService()?.getPlayerStreakJson(player.getUniqueId().toString())
            addProperty("current_streak", if (streak == null) 0 else streak.get("current_streak").asInt)
        }
    }

    private fun handlePublicChat(exchange: HttpExchange) {
        if (exchange.requestMethod != "GET") {
            sendError(exchange, 405, "method not allowed")
            return
        }
        if (exchange.requestURI.path != "/chat/events") {
            sendError(exchange, 404, "not found")
            return
        }
        val origin = exchange.requestHeaders.getFirst("Origin")
        if (origin != null) {
            if (!plugin.getConfig().getPublicChatAllowedOrigins().contains(origin)) {
                sendError(exchange, 403, "origin not allowed")
                return
            }
            exchange.responseHeaders.set("Access-Control-Allow-Origin", origin)
            exchange.responseHeaders.set("Vary", "Origin")
        }
        val broker = publicChatBroker
        if (broker == null) {
            sendError(exchange, 503, "public chat is disabled")
            return
        }
        if (isRateLimited(exchange)) {
            sendError(exchange, 429, "rate limit exceeded")
            return
        }
        val ip = ClientIpResolver.resolve(exchange)
        if (!chatConnectionLimiter.tryAcquire(ip)) {
            sendError(exchange, 503, "public chat connection limit reached")
            return
        }
        val subscription =
            try {
                broker.subscribe(
                    exchange.requestHeaders.getFirst("Last-Event-ID"),
                    plugin.getConfig().getPublicChatReplayMessages(),
                )
            } catch (_: IllegalStateException) {
                chatConnectionLimiter.release(ip)
                sendError(exchange, 503, "public chat is unavailable")
                return
            }
        exchange.responseHeaders.set("Content-Type", "text/event-stream; charset=UTF-8")
        exchange.responseHeaders.set("Cache-Control", "no-cache, no-transform")
        exchange.responseHeaders.set("Connection", "keep-alive")
        exchange.responseHeaders.set("X-Accel-Buffering", "no")
        try {
            subscription.use {
                exchange.sendResponseHeaders(200, 0)
                exchange.responseBody.use { output ->
                    output.write(": connected\nretry: 5000\n\n".toByteArray(StandardCharsets.UTF_8))
                    output.flush()
                    while (!subscription.isClosed() && !Thread.currentThread().isInterrupted) {
                        val event = subscription.poll(15L, TimeUnit.SECONDS)
                        if (event == null) {
                            if (subscription.isClosed()) break
                            output.write(": keep-alive\n\n".toByteArray(StandardCharsets.UTF_8))
                        } else output.write(event.toSseBytes())
                        output.flush()
                    }
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (_: IOException) {
            // Browser and proxy disconnects are normal for a long-lived stream.
        } finally {
            chatConnectionLimiter.release(ip)
        }
    }

    private fun registerRateLimitedGet(path: String, handler: HttpHandler) {
        httpServer!!.createContext(
            path,
            HttpHandler { exchange ->
                if (exchange.requestMethod != "GET") {
                    sendError(exchange, 405, "method not allowed")
                    return@HttpHandler
                }
                if (isRateLimited(exchange)) {
                    sendError(exchange, 429, "rate limit exceeded")
                    return@HttpHandler
                }
                handler.handle(exchange)
            },
        )
    }

    open fun start(): Boolean {
        if (httpServer != null) {
            plugin.getLogger().warn("Web API is already running.")
            return false
        }
        try {
            val server = HttpServer.create(InetSocketAddress(port), 0)
            httpServer = server
            val publicChatConnections =
                if (plugin.getConfig().isPublicChatEnabled()) plugin.getConfig().getPublicChatMaxConnections() else 0
            httpExecutor = createHttpExecutor(publicChatConnections)
            server.executor = httpExecutor
            server.createContext(
                "/",
                HttpHandler { exchange ->
                    if (exchange.requestMethod != "GET") {
                        sendError(exchange, 405, "method not allowed")
                        return@HttpHandler
                    }
                    if (exchange.requestURI.path != "/") {
                        sendError(exchange, 404, "not found")
                        return@HttpHandler
                    }
                    val body = SCALAR_HTML.toByteArray(StandardCharsets.UTF_8)
                    exchange.responseHeaders.set("Content-Type", "text/html; charset=UTF-8")
                    exchange.sendResponseHeaders(200, body.size.toLong())
                    exchange.responseBody.use { it.write(body) }
                },
            )
            server.createContext(
                "/openapi.json",
                HttpHandler { exchange ->
                    if (exchange.requestMethod != "GET") {
                        sendError(exchange, 405, "method not allowed")
                        return@HttpHandler
                    }
                    sendJson(exchange, OPENAPI_JSON)
                },
            )
            registerRateLimitedGet("/ping") { exchange -> sendJson(exchange, "{\"status\":\"ok\"}") }
            server.createContext("/chat/events", ::handlePublicChat)
            registerRateLimitedGet("/status") { exchange ->
                val players =
                    JsonObject().apply {
                        addProperty("online", plugin.getVanishManager().visiblePlayers().size)
                        addProperty("max", plugin.getServer().getConfiguration().getShowMaxPlayers())
                    }
                val response =
                    JsonObject().apply {
                        addProperty("online", true)
                        add("players", players)
                        addProperty("version", plugin.getServer().getVersion().toString())
                    }
                sendJson(exchange, GSON.toJson(response))
            }
            registerRateLimitedGet("/servers") { exchange ->
                val servers = JsonArray()
                for (registered in plugin.getServer().getAllServers()) {
                    servers.add(
                        JsonObject().apply {
                            addProperty("name", registered.getServerInfo().getName())
                            addProperty("players", plugin.getVanishManager().visiblePlayerCount(registered))
                        }
                    )
                }
                sendJson(exchange, GSON.toJson(JsonObject().apply { add("servers", servers) }))
            }
            registerRateLimitedGet("/players") { exchange ->
                val players = JsonArray()
                for (player in plugin.getVanishManager().visiblePlayers()) players.add(buildPlayerJson(player))
                val response =
                    JsonObject().apply {
                        addProperty("count", players.size())
                        add("players", players)
                    }
                sendJson(exchange, GSON.toJson(response))
            }
            server.createContext("/punishments/active", ::handleActivePunishments)
            registerRateLimitedGet("/awards/crowns") { exchange ->
                val params = parseQuery(exchange.requestURI)
                val result =
                    plugin
                        .getAwardQueryService()!!
                        .getCrownLeaderboard(
                            params["season"],
                            queryInt(params, "limit", 100),
                            queryInt(params, "offset", 0),
                            params["show_hidden"] == "true",
                        )
                sendSeasonResult(exchange, result, null)
            }
            registerRateLimitedGet("/players/", ::handlePlayer)
            registerRateLimitedGet("/awards") { exchange ->
                val params = parseQuery(exchange.requestURI)
                val path = exchange.requestURI.path
                // /awards/{id} — single award leaderboard
                if (path.length > "/awards/".length) {
                    val awardId = path.substring("/awards/".length)
                    if (!AWARD_ID_PATTERN.matcher(awardId).matches()) {
                        sendError(exchange, 400, "invalid award id format")
                        return@registerRateLimitedGet
                    }
                    val result =
                        plugin
                            .getAwardQueryService()!!
                            .getAwardLeaderboard(
                                awardId,
                                params["season"],
                                queryInt(params, "limit", 100),
                                queryInt(params, "offset", 0),
                                params["show_hidden"] == "true",
                            )
                    sendSeasonResult(exchange, result, "award not found")
                    return@registerRateLimitedGet
                }
                val result =
                    plugin.getAwardQueryService()!!.getAllAwards(params["season"], params["show_hidden"] == "true")
                sendSeasonResult(exchange, result, null)
            }
            registerRateLimitedGet("/streaks/leaderboard") { exchange ->
                val service = plugin.getLoginStreakService()
                if (service == null) {
                    sendError(exchange, 503, "streak service unavailable")
                    return@registerRateLimitedGet
                }
                val params = parseQuery(exchange.requestURI)
                sendJson(
                    exchange,
                    GSON.toJson(
                        service.getLeaderboard(
                            queryInt(params, "limit", 100),
                            queryInt(params, "offset", 0),
                            "longest".equals(params["metric"], ignoreCase = true),
                        )
                    ),
                )
            }
            registerRateLimitedGet("/advancements/leaderboard") { exchange ->
                val params = parseQuery(exchange.requestURI)
                val result =
                    plugin
                        .getAdvancementQueryService()
                        .getAdvancementLeaderboard(
                            params["season"],
                            queryInt(params, "limit", 100),
                            queryInt(params, "offset", 0),
                            params["category"],
                            params["show_hidden"] == "true",
                        )
                sendSeasonResult(exchange, result, null)
            }
            if (plugin.getConfig().isPublicChatEnabled()) {
                publicChatBroker =
                    PublicChatBroker(
                        plugin.getConfig().getRedisHost(),
                        plugin.getConfig().getRedisPort(),
                        plugin.getConfig().getRedisPassword(),
                        plugin.getConfig().getPublicChatStream(),
                        plugin.getLogger(),
                    )
                publicChatBroker!!.start()
            }
            server.start()
            plugin.getLogger().info("Web API started on port {}", port)
            cloudflareIpRefresher = Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "crabutilities-cf-ip-refresher").apply { isDaemon = true }
            }
            cloudflareIpRefresher!!.scheduleWithFixedDelay(
                { ClientIpResolver.refreshCloudflareRanges(plugin.getLogger()) },
                0L,
                24L,
                TimeUnit.HOURS,
            )
            return true
        } catch (e: IOException) {
            publicChatBroker?.close()
            publicChatBroker = null
            httpExecutor?.shutdownNow()
            httpExecutor = null
            plugin.getLogger().error("Failed to start Web API on port {}", port, e)
            return false
        }
    }

    private fun handleActivePunishments(exchange: HttpExchange) {
        if (exchange.requestMethod != "POST") {
            sendError(exchange, 405, "method not allowed")
            return
        }
        if (isRateLimited(exchange)) {
            sendError(exchange, 429, "rate limit exceeded")
            return
        }
        val body =
            try {
                GSON.fromJson(readRequestBody(exchange.requestBody), JsonObject::class.java)
            } catch (_: RequestBodyTooLargeException) {
                sendError(exchange, 413, "request body exceeds 64 KiB")
                return
            } catch (_: IOException) {
                sendError(exchange, 400, "failed to read request body")
                return
            } catch (_: RuntimeException) {
                sendError(exchange, 400, "request body must be a JSON object")
                return
            }
        if (body == null || !body.has("uuids") || !body.get("uuids").isJsonArray) {
            sendError(exchange, 400, "uuids must be an array")
            return
        }
        val rawUuids = body.getAsJsonArray("uuids")
        if (rawUuids.size() > 1000) {
            sendError(exchange, 400, "uuids must contain at most 1000 entries")
            return
        }
        val uuids = LinkedHashSet<String>()
        for (element in rawUuids) {
            if (!element.isJsonPrimitive || !element.asJsonPrimitive.isString) {
                sendError(exchange, 400, "uuids must contain only strings")
                return
            }
            val uuid = normalizeMinecraftUuid(element.asString)
            if (uuid == null) {
                sendError(exchange, 400, "invalid uuid format")
                return
            }
            uuids.add(uuid)
        }
        val service = plugin.getLiteBansInfractionService()
        if (service == null) {
            sendError(exchange, 503, "litebans service unavailable")
            return
        }
        try {
            sendJson(exchange, GSON.toJson(service.getActivePunishmentsJson(uuids)))
        } catch (_: LiteBansUnavailableException) {
            sendError(exchange, 503, "litebans is not available")
        } catch (e: SQLException) {
            plugin.getLogger().warn("Failed to query active LiteBans punishments", e)
            sendError(exchange, 500, "failed to query active litebans punishments")
        }
    }

    private fun handlePlayer(exchange: HttpExchange) {
        val sub = exchange.requestURI.path.substring("/players/".length)
        val separator = sub.lastIndexOf('/')
        val resource = sub.substring(separator + 1)
        if (separator >= 0 && resource in PLAYER_RESOURCES) {
            val rawUuid = sub.substring(0, separator)
            val uuid =
                if (resource == "infractions") normalizeMinecraftUuid(rawUuid)
                else rawUuid.takeIf { UUID_PATTERN.matcher(it).matches() }
            if (uuid == null) {
                sendError(exchange, 400, "invalid uuid format")
                return
            }
            when (resource) {
                "awards" -> {
                    val params = parseQuery(exchange.requestURI)
                    sendSeasonResult(
                        exchange,
                        plugin.getAwardQueryService()!!.getPlayerAwards(uuid, params["season"]),
                        "player has no award data",
                    )
                }
                "advancements" -> {
                    val params = parseQuery(exchange.requestURI)
                    sendSeasonResult(
                        exchange,
                        plugin.getAdvancementQueryService().getPlayerAdvancements(uuid, params["season"]),
                        null,
                    )
                }
                "stats" -> {
                    val params = parseQuery(exchange.requestURI)
                    sendSeasonResult(
                        exchange,
                        plugin.getStatsQueryService().getPlayerStats(uuid, params["season"]),
                        "player has no stat data",
                    )
                }
                "seasons" -> {
                    val seasons = plugin.getStatsQueryService().getPlayerSeasons(uuid)
                    if (seasons == null) {
                        sendError(exchange, 500, "failed to query seasons")
                        return
                    }
                    sendJson(
                        exchange,
                        GSON.toJson(
                            JsonObject().apply {
                                addProperty("uuid", uuid)
                                add("seasons", seasons)
                            }
                        ),
                    )
                }
                "infractions" -> {
                    val params = parseQuery(exchange.requestURI)
                    var limit = 10
                    val rawLimit = params["limit"]
                    if (rawLimit != null) {
                        try {
                            limit = Integer.parseInt(rawLimit)
                        } catch (_: NumberFormatException) {
                            sendError(exchange, 400, "limit must be an integer between 1 and 25")
                            return
                        }
                        if (limit !in 1..25) {
                            sendError(exchange, 400, "limit must be between 1 and 25")
                            return
                        }
                    }
                    val service = plugin.getLiteBansInfractionService()
                    if (service == null) {
                        sendError(exchange, 503, "litebans service unavailable")
                        return
                    }
                    try {
                        sendJson(exchange, GSON.toJson(service.getInfractionsJson(uuid, limit)))
                    } catch (_: LiteBansUnavailableException) {
                        sendError(exchange, 503, "litebans is not available")
                    } catch (e: SQLException) {
                        plugin.getLogger().warn("Failed to query LiteBans infractions for {}", uuid, e)
                        sendError(exchange, 500, "failed to query litebans infractions")
                    }
                }
                "streak" -> {
                    val service = plugin.getLoginStreakService()
                    if (service == null) {
                        sendError(exchange, 503, "streak service unavailable")
                        return
                    }
                    val result = service.getPlayerStreakJson(uuid)
                    if (result == null) {
                        sendError(exchange, 404, "no streak data for player")
                        return
                    }
                    sendJson(exchange, GSON.toJson(result))
                }
            }
            return
        }
        // /players/{name} — online player lookup
        if (!USERNAME.matcher(sub).matches()) {
            sendError(exchange, 400, "invalid username")
            return
        }
        val target = plugin.getServer().getPlayer(sub)
        if (target.isPresent && plugin.getVanishManager().isVisible(target.get())) {
            sendJson(exchange, GSON.toJson(buildPlayerJson(target.get())))
        } else sendError(exchange, 404, "player not online")
    }

    open fun stop() {
        cloudflareIpRefresher?.shutdownNow()
        cloudflareIpRefresher = null
        publicChatBroker?.close()
        publicChatBroker = null
        httpServer?.let {
            it.stop(0)
            httpServer = null
            plugin.getLogger().info("Web API stopped.")
        }
        httpExecutor?.shutdownNow()
        httpExecutor = null
    }

    private class RequestBodyTooLargeException : IOException()

    companion object {
        private val GSON = Gson()
        private const val RATE_LIMIT = 60
        private const val RATE_WINDOW_MS = 60_000L
        private const val MAX_RATE_LIMIT_ENTRIES = 10_000
        private const val MAX_REQUEST_BODY_BYTES = 64 * 1024
        private const val ORDINARY_HTTP_WORKERS = 4
        private const val MAX_QUEUED_HTTP_REQUESTS = 128
        private val USERNAME = Pattern.compile("^[a-zA-Z0-9_]{3,16}$")
        private val UUID_PATTERN =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
        private val COMPACT_UUID_PATTERN = Pattern.compile("^[0-9a-fA-F]{32}$")
        private val AWARD_ID_PATTERN = Pattern.compile("^[a-z0-9_]+$")
        private val PLAYER_RESOURCES = setOf("awards", "advancements", "stats", "seasons", "infractions", "streak")

        @JvmStatic
        fun createHttpExecutor(publicChatConnections: Int): ThreadPoolExecutor {
            val chatWorkers = maxOf(0, publicChatConnections)
            val workerLimit =
                if (chatWorkers > Int.MAX_VALUE - ORDINARY_HTTP_WORKERS) Int.MAX_VALUE
                else ORDINARY_HTTP_WORKERS + chatWorkers
            return ThreadPoolExecutor(
                    workerLimit,
                    workerLimit,
                    60L,
                    TimeUnit.SECONDS,
                    LinkedBlockingQueue(MAX_QUEUED_HTTP_REQUESTS),
                    Thread.ofVirtual().name("crabutilities-api-", 0).factory(),
                    ThreadPoolExecutor.AbortPolicy(),
                )
                .apply { allowCoreThreadTimeOut(true) }
        }

        @JvmStatic
        @Throws(IOException::class)
        fun readRequestBody(input: InputStream): String {
            val bytes = input.readNBytes(MAX_REQUEST_BODY_BYTES + 1)
            if (bytes.size > MAX_REQUEST_BODY_BYTES) throw RequestBodyTooLargeException()
            return String(bytes, StandardCharsets.UTF_8)
        }

        private fun sendSeasonResult(exchange: HttpExchange, result: JsonObject?, notFoundMessage: String?) {
            if (result == null) sendError(exchange, 404, "no current season")
            else if (notFoundMessage != null && result.has("notFound")) sendError(exchange, 404, notFoundMessage)
            else sendJson(exchange, GSON.toJson(result))
        }

        private fun sendJson(exchange: HttpExchange, json: String) = sendJson(exchange, 200, json)

        private fun sendJson(exchange: HttpExchange, status: Int, json: String) {
            val body = json.toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.set("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }

        private fun sendError(exchange: HttpExchange, code: Int, message: String) {
            sendJson(exchange, code, GSON.toJson(JsonObject().apply { addProperty("error", message) }))
        }

        private fun parseQuery(uri: URI): Map<String, String> {
            val params = HashMap<String, String>()
            val query = uri.rawQuery
            if (query.isNullOrEmpty()) return params
            for (pair in query.split('&')) {
                val equals = pair.indexOf('=')
                if (equals > 0)
                    params[URLDecoder.decode(pair.substring(0, equals), StandardCharsets.UTF_8)] =
                        URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8)
            }
            return params
        }

        private fun normalizeMinecraftUuid(value: String): String? {
            if (UUID_PATTERN.matcher(value).matches()) return UUID.fromString(value).toString()
            if (COMPACT_UUID_PATTERN.matcher(value).matches())
                return UUID.fromString(
                        "${value.substring(0, 8)}-${value.substring(8, 12)}-${value.substring(12, 16)}-" +
                            "${value.substring(16, 20)}-${value.substring(20)}"
                    )
                    .toString()
            return null
        }

        private fun queryInt(params: Map<String, String>, key: String, default: Int): Int =
            try {
                Integer.parseInt(params.getOrDefault(key, default.toString()))
            } catch (_: NumberFormatException) {
                default
            }

        private val ERROR_SCHEMA = "{\"type\":\"object\",\"properties\":{\"error\":{\"type\":\"string\"}}}"

        private val PLAYER_SCHEMA =
            "{" +
                "\"type\":\"object\"," +
                "\"properties\":{" +
                "\"username\":{\"type\":\"string\",\"description\":\"Minecraft username\"}," +
                "\"uuid\":{\"type\":\"string\",\"format\":\"uuid\",\"description\":\"Player UUID\"}," +
                "\"nickname\":{\"type\":\"string\",\"nullable\":true,\"description\":\"Plain-text display name\"}," +
                "\"nickname_raw\":{\"type\":\"string\",\"nullable\":true,\"description\":\"Raw (formatted) display name\"}," +
                "\"ping\":{\"type\":\"integer\",\"description\":\"Player latency in milliseconds\"}," +
                "\"server\":{\"type\":\"string\",\"nullable\":true,\"description\":\"Backend server the player is on\"}," +
                "\"current_streak\":{\"type\":\"integer\",\"description\":\"Live login streak. 0 if no qualified streak day has been recorded or the streak has lapsed.\"}" +
                "}" +
                "}"

        private val COMMON_ERRORS =
            "\"405\":{\"description\":\"Method not allowed\",\"content\":{\"application/json\":{\"schema\":" +
                ERROR_SCHEMA +
                "}}}," +
                "\"429\":{\"description\":\"Rate limit exceeded\",\"content\":{\"application/json\":{\"schema\":" +
                ERROR_SCHEMA +
                "}}}"

        private val OPENAPI_JSON =
            "{" +
                "\"openapi\":\"3.0.3\"," +
                "\"info\":{" +
                "\"title\":\"CrabCraft API\"," +
                "\"version\":\"2.1.0\"," +
                "\"description\":\"The CrabCraft API provides real-time and historical data from the CrabCraft Minecraft server network. " +
                "It is served directly from the Velocity proxy and requires no authentication.\\n\\n" +
                "## Rate Limiting\\n\\n" +
                "All endpoints (except docs) are rate limited to **60 requests per minute per IP**. " +
                "Exceeding this limit returns a 429 status code.\\n\\n" +
                "## Seasons\\n\\n" +
                "Many endpoints accept an optional `season` query parameter. " +
                "If omitted, it defaults to the currently active season. " +
                "Seasons are identified by short IDs like `6` or `creative`.\\n\\n" +
                "## Pagination\\n\\n" +
                "Leaderboard endpoints support pagination with `limit` (max 100, default 100) and `offset` (default 0). " +
                "Paginated responses include `total`, `offset`, and `limit` fields alongside the data array.\\n\\n" +
                "## Crown Scoring\\n\\n" +
                "The crown system ranks players by their medal holdings across all awards. " +
                "Gold medals (1st place) are worth 5 points, silver (2nd) worth 3 points, and bronze (3rd) worth 1 point. " +
                "The crown score is the weighted sum of all medals a player holds.\"" +
                "}," +
                "\"tags\":[" +
                "{\"name\":\"Server\",\"description\":\"Proxy server status, backend servers, and online player information. These endpoints return live data from the running proxy.\"}," +
                "{\"name\":\"Chat\",\"description\":\"A read-only, moderated stream of normal in-game chat for public website displays.\"}," +
                "{\"name\":\"Players\",\"description\":\"Player-specific data including online status, award scores, and advancement progress. Use a Minecraft UUID to look up a specific player.\"}," +
                "{\"name\":\"Punishments\",\"description\":\"Punishment-state checks backed by LiteBans.\"}," +
                "{\"name\":\"Awards\",\"description\":\"Awards are competitive stat-tracking categories (e.g. distance walked, mobs killed, items crafted). Each award has a leaderboard. Players earn gold, silver, and bronze medals for placing in the top 3. The crown leaderboard ranks players by their total medal points.\"}," +
                "{\"name\":\"Advancements\",\"description\":\"Minecraft advancements (achievements) tracked per player per season. The leaderboard ranks players by how many advancements they have completed.\"}," +
                "{\"name\":\"Streaks\",\"description\":\"All-time login streaks. A streak counts the days a player is online long enough to qualify, where a day is a fixed 24-hour window rolling over at 06:00 UTC. A qualified day adds +1; a single missed day is forgiven (the streak holds but earns no point); missing two days in a row resets the streak to 1. Alt accounts are capped at a one-day streak and excluded from the leaderboard.\"}" +
                "]," +
                "\"servers\":[{\"url\":\"https://api.crabcraft.net\"}]," +
                "\"paths\":{" +

                // ── Server ──
                "\"/ping\":{" +
                "\"get\":{" +
                "\"tags\":[\"Server\"]," +
                "\"summary\":\"Health check\"," +
                "\"description\":\"Returns a simple status object to verify the API is running and reachable. Useful for monitoring and uptime checks.\"," +
                "\"operationId\":\"ping\"," +
                "\"responses\":{" +
                "\"200\":{" +
                "\"description\":\"API is healthy\"," +
                "\"content\":{\"application/json\":{\"schema\":{\"type\":\"object\",\"properties\":{\"status\":{\"type\":\"string\",\"example\":\"ok\"}}}}}" +
                "}," +
                COMMON_ERRORS +
                "}" +
                "}" +
                "}," +
                "\"/status\":{" +
                "\"get\":{" +
                "\"tags\":[\"Server\"]," +
                "\"summary\":\"Server status\"," +
                "\"description\":\"Returns an overview of the proxy including whether it is online, the visible player count, maximum player slots, and the server version string. Vanished players are excluded.\"," +
                "\"operationId\":\"getStatus\"," +
                "\"responses\":{" +
                "\"200\":{" +
                "\"description\":\"Server status retrieved\"," +
                "\"content\":{\"application/json\":{\"schema\":{\"type\":\"object\",\"properties\":{" +
                "\"online\":{\"type\":\"boolean\",\"description\":\"Always true when the API is reachable\"}," +
                "\"players\":{\"type\":\"object\",\"properties\":{" +
                "\"online\":{\"type\":\"integer\",\"description\":\"Number of visible players currently connected\"}," +
                "\"max\":{\"type\":\"integer\",\"description\":\"Maximum player slots configured on the proxy\"}" +
                "}}," +
                "\"version\":{\"type\":\"string\",\"description\":\"Velocity proxy version string\"}" +
                "}}}}" +
                "}," +
                COMMON_ERRORS +
                "}" +
                "}" +
                "}," +
                "\"/chat/events\":{" +
                "\"get\":{" +
                "\"tags\":[\"Chat\"]," +
                "\"summary\":\"Stream public in-game chat\"," +
                "\"description\":\"Opens a Server-Sent Events connection. The latest messages are replayed first, followed by new accepted normal chat. Event IDs are Redis stream IDs and are resumed from the Last-Event-ID request header when possible.\"," +
                "\"operationId\":\"streamPublicChat\"," +
                "\"responses\":{" +
                "\"200\":{\"description\":\"SSE chat stream opened\",\"content\":{\"text/event-stream\":{\"schema\":{\"type\":\"string\"}}}}," +
                "\"403\":{\"description\":\"Browser origin is not allowed\",\"content\":{\"application/json\":{\"schema\":" +
                ERROR_SCHEMA +
                "}}}," +
                "\"503\":{\"description\":\"Public chat is disabled or at connection capacity\",\"content\":{\"application/json\":{\"schema\":" +
                ERROR_SCHEMA +
                "}}}," +
                COMMON_ERRORS +
                "}" +
                "}" +
                "}," +
                "\"/servers\":{" +
                "\"get\":{" +
                "\"tags\":[\"Server\"]," +
                "\"summary\":\"List backend servers\"," +
                "\"description\":\"Returns all backend servers registered on the proxy with the number of visible players currently connected to each. Vanished players are excluded.\"," +
                "\"operationId\":\"getServers\"," +
                "\"responses\":{" +
                "\"200\":{" +
                "\"description\":\"Server list retrieved\"," +
                "\"content\":{\"application/json\":{\"schema\":{\"type\":\"object\",\"properties\":{" +
                "\"servers\":{\"type\":\"array\",\"items\":{\"type\":\"object\",\"properties\":{" +
                "\"name\":{\"type\":\"string\",\"description\":\"Server name as registered in the proxy config\"}," +
                "\"players\":{\"type\":\"integer\",\"description\":\"Number of visible players on this server\"}" +
                "}}}" +
                "}}}}" +
                "}," +
                COMMON_ERRORS +
                "}" +
                "}" +
                "}," +

                // ── Players ──
                "\"/players\":{" +
                "\"get\":{" +
                "\"tags\":[\"Players\"]," +
                "\"summary\":\"List online players\"," +
                "\"description\":\"Returns all visible players currently connected to the proxy across all backend servers. EssentialsX-vanished players are excluded. Each player object includes their username, UUID, display nickname, ping, and backend server.\"," +
                "\"operationId\":\"getPlayers\"," +
                "\"responses\":{" +
                "\"200\":{" +
                "\"description\":\"Player list retrieved\"," +
                "\"content\":{\"application/json\":{\"schema\":{\"type\":\"object\",\"properties\":{" +
                "\"count\":{\"type\":\"integer\",\"description\":\"Total number of visible online players\"}," +
                "\"players\":{\"type\":\"array\",\"items\":" +
                PLAYER_SCHEMA +
                "}" +
                "}}}}" +
                "}," +
                COMMON_ERRORS +
                "}" +
                "}" +
                "}," +
                "\"/players/{name}\":{" +
                "\"get\":{" +
                "\"tags\":[\"Players\"]," +
                "\"summary\":\"Look up online player\"," +
                "\"description\":\"Returns details for a specific visible player by their Minecraft username. Returns 404 when the player is offline or vanished.\"," +
                "\"operationId\":\"getPlayer\"," +
                "\"parameters\":[{\"name\":\"name\",\"in\":\"path\",\"required\":true,\"schema\":{\"type\":\"string\",\"pattern\":\"^[a-zA-Z0-9_]{3,16}\$\"},\"description\":\"Minecraft username (3-16 alphanumeric characters or underscores)\"}]," +
                "\"responses\":{" +
                "\"200\":{" +
                "\"description\":\"Player found and online\"," +
                "\"content\":{\"application/json\":{\"schema\":" +
                PLAYER_SCHEMA +
                "}}" +
                "}," +
                "\"400\":{\"description\":\"Username does not match the required format\",\"content\":{\"application/json\":{\"schema\":" +
                ERROR_SCHEMA +
                "}}}," +
                "\"404\":{\"description\":\"No visible online player has that username\",\"content\":{\"application/json\":{\"schema\":" +
                ERROR_SCHEMA +
                "}}}," +
                COMMON_ERRORS +
                "}" +
                "}" +
                "}," +
                "\"/players/{uuid}/awards\":{" +
                "\"get\":{" +
                "\"tags\":[\"Players\"]," +
                "\"summary\":\"Player award scores\"," +
                "\"description\":\"Returns all award scores and rankings for a specific player, plus their crown score summary (gold, silver, bronze medal counts and overall rank). Only awards where the player has a score greater than zero are included. The scores object is keyed by award ID, with each entry containing the player's rank and score for that award.\"," +
                "\"operationId\":\"getPlayerAwards\"," +
                "\"parameters\":[" +
                "{\"name\":\"uuid\",\"in\":\"path\",\"required\":true,\"schema\":{\"type\":\"string\",\"format\":\"uuid\"},\"description\":\"Minecraft player UUID (with dashes)\"}," +
                "{\"name\":\"season\",\"in\":\"query\",\"schema\":{\"type\":\"string\"},\"description\":\"Season ID. Defaults to the current active season.\"}" +
                "]," +
                "\"responses\":{" +
                "\"200\":{\"description\":\"Player award data retrieved successfully\"," +
                "\"content\":{\"application/json\":{\"schema\":{\"type\":\"object\",\"properties\":{" +
                "\"uuid\":{\"type\":\"string\",\"format\":\"uuid\"}," +
                "\"username\":{\"type\":\"string\",\"nullable\":true}," +
                "\"crown\":{\"type\":\"object\",\"nullable\":true,\"properties\":{" +
                "\"rank\":{\"type\":\"integer\"},\"gold\":{\"type\":\"integer\"},\"silver\":{\"type\":\"integer\"},\"bronze\":{\"type\":\"integer\"},\"crown_score\":{\"type\":\"integer\"}" +
                "}}," +
                "\"scores\":{\"type\":\"object\",\"additionalProperties\":{\"type\":\"object\",\"properties\":{" +
                "\"rank\":{\"type\":\"integer\"},\"score\":{\"type\":\"number\"}" +
                "}}}" +
                "}}}}}," +
                "\"400\":{\"description\":\"UUID format is invalid. Must be a standard UUID with dashes.\",\"content\":{\"application/json\":{\"schema\":" +
                ERROR_SCHEMA +
                "}}}," +
                "\"404\":{\"description\":\"Player has no award data for this season, or no season is currently active.\",\"content\":{\"application/json\":{\"schema\":" +
                ERROR_SCHEMA +
                "}}}," +
                COMMON_ERRORS +
                "}" +
                "}" +
                "}," +
                "\"/players/{uuid}/advancements\":{" +
                "\"get\":{" +
                "\"tags\":[\"Players\"]," +
                "\"summary\":\"Player advancements\"," +
                "\"description\":\"Returns all tracked Minecraft advancements for a player with their completion status. Includes a count of completed vs total advancements. Each advancement entry shows whether it has been completed and the timestamp of completion (if available). Advancements are identified by their Minecraft namespace ID (e.g. minecraft:story/mine_stone).\"," +
                "\"operationId\":\"getPlayerAdvancements\"," +
                "\"parameters\":[" +
                "{\"name\":\"uuid\",\"in\":\"path\",\"required\":true,\"schema\":{\"type\":\"string\",\"format\":\"uuid\"},\"description\":\"Minecraft player UUID (with dashes)\"}," +
                "{\"name\":\"season\",\"in\":\"query\",\"schema\":{\"type\":\"string\"},\"description\":\"Season ID. Defaults to the current active season.\"}" +
                "]," +
                "\"responses\":{" +
                "\"200\":{\"description\":\"Player advancement data retrieved successfully\"," +
                "\"content\":{\"application/json\":{\"schema\":{\"type\":\"object\",\"properties\":{" +
                "\"uuid\":{\"type\":\"string\",\"format\":\"uuid\"}," +
                "\"username\":{\"type\":\"string\",\"nullable\":true}," +
                "\"completed\":{\"type\":\"integer\",\"description\":\"Number of completed advancements\"}," +
                "\"total\":{\"type\":\"integer\",\"description\":\"Total number of advancements\"}," +
                "\"advancements\":{\"type\":\"object\",\"additionalProperties\":{\"type\":\"object\",\"properties\":{" +
                "\"name\":{\"type\":\"string\"},\"description\":{\"type\":\"string\"},\"category\":{\"type\":\"string\"}," +
                "\"completed\":{\"type\":\"boolean\"},\"completed_at\":{\"type\":\"integer\",\"nullable\":true}" +
                "}}}" +
                "}}}}}," +
                "\"400\":{\"description\":\"UUID format is invalid. Must be a standard UUID with dashes.\",\"content\":{\"application/json\":{\"schema\":" +
                ERROR_SCHEMA +
                "}}}," +
                "\"404\":{\"description\":\"Player has no advancement data for this season, or no season is currently active.\",\"content\":{\"application/json\":{\"schema\":" +
                ERROR_SCHEMA +
                "}}}," +
                COMMON_ERRORS +
                "}" +
                "}" +
                "}," +
                "\"/players/{uuid}/stats\":{" +
                "\"get\":{" +
                "\"tags\":[\"Players\"]," +
                "\"summary\":\"Player season stats\"," +
                "\"description\":\"Returns a player's raw aggregated season stats: play time, movement distances (metres), combat counts, block/item totals, and the player's top block mined, mob killed, item crafted, item used, and death cause. Distances are floating-point metres; all other counters are integers. Returns 404 if the player has no recorded stats for the season.\"," +
                "\"operationId\":\"getPlayerStats\"," +
                "\"parameters\":[" +
                "{\"name\":\"uuid\",\"in\":\"path\",\"required\":true,\"schema\":{\"type\":\"string\",\"format\":\"uuid\"},\"description\":\"Minecraft player UUID (with dashes)\"}," +
                "{\"name\":\"season\",\"in\":\"query\",\"schema\":{\"type\":\"string\"},\"description\":\"Season ID. Defaults to the current active season.\"}" +
                "]," +
                "\"responses\":{" +
                "\"200\":{\"description\":\"Player season stats retrieved successfully\"," +
                "\"content\":{\"application/json\":{\"schema\":{\"type\":\"object\",\"properties\":{" +
                "\"uuid\":{\"type\":\"string\",\"format\":\"uuid\"}," +
                "\"username\":{\"type\":\"string\",\"nullable\":true}," +
                "\"season\":{\"type\":\"string\"}," +
                "\"stats\":{\"type\":\"object\",\"properties\":{" +
                "\"play_time_seconds\":{\"type\":\"integer\"}," +
                "\"walk_distance_m\":{\"type\":\"number\"},\"sprint_distance_m\":{\"type\":\"number\"}," +
                "\"swim_distance_m\":{\"type\":\"number\"},\"fly_distance_m\":{\"type\":\"number\"}," +
                "\"boat_distance_m\":{\"type\":\"number\"},\"elytra_distance_m\":{\"type\":\"number\"}," +
                "\"horse_distance_m\":{\"type\":\"number\"},\"climb_distance_m\":{\"type\":\"number\"}," +
                "\"fall_distance_m\":{\"type\":\"number\"},\"total_distance_m\":{\"type\":\"number\"}," +
                "\"mob_kills\":{\"type\":\"integer\"},\"player_kills\":{\"type\":\"integer\"},\"deaths\":{\"type\":\"integer\"}," +
                "\"damage_dealt\":{\"type\":\"integer\"},\"damage_taken\":{\"type\":\"integer\"}," +
                "\"total_blocks_mined\":{\"type\":\"integer\"},\"total_blocks_placed\":{\"type\":\"integer\"}," +
                "\"total_items_crafted\":{\"type\":\"integer\"},\"total_items_broken\":{\"type\":\"integer\"}," +
                "\"jumps\":{\"type\":\"integer\"},\"animals_bred\":{\"type\":\"integer\"},\"fish_caught\":{\"type\":\"integer\"}," +
                "\"villagers_traded\":{\"type\":\"integer\"},\"enchantments\":{\"type\":\"integer\"},\"times_slept\":{\"type\":\"integer\"}," +
                "\"top_block_mined\":{\"type\":\"string\",\"nullable\":true},\"top_mob_killed\":{\"type\":\"string\",\"nullable\":true}," +
                "\"top_item_crafted\":{\"type\":\"string\",\"nullable\":true},\"top_item_used\":{\"type\":\"string\",\"nullable\":true}," +
                "\"top_death_cause\":{\"type\":\"string\",\"nullable\":true}," +
                "\"computed_at\":{\"type\":\"integer\",\"nullable\":true,\"description\":\"Unix seconds when these stats were last computed\"}" +
                "}}" +
                "}}}}}," +
                "\"400\":{\"description\":\"UUID format is invalid. Must be a standard UUID with dashes.\",\"content\":{\"application/json\":{\"schema\":" +
                ERROR_SCHEMA +
                "}}}," +
                "\"404\":{\"description\":\"Player has no stat data for this season, or no season is currently active.\",\"content\":{\"application/json\":{\"schema\":" +
                ERROR_SCHEMA +
                "}}}," +
                COMMON_ERRORS +
                "}" +
                "}" +
                "}," +
                "\"/players/{uuid}/seasons\":{" +
                "\"get\":{" +
                "\"tags\":[\"Players\"]," +
                "\"summary\":\"Player seasons with stats\"," +
                "\"description\":\"Returns the list of seasons a player has recorded season-stat data for, newest first. Useful for populating a season picker. Each entry has the season ID and display name. Returns an empty array (not a 404) when the player has no season stats.\"," +
                "\"operationId\":\"getPlayerSeasons\"," +
                "\"parameters\":[" +
                "{\"name\":\"uuid\",\"in\":\"path\",\"required\":true,\"schema\":{\"type\":\"string\",\"format\":\"uuid\"},\"description\":\"Minecraft player UUID (with dashes)\"}" +
                "]," +
                "\"responses\":{" +
                "\"200\":{\"description\":\"List of seasons the player has stats for, newest first\"," +
                "\"content\":{\"application/json\":{\"schema\":{\"type\":\"object\",\"properties\":{" +
                "\"uuid\":{\"type\":\"string\",\"format\":\"uuid\"}," +
                "\"seasons\":{\"type\":\"array\",\"items\":{\"type\":\"object\",\"properties\":{" +
                "\"id\":{\"type\":\"string\"},\"name\":{\"type\":\"string\"}" +
                "}}}" +
                "}}}}}," +
                "\"400\":{\"description\":\"UUID format is invalid. Must be a standard UUID with dashes.\",\"content\":{\"application/json\":{\"schema\":" +
                ERROR_SCHEMA +
                "}}}," +
                "\"500\":{\"description\":\"Failed to query seasons\",\"content\":{\"application/json\":{\"schema\":" +
                ERROR_SCHEMA +
                "}}}," +
                COMMON_ERRORS +
                "}" +
                "}" +
                "}," +
                "\"/players/{uuid}/infractions\":{" +
                "\"get\":{" +
                "\"tags\":[\"Players\"]," +
                "\"summary\":\"Player punishment history\"," +
                "\"description\":\"Returns sanitized public LiteBans history for a player, combining bans, mutes, warnings, and kicks. IP data is never exposed. Results are newest-first and capped by the `limit` query parameter.\"," +
                "\"operationId\":\"getPlayerInfractions\"," +
                "\"parameters\":[" +
                "{\"name\":\"uuid\",\"in\":\"path\",\"required\":true,\"schema\":{\"type\":\"string\"},\"description\":\"Minecraft player UUID, with or without dashes\"}," +
                "{\"name\":\"limit\",\"in\":\"query\",\"schema\":{\"type\":\"integer\",\"default\":10,\"minimum\":1,\"maximum\":25},\"description\":\"Maximum number of infractions to return (1-25)\"}" +
                "]," +
                "\"responses\":{" +
                "\"200\":{\"description\":\"Player infraction history retrieved\"," +
                "\"content\":{\"application/json\":{\"schema\":{\"type\":\"object\",\"properties\":{" +
                "\"uuid\":{\"type\":\"string\",\"format\":\"uuid\"}," +
                "\"count\":{\"type\":\"integer\"}," +
                "\"infractions\":{\"type\":\"array\",\"items\":{\"type\":\"object\",\"properties\":{" +
                "\"type\":{\"type\":\"string\",\"enum\":[\"ban\",\"mute\",\"warning\",\"kick\"]}," +
                "\"id\":{\"type\":\"integer\"}," +
                "\"reason\":{\"type\":\"string\",\"nullable\":true}," +
                "\"staff\":{\"type\":\"string\",\"nullable\":true}," +
                "\"created_at\":{\"type\":\"integer\",\"description\":\"Unix seconds when the infraction was created\"}," +
                "\"expires_at\":{\"type\":\"integer\",\"nullable\":true}," +
                "\"active\":{\"type\":\"boolean\",\"nullable\":true}," +
                "\"removed\":{\"type\":\"boolean\"}," +
                "\"removed_by\":{\"type\":\"string\",\"nullable\":true}," +
                "\"removed_at\":{\"type\":\"integer\",\"nullable\":true}" +
                "}}}" +
                "}}}}}," +
                "\"400\":{\"description\":\"UUID format is invalid. Must be a standard UUID with dashes.\",\"content\":{\"application/json\":{\"schema\":" +
                ERROR_SCHEMA +
                "}}}," +
                "\"500\":{\"description\":\"LiteBans query failed\",\"content\":{\"application/json\":{\"schema\":" +
                ERROR_SCHEMA +
                "}}}," +
                "\"503\":{\"description\":\"LiteBans is not available on this proxy\",\"content\":{\"application/json\":{\"schema\":" +
                ERROR_SCHEMA +
                "}}}," +
                COMMON_ERRORS +
                "}" +
                "}" +
                "}," +
                "\"/punishments/active\":{" +
                "\"post\":{" +
                "\"tags\":[\"Punishments\"]," +
                "\"summary\":\"Active ban or mute lookup\"," +
                "\"description\":\"Returns the submitted Minecraft UUIDs that currently have an active LiteBans ban or mute. Warning and kick records are ignored. The endpoint accepts at most 1000 UUIDs per request and does not expose a global punishment list.\"," +
                "\"operationId\":\"getActivePunishments\"," +
                "\"requestBody\":{\"required\":true,\"content\":{\"application/json\":{\"schema\":{\"type\":\"object\",\"required\":[\"uuids\"],\"properties\":{" +
                "\"uuids\":{\"type\":\"array\",\"maxItems\":1000,\"items\":{\"type\":\"string\",\"description\":\"Minecraft player UUID, with or without dashes\"}}" +
                "}}}}}," +
                "\"responses\":{" +
                "\"200\":{\"description\":\"Active punishment state retrieved\"," +
                "\"content\":{\"application/json\":{\"schema\":{\"type\":\"object\",\"properties\":{" +
                "\"count\":{\"type\":\"integer\"}," +
                "\"punished_uuids\":{\"type\":\"array\",\"items\":{\"type\":\"string\",\"format\":\"uuid\"}}" +
                "}}}}}," +
                "\"400\":{\"description\":\"Request body or UUID format is invalid\",\"content\":{\"application/json\":{\"schema\":" +
                ERROR_SCHEMA +
                "}}}," +
                "\"405\":{\"description\":\"Method not allowed\",\"content\":{\"application/json\":{\"schema\":" +
                ERROR_SCHEMA +
                "}}}," +
                "\"429\":{\"description\":\"Rate limit exceeded\",\"content\":{\"application/json\":{\"schema\":" +
                ERROR_SCHEMA +
                "}}}," +
                "\"500\":{\"description\":\"LiteBans query failed\",\"content\":{\"application/json\":{\"schema\":" +
                ERROR_SCHEMA +
                "}}}," +
                "\"503\":{\"description\":\"LiteBans is not available on this proxy\",\"content\":{\"application/json\":{\"schema\":" +
                ERROR_SCHEMA +
                "}}}" +
                "}" +
                "}" +
                "}," +

                // ── Awards ──
                "\"/awards\":{" +
                "\"get\":{" +
                "\"tags\":[\"Awards\"]," +
                "\"summary\":\"List all awards\"," +
                "\"description\":\"Returns every enabled award definition along with the current #1 holder for each. Awards are grouped by bucket (combat, mining, crafting, building, items, food, movement, misc) and sorted by display order within each bucket.\"," +
                "\"operationId\":\"getAwards\"," +
                "\"parameters\":[" +
                "{\"name\":\"show_hidden\",\"in\":\"query\",\"schema\":{\"type\":\"boolean\",\"default\":false},\"description\":\"Include anonymous manually hidden players in ranks. Their UUIDs and nicknames are null and their username is Hidden player.\"}," +
                "{\"name\":\"season\",\"in\":\"query\",\"schema\":{\"type\":\"string\"},\"description\":\"Season ID. Defaults to the current active season.\"}" +
                "]," +
                "\"responses\":{" +
                "\"200\":{\"description\":\"Full list of awards with leader information\"," +
                "\"content\":{\"application/json\":{\"schema\":{\"type\":\"object\",\"properties\":{" +
                "\"awards\":{\"type\":\"array\",\"items\":{\"type\":\"object\",\"properties\":{" +
                "\"id\":{\"type\":\"string\"},\"title\":{\"type\":\"string\"},\"description\":{\"type\":\"string\"}," +
                "\"unit\":{\"type\":\"string\"},\"bucket\":{\"type\":\"string\"},\"icon\":{\"type\":\"string\"}," +
                "\"leader\":{\"type\":\"object\",\"nullable\":true,\"properties\":{" +
                "\"hidden\":{\"type\":\"boolean\"},\"uuid\":{\"type\":\"string\",\"nullable\":true},\"username\":{\"type\":\"string\",\"nullable\":true},\"nickname\":{\"type\":\"string\",\"nullable\":true},\"score\":{\"type\":\"number\"}" +
                "}}" +
                "}}}" +
                "}}}}}," +
                "\"404\":{\"description\":\"No season is currently active\",\"content\":{\"application/json\":{\"schema\":" +
                ERROR_SCHEMA +
                "}}}," +
                COMMON_ERRORS +
                "}" +
                "}" +
                "}," +
                "\"/awards/{id}\":{" +
                "\"get\":{" +
                "\"tags\":[\"Awards\"]," +
                "\"summary\":\"Award leaderboard\"," +
                "\"description\":\"Returns the leaderboard for a single award, showing the top players ranked by score. The response includes the award metadata (title, description, unit, icon) and a paginated list of entries. Each entry contains the player's rank, UUID, username, score, and medal (1=gold, 2=silver, 3=bronze, 0=none). Supports pagination with limit and offset.\"," +
                "\"operationId\":\"getAwardLeaderboard\"," +
                "\"parameters\":[" +
                "{\"name\":\"show_hidden\",\"in\":\"query\",\"schema\":{\"type\":\"boolean\",\"default\":false},\"description\":\"Include anonymous manually hidden players in ranks. Their UUIDs and nicknames are null and their username is Hidden player.\"}," +
                "{\"name\":\"id\",\"in\":\"path\",\"required\":true,\"schema\":{\"type\":\"string\",\"pattern\":\"^[a-z0-9_]+\$\"},\"description\":\"Award ID (e.g. aviate, kill_any, mine_diamond_ore)\"}," +
                "{\"name\":\"season\",\"in\":\"query\",\"schema\":{\"type\":\"string\"},\"description\":\"Season ID. Defaults to the current active season.\"}," +
                "{\"name\":\"limit\",\"in\":\"query\",\"schema\":{\"type\":\"integer\",\"default\":100,\"maximum\":100},\"description\":\"Maximum number of entries to return (1-100)\"}," +
                "{\"name\":\"offset\",\"in\":\"query\",\"schema\":{\"type\":\"integer\",\"default\":0},\"description\":\"Number of entries to skip for pagination\"}" +
                "]," +
                "\"responses\":{" +
                "\"200\":{\"description\":\"Award metadata and paginated leaderboard\"," +
                "\"content\":{\"application/json\":{\"schema\":{\"type\":\"object\",\"properties\":{" +
                "\"award\":{\"type\":\"object\",\"properties\":{" +
                "\"id\":{\"type\":\"string\"},\"title\":{\"type\":\"string\"},\"description\":{\"type\":\"string\"}," +
                "\"unit\":{\"type\":\"string\"},\"bucket\":{\"type\":\"string\"},\"icon\":{\"type\":\"string\"}" +
                "}}," +
                "\"leaderboard\":{\"type\":\"array\",\"items\":{\"type\":\"object\",\"properties\":{" +
                "\"rank\":{\"type\":\"integer\"},\"hidden\":{\"type\":\"boolean\"},\"uuid\":{\"type\":\"string\",\"nullable\":true},\"username\":{\"type\":\"string\",\"nullable\":true},\"nickname\":{\"type\":\"string\",\"nullable\":true}," +
                "\"score\":{\"type\":\"number\"},\"medal\":{\"type\":\"integer\",\"description\":\"1=gold, 2=silver, 3=bronze, 0=none\"}" +
                "}}}," +
                "\"total\":{\"type\":\"integer\"},\"offset\":{\"type\":\"integer\"},\"limit\":{\"type\":\"integer\"}" +
                "}}}}}," +
                "\"400\":{\"description\":\"Award ID contains invalid characters. Must be lowercase alphanumeric and underscores only.\",\"content\":{\"application/json\":{\"schema\":" +
                ERROR_SCHEMA +
                "}}}," +
                "\"404\":{\"description\":\"Award does not exist, is disabled, or no season is currently active.\",\"content\":{\"application/json\":{\"schema\":" +
                ERROR_SCHEMA +
                "}}}," +
                COMMON_ERRORS +
                "}" +
                "}" +
                "}," +
                "\"/awards/crowns\":{" +
                "\"get\":{" +
                "\"tags\":[\"Awards\"]," +
                "\"summary\":\"Crown leaderboard\"," +
                "\"description\":\"Returns the Hall of Fame leaderboard, ranking players by their crown score. The crown score is a weighted sum of medal placements across all awards: gold (1st place) = 5 points, silver (2nd) = 3 points, bronze (3rd) = 1 point. Only players with at least one medal are included. Supports pagination with limit and offset.\"," +
                "\"operationId\":\"getCrownLeaderboard\"," +
                "\"parameters\":[" +
                "{\"name\":\"show_hidden\",\"in\":\"query\",\"schema\":{\"type\":\"boolean\",\"default\":false},\"description\":\"Include anonymous manually hidden players in ranks. Identities are redacted; crown points are recalculated for this view without changing stored medals.\"}," +
                "{\"name\":\"season\",\"in\":\"query\",\"schema\":{\"type\":\"string\"},\"description\":\"Season ID. Defaults to the current active season.\"}," +
                "{\"name\":\"limit\",\"in\":\"query\",\"schema\":{\"type\":\"integer\",\"default\":100,\"maximum\":100},\"description\":\"Maximum number of entries to return (1-100)\"}," +
                "{\"name\":\"offset\",\"in\":\"query\",\"schema\":{\"type\":\"integer\",\"default\":0},\"description\":\"Number of entries to skip for pagination\"}" +
                "]," +
                "\"responses\":{" +
                "\"200\":{\"description\":\"Paginated crown score leaderboard\"," +
                "\"content\":{\"application/json\":{\"schema\":{\"type\":\"object\",\"properties\":{" +
                "\"leaderboard\":{\"type\":\"array\",\"items\":{\"type\":\"object\",\"properties\":{" +
                "\"rank\":{\"type\":\"integer\"},\"hidden\":{\"type\":\"boolean\"},\"uuid\":{\"type\":\"string\",\"nullable\":true},\"username\":{\"type\":\"string\",\"nullable\":true},\"nickname\":{\"type\":\"string\",\"nullable\":true}," +
                "\"gold\":{\"type\":\"integer\"},\"silver\":{\"type\":\"integer\"},\"bronze\":{\"type\":\"integer\"}," +
                "\"crown_score\":{\"type\":\"integer\"}" +
                "}}}," +
                "\"total\":{\"type\":\"integer\"},\"offset\":{\"type\":\"integer\"},\"limit\":{\"type\":\"integer\"}" +
                "}}}}}," +
                "\"404\":{\"description\":\"No season is currently active\",\"content\":{\"application/json\":{\"schema\":" +
                ERROR_SCHEMA +
                "}}}," +
                COMMON_ERRORS +
                "}" +
                "}" +
                "}," +

                // ── Advancements ──
                "\"/advancements/leaderboard\":{" +
                "\"get\":{" +
                "\"tags\":[\"Advancements\"]," +
                "\"summary\":\"Advancement leaderboard\"," +
                "\"description\":\"Returns a global leaderboard ranking players by the number of Minecraft advancements they have completed. Only players with at least one completed advancement are included. Supports pagination with limit and offset.\"," +
                "\"operationId\":\"getAdvancementLeaderboard\"," +
                "\"parameters\":[" +
                "{\"name\":\"show_hidden\",\"in\":\"query\",\"schema\":{\"type\":\"boolean\",\"default\":false},\"description\":\"Include anonymous manually hidden players in ranks. Their UUIDs and nicknames are null and their username is Hidden player.\"}," +
                "{\"name\":\"season\",\"in\":\"query\",\"schema\":{\"type\":\"string\"},\"description\":\"Season ID. Defaults to the current active season.\"}," +
                "{\"name\":\"limit\",\"in\":\"query\",\"schema\":{\"type\":\"integer\",\"default\":100,\"maximum\":100},\"description\":\"Maximum number of entries to return (1-100)\"}," +
                "{\"name\":\"offset\",\"in\":\"query\",\"schema\":{\"type\":\"integer\",\"default\":0},\"description\":\"Number of entries to skip for pagination\"}," +
                "{\"name\":\"category\",\"in\":\"query\",\"schema\":{\"type\":\"string\",\"enum\":[\"story\",\"nether\",\"end\",\"adventure\",\"husbandry\"]},\"description\":\"Filter by advancement category. Omit for overall leaderboard.\"}" +
                "]," +
                "\"responses\":{" +
                "\"200\":{\"description\":\"Paginated advancement completion leaderboard\"," +
                "\"content\":{\"application/json\":{\"schema\":{\"type\":\"object\",\"properties\":{" +
                "\"leaderboard\":{\"type\":\"array\",\"items\":{\"type\":\"object\",\"properties\":{" +
                "\"rank\":{\"type\":\"integer\"},\"hidden\":{\"type\":\"boolean\"},\"uuid\":{\"type\":\"string\",\"nullable\":true},\"username\":{\"type\":\"string\",\"nullable\":true},\"nickname\":{\"type\":\"string\",\"nullable\":true}," +
                "\"completed\":{\"type\":\"integer\"}" +
                "}}}," +
                "\"total\":{\"type\":\"integer\"},\"totalAdvancements\":{\"type\":\"integer\"}," +
                "\"category\":{\"type\":\"string\",\"description\":\"Present only when filtering by category\"}," +
                "\"offset\":{\"type\":\"integer\"},\"limit\":{\"type\":\"integer\"}" +
                "}}}}}," +
                "\"404\":{\"description\":\"No season is currently active\",\"content\":{\"application/json\":{\"schema\":" +
                ERROR_SCHEMA +
                "}}}," +
                COMMON_ERRORS +
                "}" +
                "}" +
                "}," +

                // ── Streaks ──
                "\"/players/{uuid}/streak\":{" +
                "\"get\":{" +
                "\"tags\":[\"Streaks\"]," +
                "\"summary\":\"Player login streak\"," +
                "\"description\":\"Returns the all-time login streak for a player. The streak counts days where the player has enough online time to qualify, where a day rolls over at 06:00 UTC; a single missed day is forgiven but two missed days in a row reset it to 1. The streak is `active` while `now < expires_at`; once it lapses the response shows `current_streak: 0` (the prior run is preserved as `pending_streak` until the next qualified day). `longest_streak` records the player's all-time best.\"," +
                "\"operationId\":\"getPlayerStreak\"," +
                "\"parameters\":[" +
                "{\"name\":\"uuid\",\"in\":\"path\",\"required\":true,\"schema\":{\"type\":\"string\",\"format\":\"uuid\"},\"description\":\"Minecraft player UUID (with dashes)\"}" +
                "]," +
                "\"responses\":{" +
                "\"200\":{\"description\":\"Streak data retrieved\"," +
                "\"content\":{\"application/json\":{\"schema\":{\"type\":\"object\",\"properties\":{" +
                "\"uuid\":{\"type\":\"string\",\"format\":\"uuid\"}," +
                "\"current_streak\":{\"type\":\"integer\",\"description\":\"Live streak. 0 if the streak has lapsed since the last qualified day.\"}," +
                "\"pending_streak\":{\"type\":\"integer\",\"description\":\"Streak value before the active/lapsed check. Equals current_streak when active.\"}," +
                "\"longest_streak\":{\"type\":\"integer\"}," +
                "\"last_login_at\":{\"type\":\"integer\",\"description\":\"Unix seconds when the player last qualified for a streak day\"}," +
                "\"streak_started_at\":{\"type\":\"integer\",\"description\":\"Unix seconds when the current run began\"}," +
                "\"expires_at\":{\"type\":\"integer\",\"description\":\"Unix seconds; the streak lapses (and resets on next qualified day) if the player does not qualify by this time, i.e. after two consecutive missed days\"}," +
                "\"active\":{\"type\":\"boolean\"}" +
                "}}}}}," +
                "\"400\":{\"description\":\"UUID format is invalid\",\"content\":{\"application/json\":{\"schema\":" +
                ERROR_SCHEMA +
                "}}}," +
                "\"404\":{\"description\":\"Player has no streak data yet\",\"content\":{\"application/json\":{\"schema\":" +
                ERROR_SCHEMA +
                "}}}," +
                "\"503\":{\"description\":\"Streak service is unavailable\",\"content\":{\"application/json\":{\"schema\":" +
                ERROR_SCHEMA +
                "}}}," +
                COMMON_ERRORS +
                "}" +
                "}" +
                "}," +
                "\"/streaks/leaderboard\":{" +
                "\"get\":{" +
                "\"tags\":[\"Streaks\"]," +
                "\"summary\":\"Login streak leaderboard\"," +
                "\"description\":\"Returns players ranked by their current or longest login streak. Use `metric=longest` for the all-time best leaderboard; the default ranks by current streak and only includes streaks that are still active (lapsed streaks are excluded). Each entry includes the player's UUID, username, current and longest streaks, and whether their streak is still active. Alt accounts are excluded. Supports pagination with limit and offset.\"," +
                "\"operationId\":\"getStreakLeaderboard\"," +
                "\"parameters\":[" +
                "{\"name\":\"metric\",\"in\":\"query\",\"schema\":{\"type\":\"string\",\"enum\":[\"current\",\"longest\"],\"default\":\"current\"},\"description\":\"Which streak to rank by\"}," +
                "{\"name\":\"limit\",\"in\":\"query\",\"schema\":{\"type\":\"integer\",\"default\":100,\"maximum\":100},\"description\":\"Maximum number of entries to return (1-100)\"}," +
                "{\"name\":\"offset\",\"in\":\"query\",\"schema\":{\"type\":\"integer\",\"default\":0},\"description\":\"Number of entries to skip for pagination\"}" +
                "]," +
                "\"responses\":{" +
                "\"200\":{\"description\":\"Paginated streak leaderboard\"," +
                "\"content\":{\"application/json\":{\"schema\":{\"type\":\"object\",\"properties\":{" +
                "\"metric\":{\"type\":\"string\",\"enum\":[\"current\",\"longest\"]}," +
                "\"leaderboard\":{\"type\":\"array\",\"items\":{\"type\":\"object\",\"properties\":{" +
                "\"rank\":{\"type\":\"integer\"},\"uuid\":{\"type\":\"string\"},\"username\":{\"type\":\"string\",\"nullable\":true}," +
                "\"current_streak\":{\"type\":\"integer\"},\"pending_streak\":{\"type\":\"integer\"},\"longest_streak\":{\"type\":\"integer\"}," +
                "\"last_login_at\":{\"type\":\"integer\"},\"streak_started_at\":{\"type\":\"integer\"},\"active\":{\"type\":\"boolean\"}" +
                "}}}," +
                "\"total\":{\"type\":\"integer\"},\"offset\":{\"type\":\"integer\"},\"limit\":{\"type\":\"integer\"}" +
                "}}}}}," +
                "\"503\":{\"description\":\"Streak service is unavailable\",\"content\":{\"application/json\":{\"schema\":" +
                ERROR_SCHEMA +
                "}}}," +
                COMMON_ERRORS +
                "}" +
                "}" +
                "}" +
                "}" +
                "}"

        private val SCALAR_HTML =
            "<!doctype html>\n" +
                "<html>\n" +
                "  <head>\n" +
                "    <meta charset=\"UTF-8\" />\n" +
                "    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\" />\n" +
                "    <title>CrabCraft API Docs</title>\n" +
                "    <style>html,body{margin:0;padding:0;}</style>\n" +
                "  </head>\n" +
                "  <body>\n" +
                "    <script id=\"api-reference\" type=\"application/json\">\n" +
                OPENAPI_JSON +
                "\n" +
                "    </script>\n" +
                "    <script src=\"https://cdn.jsdelivr.net/npm/@scalar/api-reference\"></script>\n" +
                "    <noscript>JavaScript is required to render the API documentation. You can still access /openapi.json and /players directly.</noscript>\n" +
                "  </body>\n" +
                "</html>\n"
    }
}
