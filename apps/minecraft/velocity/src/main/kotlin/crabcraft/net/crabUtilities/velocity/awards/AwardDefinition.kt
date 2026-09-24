package crabcraft.net.crabUtilities.velocity.awards

import com.google.gson.annotations.SerializedName

open class AwardDefinition {
    @JvmField var id: String? = null
    @JvmField var reader: Reader? = null

    open class Reader {
        @SerializedName("\$type") @JvmField var type: String? = null
        /** Path within the reader's vanilla stats, custom metrics or full payload. */
        @JvmField var path: List<String>? = null
        /** Regex patterns for match-sum readers. */
        @JvmField var patterns: List<String>? = null
    }
}
