package crabcraft.net.crabUtilities.velocity.api

import com.google.gson.JsonObject
import java.sql.ResultSet
import java.sql.SQLException

/** Redacts identities before optional hidden entries reach a public response. */
class LeaderboardIdentity private constructor() {
    companion object {
        @JvmStatic
        @Throws(SQLException::class)
        fun addTo(entry: JsonObject, row: ResultSet) {
            val hidden = row.getBoolean("hidden")
            entry.addProperty("hidden", hidden)
            entry.addProperty("uuid", if (hidden) null else row.getString("minecraft_uuid"))
            entry.addProperty("username", if (hidden) "Hidden player" else row.getString("minecraft_username"))
            entry.addProperty("nickname", if (hidden) null else row.getString("nickname"))
        }
    }
}
