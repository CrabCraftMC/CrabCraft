package crabcraft.net.crabUtilities.velocity.awards

/** Shared eligibility rules for award queries using the `scores` alias. */
internal object AwardEligibility {
    private val FILTER_SQL =
        """
        AND NOT EXISTS (
            SELECT 1 FROM player_alts alt
            WHERE alt.minecraft_uuid = scores.minecraft_uuid
        )
        AND EXISTS (
            SELECT 1 FROM players eligible_player
            WHERE eligible_player.minecraft_uuid = scores.minecraft_uuid
              AND eligible_player.is_discord_member = true
              AND %s
              AND eligible_player.last_mc_login_at >=
                  EXTRACT(EPOCH FROM NOW())::INTEGER - 2592000
        )
        """
            .trimIndent() + "\n"

    @JvmField val PUBLIC_SCORES = FILTER_SQL.format("eligible_player.awards_excluded = false")
    @JvmField val WITH_VISIBILITY = FILTER_SQL.format("(? OR eligible_player.awards_excluded = false)")
}
