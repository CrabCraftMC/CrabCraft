package crabcraft.net.crabUtilities.velocity

import redis.clients.jedis.JedisPool
import redis.clients.jedis.JedisPoolConfig

class RedisPools private constructor() {
    companion object {
        private const val TIMEOUT_MS = 2_000
        @JvmStatic fun create(config: VelocityConfig): JedisPool = create(config, JedisPoolConfig())
        @JvmStatic fun create(config: VelocityConfig, maxTotal: Int): JedisPool {
            val poolConfig = JedisPoolConfig()
            poolConfig.maxTotal = maxTotal
            return create(config, poolConfig)
        }
        @JvmStatic fun create(config: VelocityConfig, poolConfig: JedisPoolConfig): JedisPool {
            val password = config.getRedisPassword()
            if (!password.isNullOrEmpty()) return JedisPool(poolConfig, config.getRedisHost(), config.getRedisPort(), TIMEOUT_MS, password)
            return JedisPool(poolConfig, config.getRedisHost(), config.getRedisPort(), TIMEOUT_MS)
        }
    }
}
