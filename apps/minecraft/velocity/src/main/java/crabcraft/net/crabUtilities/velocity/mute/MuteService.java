package crabcraft.net.crabUtilities.velocity.mute;

import java.util.Optional;
import java.util.UUID;

/**
 * Ties {@link MuteStore} (Postgres) and {@link RedisMutePublisher} (live
 * cache + pub/sub) together. The proxy mutes/unmutes through this; the
 * persisted state in Postgres is authoritative and the Redis hash is the
 * cache the backends enforce from.
 */
public final class MuteService {

    private final MuteStore store;
    private final RedisMutePublisher redis;

    public MuteService(MuteStore store, RedisMutePublisher redis) {
        this.store = store;
        this.redis = redis;
    }

    /** Creates the table and rehydrates Redis from the active mutes in Postgres. */
    public void init() {
        store.init();
        redis.rehydrate(store.getActiveMutes());
    }

    /**
     * Mutes a player.
     *
     * @param durationMillis {@code <= 0} for a permanent mute, otherwise
     *        the mute lapses {@code durationMillis} from now
     */
    public void mute(UUID uuid, long durationMillis, String reason, String mutedBy) {
        long expiry = durationMillis <= 0 ? 0L : System.currentTimeMillis() + durationMillis;
        store.setMute(uuid, expiry, reason, mutedBy);
        redis.applyMute(uuid, expiry);
    }

    public void unmute(UUID uuid) {
        store.removeMute(uuid);
        redis.clearMute(uuid);
    }

    public MuteStore.Mute getMute(UUID uuid) {
        return store.getMute(uuid);
    }

    public Optional<UUID> lookupUuidByUsername(String username) {
        return store.lookupUuidByUsername(username);
    }

    /** @return true if the mute exists and isn't permanent and has lapsed. */
    public static boolean isExpired(MuteStore.Mute mute) {
        if (mute == null) return true;
        if (mute.expiry() == 0L) return false;
        return mute.expiry() <= System.currentTimeMillis();
    }

    /** @return true if the player has an active (non-expired) mute. */
    public boolean isMuted(UUID uuid) {
        return !isExpired(store.getMute(uuid));
    }
}
