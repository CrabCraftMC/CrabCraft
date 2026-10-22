# Halloween challenge

The 2026 event runs from **00:00 Thursday 24 September (BST)** through the end
of **Saturday 31 October (GMT)**. The exclusive end is 00:00 on 1 November.
It runs independently of weekly bingo.

Players must complete all three challenges on one Minecraft account:

- **Pumpkin Head’s Hunt:** Kill a zombie, skeleton, spider, creeper and witch
  while wearing a carved pumpkin. Duplicate mob types do not count. Death resets
  an unfinished hunt, even without the pumpkin or in an excluded world.
  Completed hunts stay completed. Only the five exact mob types count, not
  variants such as husks, strays or cave spiders.
- **Trick or Treat:** Place a pressure plate directly beside a bell and get a
  creeper to step on it and ring the bell. The player who placed the plate gets
  credit and must be online. As with bingo, replace the plate after a backend
  restart or plugin reload so its ownership is tracked again.
- **Back from the Dead:** Cure a zombie villager. The player who started the
  cure must be online in Survival and wearing a carved pumpkin when it finishes.

Completed challenges and recorded hunt progress persist across logouts and
restarts. Taking off the pumpkin does not reset the hunt, but kills without it
do not count. Progress is separate for each Minecraft account; a linked main or
alt account can earn the role and its own permanent tag. `/halloween` in Minecraft and Discord shows the rules and progress. The Minecraft
command works before Discord linking and replies only to the issuing player.
It shows a four-line checklist; hover over a task for its rules and remaining mobs.
Progress may take a few seconds to update. Each newly completed task sends a
private orange, gold and green chat message to the player after progress is saved.
Repeated actions do not announce completed tasks again. These live notifications
are not replayed after a disconnect; `/halloween` always shows saved progress.
Completing the third task adds a single yellow message describing the earned
rewards. The **Halloween 2026** pumpkin tag is unlocked in the same PostgreSQL
transaction as completion, even for offline or unlinked accounts. Equip it with
`/tags`, `/settings tags` or `/settings` → **Tags**; new unlocks do not change the
selected tag. It remains owned after the event and across seasons. Discord role
delivery is separate. No event-start messages are sent.

## Outstanding rollout issues

- Paper keeps unpublished actions in memory. A restart or reload during a Redis
  outage can lose a completion or death reset; this needs a durable outbox.
- Redis stream order is arrival order across backends. A delayed death from one
  backend can arrive after a later kill on another and incorrectly finish the
  hunt. Per-player ordering across backends needs addressing before rollout.

## Setup

1. Deploy and restart the updated Velocity plugin first. It creates
   `halloween_events`, `halloween_player_progress` and the four tag tables, then
   upserts the reviewed catalogue from `packages/db/seeds/tags.json`. The same
   definitions are included in the Drizzle schema. No `db:push` is needed for this rollout.
2. Set `roles.halloween` in the bot’s `config.json` to the reward role ID. Give
   the bot Manage Roles and position its highest role above the reward role.
3. The bot configuration defaults to the agreed 2026 schedule. These optional
   fields, also in `config.example.json`, control the event:

   ```json
   "halloween": {
     "enabled": true,
     "eventId": "halloween-2026",
     "startsAt": 1790204400,
     "endsAt": 1793491200
   }
   ```

4. Deploy the Paper plugin on each participating backend and set
   `halloween.enabled: true` in its core `config.yml`. Configure
   `halloween.excluded-worlds` if needed. It uses the existing Redis connection.
   Restart or run `/crabutilities reload`.
5. Restart the bot to load configuration and register `/halloween`.
6. Backfill previously completed accounts after the tag catalogue is available:

   ```sh
   bun packages/db/scripts/backfill-halloween-tags.ts --event halloween-2026 --dry-run
   bun packages/db/scripts/backfill-halloween-tags.ts --event halloween-2026 --apply
   ```

   Set `DATABASE_URL` through the normal deployment environment. The preview
   reports eligible, new, already-awarded and already-owned counts. Apply keeps
   each account's original completion date and current selection. Re-running it
   cannot duplicate an award or restore an automatic award revoked by staff.
   An account already granted the tag manually still records its first automatic
   award receipt, so a later revocation remains effective.

The bot publishes the schedule via `crabcraft:halloween:active-event`. Paper
streams eligible actions through `crabcraft:halloween:actions`; one bot consumer
processes them in stream order and stores progress in PostgreSQL. Start is
inclusive and end exclusive, checked both on Paper and when recording progress.
Use a new event ID for a future event to keep past progress separate.
The 2026 pumpkin hook is explicitly scoped to `halloween-2026`; future event IDs
need their own reviewed catalogue and award hook. After a tag award commits,
the worker publishes the UUID to `crabcraft:tags:invalidate`. Missed invalidations
are repaired by the proxy's periodic online refresh within 30 seconds.

The role is awarded only after all three challenges are complete. Delivery
retries every 30 seconds, including after the event ends. A missing role ID,
unlinked Discord account, absent guild member or role permission failure leaves
the reward pending. Setting the role ID and restarting the bot, or linking the
account, allows a later retry to deliver it. No role is automatically removed.

## Verification

```sh
bun run --cwd apps/bot build
bun test apps/bot/tests/halloween.test.ts
cd apps/minecraft && ./gradlew test
```

On a staging server with a separate event ID and a current event window, check
that duplicate kills leave the hunt count unchanged; death clears a partial
hunt; a finished hunt survives death; another player cannot claim the placed
plate; and a cure only counts for the converter wearing the pumpkin at finish.
Check `/halloween` after reconnecting and confirm that the role is assigned only
after the third challenge. Also verify that a temporary role permission failure
can be corrected without repeating the challenges.

## Local manual test

See [the isolated test server](../apps/minecraft/halloween-test/README.md) for a
loopback-only Paper server using the production detector, saved progress and
Minecraft command output. Its worker never connects to Discord.
