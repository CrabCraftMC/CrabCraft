package crabcraft.net.crabUtilities.velocity.awards

import com.google.gson.annotations.SerializedName

open class AwardDefinition {
    @JvmField var id: String? = null
    @JvmField var reader: Reader? = null

    open class Reader {
        @SerializedName("\$type")
        @JvmField var type: String? = null

        /**
         * Path inside the reader's source object: vanilla stats for standard
         * readers, plugin-provided metrics for {@code custom-int}, or the full
         * stats payload for {@code set-count}.
         */
        @JvmField var path: List<String>? = null

        /** For match-sum readers: regex patterns whose matching keys are summed. */
        @JvmField var patterns: List<String>? = null
    }
}
