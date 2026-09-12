package crabcraft.net.crabUtilities.bingo

import com.google.gson.JsonParser

data class BingoActiveCard(
    private val id: Int,
    private val number: Int,
    private val startsAt: Long,
    private val endsAt: Long,
    private val taskIds: Set<String>,
) {
    fun id(): Int = id
    fun number(): Int = number
    fun startsAt(): Long = startsAt
    fun endsAt(): Long = endsAt
    fun taskIds(): Set<String> = taskIds

    fun isLive(): Boolean {
        val now = System.currentTimeMillis() / 1_000L
        return startsAt <= now && now < endsAt
    }

    fun contains(task: BingoTask): Boolean = taskIds.contains(task.id())

    companion object {
        @JvmStatic
        fun fromJson(json: String): BingoActiveCard {
            val obj = JsonParser.parseString(json).asJsonObject
            val taskIds = HashSet<String>()
            obj.getAsJsonArray("taskIds").forEach { task -> taskIds.add(task.asString) }
            return BingoActiveCard(
                obj.get("id").asInt,
                obj.get("number").asInt,
                obj.get("startsAt").asLong,
                obj.get("endsAt").asLong,
                java.util.Set.copyOf(taskIds),
            )
        }
    }
}
