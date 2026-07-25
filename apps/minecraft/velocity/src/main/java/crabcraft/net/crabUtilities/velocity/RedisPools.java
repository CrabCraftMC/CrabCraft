package crabcraft.net.crabUtilities.velocity;

import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

public final class RedisPools {

    private static final int TIMEOUT_MS = 2_000;

    private RedisPools() {}

    public static JedisPool create(VelocityConfig config) {
        return create(config, new JedisPoolConfig());
    }

    public static JedisPool create(VelocityConfig config, int maxTotal) {
        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(maxTotal);
        return create(config, poolConfig);
    }

    public static JedisPool create(VelocityConfig config, JedisPoolConfig poolConfig) {
        String password = config.getRedisPassword();
        if (password != null && !password.isEmpty()) {
            return new JedisPool(poolConfig, config.getRedisHost(),
                    config.getRedisPort(), TIMEOUT_MS, password);
        }
        return new JedisPool(poolConfig, config.getRedisHost(),
                config.getRedisPort(), TIMEOUT_MS);
    }
}
