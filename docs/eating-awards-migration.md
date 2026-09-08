# Eating awards and historical corrections

Nine eating awards now read persistent consumption counters: `eat_bread`, `eat_cookie`, `eat_fish`, `eat_junkfood`, `eat_meat`, `eat_rawmeat`, `eat_soup`, `eat_veggie` and `eat_sweet_berries`. `eat_cake` continues to use Minecraft’s cake-slice statistic. Drink, harvest and other awards are outside this change.

Completed, uncancelled `PlayerItemConsumeEvent` events add one to each matching award. The event’s final item type is used. Cooked fish means cooked cod/salmon; cooked meat includes the five cooked meats and rabbit stew. Raw meat has its own award. Green Diet retains its existing vegetarian food list and includes cake slices. Planting, composting, feeding mobs, placing cakes and storing items in pots do not produce consumption credits.

**Existing players are not reset.** At the first tracked join or online scan, the plugin records a per-player cutoff, a snapshot of relevant item-use counts, and the cake count. New meals are saved separately in `BukkitValues["crabutilities:eating_awards_v1"]`. Where all relevant historical item-use counts are zero, a zero food baseline is safe; known cake slices are included for Green Diet. Otherwise the historical baseline is left pending and that award is omitted from updates, preserving the existing leaderboard score until verified history is imported. A pending award’s displayed score will not advance yet, but its new meals are still recorded.

This does not reconstruct unknown historical meals. A planting-only subtraction is insufficient evidence of an eating-only total because composting and decorated-pot insertion can remain. Historical entries must be independently verified for the period before that player’s recorded `trackingStartedAt` cutoff. Blank entries stay pending. Do not turn unknown values into zeros.

**Rollout**

1. Build and deploy the Spigot plugin with the tracker. It starts recording new meals independently of the current database readers. Players who are offline initialise on their next join; they have no tracking manifest until then.
2. Preview the narrowly scoped reader migration from the repository root:

   ```sh
   bun packages/db/scripts/migrate-eating-awards.ts
   ```

   Apply it with the normal `DATABASE_URL` environment and `--apply`, then restart Velocity so its evaluator reloads the nine definitions. The script changes only these reader definitions and descriptions, preserves enabled flags and scores, and requires all nine rows to exist. No PostgreSQL schema changes are needed.
3. Stop the Minecraft server cleanly before exporting or importing player saves. This saves newly tracked meals and prevents in-memory player data from overwriting the import. Work on the authoritative world directory, not an old copy. The tool acquires the world’s `session.lock` and refuses a running server.
4. From `apps/minecraft`, export a manifest:

   ```sh
   ./gradlew :spigot:eatingHistory -Pworld=/absolute/path/to/world -Phistory=/absolute/path/to/eating-history.json -PexportHistory
   ```

   The export uses `CREATE_NEW` so it cannot overwrite a prepared manifest. It includes each initialised player’s UUID, `trackingStartedAt`, `cakeSlicesAtStart` and `itemUsesAtStart`. The `historicalScores` object is empty. Supply verified totals keyed by eating-award ID and describe the evidence in `source`. Preserve the cutoff unchanged. Item-use snapshots are evidence for recovery, not automatically verified meals.

   `eat_veggie` historical totals must include the cake slices already eaten at the cutoff. The importer checks that this total is not below `cakeSlicesAtStart`. Newly eaten cake slices are added separately, so historical cake is counted once.
5. Preview the prepared import, then apply it:

   ```sh
   ./gradlew :spigot:eatingHistory -Pworld=/absolute/path/to/world -Phistory=/absolute/path/to/eating-history.json
   ./gradlew :spigot:eatingHistory -Pworld=/absolute/path/to/world -Phistory=/absolute/path/to/eating-history.json -PapplyHistory
   ```

   Every supplied row is validated before writes. Wrong cutoffs, duplicate players, non-eating award IDs, negative/fractional counts, missing evidence descriptions and uninitialised saves are rejected. Empty `historicalScores` objects are skipped. The importer backs up all affected `.dat` files into a new `eating-history-backup-*` directory, then replaces each file atomically. Re-running the same import replaces only the historical baseline; it never resets or adds the tracked meal counter. An interrupted multi-player import can therefore be re-run safely against the same stopped world.
6. Restart Minecraft. Its next stats push publishes `verified historical total + tracked meals + new cake slices where applicable`, replacing the old score through the existing absolute-score writer. Velocity recomputes medals normally.

The importer deliberately updates the persistent source of the score rather than issuing a temporary database-only score edit that the next stats push could overwrite. Keep the prepared manifest and backups with the correction evidence. Never restore a pre-import player backup after players have resumed playing without accounting for their subsequent progress.

**Verification**

The focused regression checks cover cancelled/replaced consumption, food-group overlap, raw versus cooked food, pending history, cake deltas, online/offline parity, preview mode, world locking, incorrect cutoffs, invalid totals and idempotent baseline imports. All example counts and identities in those checks are synthetic. No live historical totals are embedded in the implementation.
