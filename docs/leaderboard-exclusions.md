# Moderator award exclusions

Moderators can use these Discord commands:

- `/leaderboard exclude user:<member>` hides the user's linked player from public awards and the global awards leaderboard across all seasons.
- `/leaderboard include user:<member>` restores their eligibility. The usual membership, activity and alt-account rules still apply.

The switch covers award leaders, individual award leaderboards, the global crown leaderboard and a player's public award holdings. It applies to the website and the public API used by the Discord leaderboard. It does not delete scores or stop tracking new progress. Other player profile information, advancements and login streaks are unaffected.

The commands require the configured moderator role, check it at execution time and reply privately. The setting belongs to the registered Discord player record, so it survives Minecraft account relinking and membership synchronisation. Unregistered users are rejected.

Changing the setting reassigns medals for every season in the same database transaction. Excluded players do not occupy podium positions. Including them again uses their saved scores, including progress earned while excluded.

Public pages reflect the change after their normal cache refresh. The existing Discord leaderboard message refreshes within five minutes; `/leaderboard refresh` updates it immediately.

## Deployment

The Drizzle schema and Velocity startup migration both define `players.awards_excluded` as `BOOLEAN NOT NULL DEFAULT FALSE`. Existing players remain eligible by default.

Deploy and start the updated Velocity plugin before updating the bot and website. Velocity adds the column before starting its database consumers. Alternatively, apply this narrow migration before deploying any of the updated services:

```sql
ALTER TABLE players
    ADD COLUMN IF NOT EXISTS awards_excluded BOOLEAN NOT NULL DEFAULT FALSE;
```

Restart the bot to register the new slash-command subcommands. No player-save migration or score reset is needed.
