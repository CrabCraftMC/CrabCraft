package crabcraft.net.crabUtilities.velocity;

import com.posthog.server.PostHog;
import com.posthog.server.PostHogCaptureOptions;
import com.posthog.server.PostHogConfig;
import com.posthog.server.PostHogInterface;
import org.slf4j.Logger;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

/**
 * Best-effort, personless PostHog analytics for proxy events. PostgreSQL
 * remains authoritative; analytics failure must never affect gameplay.
 */
public final class AnalyticsService implements AutoCloseable {

    public static final String PLAYER_JOINED = "player joined";
    public static final String PLAYER_SESSION_ENDED = "player session ended";
    public static final String SERVER_SWITCHED = "server switched";
    public static final String LOGIN_DAY_QUALIFIED = "login day qualified";
    public static final String PLAYER_SETTING_CHANGED = "player setting changed";

    private final Logger logger;
    private final String personSalt;
    private final String environment;
    private final PostHogInterface client;
    private volatile boolean closed;

    public AnalyticsService(VelocityConfig config, Logger logger) {
        this.logger = logger;
        this.personSalt = normalise(config.getAnalyticsPersonSalt());
        this.environment = normaliseOr(config.getAnalyticsEnvironment(), "production");

        String projectToken = normalise(config.getAnalyticsProjectToken());
        if (!config.isAnalyticsEnabled()) {
            this.client = null;
            return;
        }
        if (projectToken.isEmpty() || personSalt.isEmpty()) {
            logger.warn("PostHog analytics disabled: analytics.project-token and analytics.person-salt are required.");
            this.client = null;
            return;
        }

        PostHogInterface configuredClient = null;
        try {
            PostHogConfig postHogConfig = PostHogConfig.builder(projectToken)
                    .host(normaliseOr(config.getAnalyticsHost(), "https://eu.i.posthog.com"))
                    .flushAt(20)
                    .flushIntervalSeconds(10)
                    .captureUncaughtExceptions(false)
                    .build();
            configuredClient = PostHog.with(postHogConfig);
            logger.info("PostHog analytics enabled for environment '{}'.", environment);
        } catch (Exception e) {
            logger.warn("PostHog analytics disabled after SDK initialisation failed: {}",
                    e.getMessage());
        }
        this.client = configuredClient;
    }

    public boolean isEnabled() {
        return client != null && !closed;
    }

    public void capture(UUID minecraftUuid, String event, Map<String, ?> properties) {
        capture(minecraftUuid, event, properties, null);
    }

    public void capture(UUID minecraftUuid, String event, Map<String, ?> properties,
                        String dedupeKey) {
        PostHogInterface activeClient = client;
        if (activeClient == null || closed
                || minecraftUuid == null || event == null || event.isBlank()) {
            return;
        }
        String distinctId = analyticsId(minecraftUuid.toString(), personSalt);
        if (distinctId == null) return;

        try {
            Map<String, Object> safeProperties = new HashMap<>();
            if (properties != null) safeProperties.putAll(properties);
            safeProperties.put("source", "velocity");
            safeProperties.put("environment", environment);
            safeProperties.put("$process_person_profile", false);
            safeProperties.put("$geoip_disable", true);
            if (dedupeKey != null && !dedupeKey.isBlank()) {
                safeProperties.put("$insert_id", sha256(
                        distinctId + ":" + event + ":" + dedupeKey));
            }

            activeClient.capture(
                    distinctId,
                    event,
                    PostHogCaptureOptions.builder().properties(safeProperties).build());
        } catch (Exception e) {
            logger.debug("PostHog capture failed: {}", e.getMessage());
        }
    }

    /** Server-side feature-flag lookup using the same pseudonymous identity. */
    public boolean isFeatureEnabled(UUID minecraftUuid, String flag, boolean fallback) {
        PostHogInterface activeClient = client;
        if (activeClient == null || closed
                || minecraftUuid == null || flag == null || flag.isBlank()) {
            return fallback;
        }
        String distinctId = analyticsId(minecraftUuid.toString(), personSalt);
        if (distinctId == null) return fallback;
        try {
            return activeClient.isFeatureEnabled(distinctId, flag, fallback);
        } catch (Exception e) {
            logger.debug("PostHog feature flag '{}' failed: {}", flag, e.getMessage());
            return fallback;
        }
    }

    static String analyticsId(String minecraftUuid, String salt) {
        if (minecraftUuid == null || salt == null || salt.isBlank()) return null;
        String canonical = minecraftUuid.trim().toLowerCase().replace("-", "");
        if (!canonical.matches("[0-9a-f]{32}")) return null;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(salt.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(
                    ("minecraft:" + canonical).getBytes(StandardCharsets.UTF_8));
            return "cc_" + HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            return null;
        }
    }

    private static String sha256(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest);
    }

    private static String normalise(String value) {
        return value == null ? "" : value.trim();
    }

    private static String normaliseOr(String value, String fallback) {
        String normalised = normalise(value);
        return normalised.isEmpty() ? fallback : normalised;
    }

    @Override
    public void close() {
        if (client == null || closed) return;
        closed = true;
        try {
            client.flush();
        } catch (Exception e) {
            logger.warn("PostHog analytics did not flush cleanly: {}", e.getMessage());
        } finally {
            try {
                client.close();
            } catch (Exception e) {
                logger.warn("PostHog analytics did not close cleanly: {}", e.getMessage());
            }
        }
    }
}
