package crabcraft.net.crabUtilities.velocity

import redis.clients.jedis.JedisPool
import redis.clients.jedis.JedisPoolConfig

object RedisPools {
    private const val TIMEOUT_MS = 2_000

    @JvmStatic fun create(config: VelocityConfig): JedisPool = create(config, JedisPoolConfig())

    @JvmStatic
    fun create(config: VelocityConfig, maxTotal: Int): JedisPool =
        create(config, JedisPoolConfig().apply { setMaxTotal(maxTotal) })

    @JvmStatic
    fun create(config: VelocityConfig, poolConfig: JedisPoolConfig): JedisPool {
        val password = config.getRedisPassword()
        return if (!password.isNullOrEmpty()) {
            JedisPool(poolConfig, config.getRedisHost(), config.getRedisPort(), TIMEOUT_MS, password)
        } else {
            JedisPool(poolConfig, config.getRedisHost(), config.getRedisPort(), TIMEOUT_MS)
        }
    }
}
