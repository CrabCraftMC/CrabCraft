package crabcraft.net.crabUtilities.velocity.awards;

import com.google.gson.annotations.SerializedName;

import java.util.List;

public class AwardDefinition {
    public String id;
    public Reader reader;

    public static class Reader {
        @SerializedName("$type")
        public String type;

        /**
         * Path inside the reader's source object: vanilla stats for standard
         * readers, plugin-provided metrics for {@code custom-int}, or the full
         * stats payload for {@code set-count}.
         */
        public List<String> path;

        /** For match-sum readers: regex patterns whose matching keys are summed. */
        public List<String> patterns;
    }
}
