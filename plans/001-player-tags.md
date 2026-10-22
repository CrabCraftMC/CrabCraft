# Plan 001: Add permanent collectible player tags

> Planning document only. No implementation or live-server changes are authorised by this document itself. When implementation is requested, follow the steps and verification gates below. Update `plans/README.md` when complete.
>
> Drift check: `git diff --stat eb1fd9a1..HEAD -- packages/db apps/bot apps/minecraft docs/halloween.md`. Compare changed integration points with the current-state evidence before implementing.

## Status and decisions

- Implementation complete on `feat/player-tags`; live rollout and client/resource-pack staging checks remain pending. See `docs/tags.md` for the deployment runbook.
- Priority: P1; effort: L; risk: MED; category: direction; dependencies: none.
- Planned at commit `eb1fd9a1`, 22 October 2026; revised the same day following the user's UI, command and placeholder clarification.
- User-confirmed: earned tags are permanent, including the pumpkin. The Halloween event ending must not remove it.
- User-confirmed interaction: `/settings` → Tags and `/tags` open the same named-tag picker, with icon previews; clicking an unlocked tag selects it, and None clears it. The placeholder is `%crabutilities_tag%`, and an absent tag must yield a zero-length string.
- Recommended defaults: only unlocked tags are selectable/listed; grant unlocks without auto-equipping; ownership belongs to each Minecraft UUID; ownership survives seasons. Main and alt accounts earn tags separately. These defaults are proposals, not additional confirmed user requirements.
- Scope: icon before the name in TAB only. Website settings, chat prefixes, overhead nametags, Discord management commands, trading, purchases, expiry and a general reward-rule engine are deferred.
- Subsequent authorised extension: administrators manage catalogue definitions
  and player grants/revocations at `/admin?tab=tags`, using the same database and
  award history. Website selection for players and Discord management remain
  deferred. Bundled defaults now insert missing tags only, preserving website
  edits across proxy restarts. See `docs/tags.md` for the implemented workflow.

## Intended behaviour

A player earns tags through events or a staff grant, opens `/settings` → Tags, `/settings tags` or `/tags`, and clicks one owned tag or None. All three entry points share one picker and service. Clicking selects immediately after database acknowledgement, without another Done/Save step; clicking a different tag replaces the previous selection. Earning another tag never changes the current choice, including an explicit None. A staff revoke of the equipped tag clears it atomically. Revocation removes an incorrectly awarded entitlement; tags do not expire automatically.

Example: a player earns the Halloween pumpkin, chooses it, and sees its icon before their nickname in TAB. They can later hide it without losing ownership. They retain it after Halloween and after a season change.

Acceptance targets: successful interactive changes appear in TAB within roughly one second under normal conditions; missed cross-process notifications converge within 30 seconds plus one TAB refresh. There is no PostgreSQL or Redis I/O in a placeholder callback.

## Current state and evidence

Repository paths below are relative to the repository root.

| File | Relevant fact |
| --- | --- |
| `apps/minecraft/spigot/src/main/java/crabcraft/net/crabUtilities/settings/SettingsCommand.java:47` | `/settings` is a Paper command, with a dialog and direct chat subcommands. |
| `apps/minecraft/spigot/src/main/java/crabcraft/net/crabUtilities/settings/SettingsDialog.java:68` | Six option selectors in a confirmation dialog; Cancel/Escape do not save. |
| `apps/minecraft/spigot/src/main/java/crabcraft/net/crabUtilities/settings/SettingsDialog.java:126` | Submits all ordinary preferences and immediately reports success. |
| `apps/minecraft/spigot/src/main/java/crabcraft/net/crabUtilities/settings/PlayerSettings.java:95` | JSON serialisation writes only known fields. |
| `apps/minecraft/velocity/src/main/java/crabcraft/net/crabUtilities/velocity/db/PlayerSettingsRepository.java:22` | Ordinary preferences are one TEXT JSON value per UUID. |
| `apps/minecraft/velocity/src/main/java/crabcraft/net/crabUtilities/velocity/PlayerSettingsService.java:150` | Existing preferences update the cache/broadcast before asynchronous persistence completes. |
| `apps/minecraft/velocity/src/main/java/crabcraft/net/crabUtilities/velocity/messaging/VelocityChatBridge.java:46` | Authenticates backend plugin messages against the player's current server connection. |
| `apps/minecraft/spigot/src/main/java/crabcraft/net/crabUtilities/chat/bridge/PaperChatBridge.java:121` | Validates reply UUID against carrier; schedules Bukkit operations on the main thread. |
| `apps/minecraft/shared/src/main/java/crabcraft/net/crabUtilities/chatbridge/ChatBridgeProtocol.java:16` | Shared, bounded binary protocol compiled into both JARs. |
| `apps/minecraft/spigot/src/main/java/crabcraft/net/crabUtilities/LoginStreakExpansion.java:40` | Already owns PlaceholderAPI identifier `crabutilities`; callbacks read memory. |
| `packages/db/src/queries/halloween.ts:19` | Existing event transaction validates dates, locks progress and deduplicates stream actions. |
| `apps/bot/src/utils/halloweenTransport.ts:35` | Acknowledges action consumption after the database transaction succeeds. |
| `apps/bot/src/utils/halloweenService.ts:24` | Discord role delivery depends on linking; Minecraft tag delivery must not. |
| `apps/minecraft/velocity/src/main/java/crabcraft/net/crabUtilities/velocity/CrabUtilitiesVelocity.java:202` | Initialises shared Java repositories; bounded DB executor starts with runtime consumers. |
| `apps/minecraft/velocity/build.gradle:116` | Regression executables are registered with `check`; `test` alone does not run them. |

Important existing excerpts:

```java
// PlayerSettingsRepository.java:22
CREATE TABLE IF NOT EXISTS player_settings (
    minecraft_uuid TEXT PRIMARY KEY,
    settings TEXT NOT NULL,
    updated_at INTEGER NOT NULL
)
```

```java
// VelocityChatBridge.java:48
event.setResult(PluginMessageEvent.ForwardResult.handled());
if (!(event.getSource() instanceof ServerConnection source)
        || !(event.getTarget() instanceof Player player)
        || player.getCurrentServer().filter(source::equals).isEmpty()) {
    // Reject.
}
```

```ts
// packages/db/src/queries/halloween.ts:27
const [previous] = await tx.select().from(halloweenPlayerProgress).where(key).for("update");
if (!isNewerStreamId(streamId, previous.last_stream_id)) return;
const next = advanceHalloweenProgress(previous, event.action);
```

Do not add ownership or selected-tag fields to the ordinary settings JSON. Its full-object updates, older serialisers and optimistic acknowledgement are unsuitable for collectible entitlements. Do not refactor that existing system as part of this feature.

## Ownership decision: database rather than permission nodes

Use PostgreSQL for the catalogue, unlocks and selected tag. Do not create per-tag ownership permissions or mirror unlocks into LuckPerms. Keep permissions for access to settings and staff maintenance only.

LuckPerms would be a valid ownership store for a system mainly distributed using `/lp` commands. It does not, however, eliminate catalogue/selection persistence. In this repository, Halloween completion already commits in PostgreSQL, so adding ownership to that transaction is more direct than introducing a second, retryable delivery to LuckPerms. The current proxy treats LuckPerms as an optional integration. Standard permission checks also include inherited grants, wildcards and contexts, which would require extra rules to represent a permanently earned collectible. See [LuckPerms usage](https://luckperms.net/wiki/Usage) and [advanced permission resolution](https://luckperms.net/wiki/Advanced-Setup).

| Option | Trade-off for this feature |
| --- | --- |
| Database unlocks and selection | One authoritative ownership check; atomic event awards; explicit per-account collections. Recommended. |
| LuckPerms unlocks, database selection | Convenient manual permission grants, but adds permission-resolution policy and a separate event-award delivery step. |
| Unlocks in both | Adds conflicting ownership and synchronisation work without a v1 requirement. Reject. |

Future player-facing website/Discord selection can reuse the same service
contract when separately requested. The subsequently requested administrator
website uses authenticated server actions, with an administrator check on every
mutation; it does not expose player selection or permission mirrors.

## Read-only live-server findings

Inspected via the user-requested Crabadactyl MCP on 22 October 2026. No console commands or mutations were performed.

- SMP: `CrabCraft SMP (Season 7)`, identifier `15d818b8`.
- `/plugins/Nexo/glyphs/ranks.yml` defines `pumpkin` as:

  ```yaml
  pumpkin:
    texture: crabcraft:ranks/pumpkin
    ascent: 8
    height: 9
    char: ꔌ
  ```

- SMP has PlaceholderAPI and TAB-Bridge. Proxy `0351a97b` has TAB and NexoProxy.
- Proxy `/plugins/tab/groups.yml` currently contains:

  ```yaml
  _DEFAULT_:
    tabprefix: "&r%javascript_voice%%essentials_afk%%luckperms_prefix%"
    tagprefix: ""
    customtabname: "&r%essentials_nickname%"
    tabsuffix: ""
    tagsuffix: "%javascript_nick%"
  ```

- Proxy TAB enables MiniMessage and a global public player list; name formatting and scoreboard teams are disabled on `Events` and `HalloweenChaos`.
- NexoProxy has glyph resolution enabled. Preserve these current backend exclusions unless separately requested.
- Only the SMP glyph availability was inspected. Do not assume other backends or clients have the pumpkin texture. Staging must verify pack availability across every supported viewer/backend combination.

Primary integration references: [TAB placeholders](https://github.com/NEZNAMY/TAB/wiki/Placeholders), [TAB-Bridge](https://github.com/NEZNAMY/TAB/wiki/TAB-Bridge), [NexoProxy glyph support](https://github.com/Nexo-MC/NexoProxy), [Nexo PlaceholderAPI behaviour](https://docs.nexomc.com/compatibility/placeholderapi).

## Architecture

```text
Staff commands on Velocity ──────┐
                               ├─ PostgreSQL: catalogue, ownership, choice, audit
Bot event completion transaction┘                  │
                                                   │ committed snapshot
                                  Velocity TagService
                                                   │ player-bound plugin channel
                                  Paper TagService/cache
                                                   │ memory-only PlaceholderAPI
                                  TAB-Bridge → proxy TAB → NexoProxy → client pack
```

Velocity owns interactive selection and staff mutations. The bot may award tags inside its own event transaction through a shared TypeScript query helper with the same locking/constraint contract. Redis carries a best-effort invalidation after bot commits; it does not authorise selection or hold the only copy of tag state. A periodic refresh of online players heals missed notifications. This avoids a new Redis mutation queue or durable outbox for the selector.

## Storage

Use four small tables. The first three hold current state; the fourth preserves accountability and automatic-award deduplication after revocation. Match existing TEXT UUID and integer Unix-second timestamp conventions. Use canonical, dashed, lower-case Minecraft UUIDs at input boundaries.

| Table | Fields and constraints |
| --- | --- |
| `cosmetic_tags` | `id TEXT PRIMARY KEY`, `name TEXT NOT NULL`, `description TEXT NOT NULL`, `glyph_id TEXT NOT NULL`, `sort_order INTEGER NOT NULL DEFAULT 0`, `enabled BOOLEAN NOT NULL DEFAULT TRUE`. Stable tag identity is separate from its reusable Nexo glyph. |
| `player_tags` | `minecraft_uuid TEXT NOT NULL`, `tag_id TEXT NOT NULL REFERENCES cosmetic_tags(id)`, `granted_at INTEGER NOT NULL`, `source TEXT NOT NULL`, `granted_by TEXT NOT NULL`; primary key `(minecraft_uuid, tag_id)`. One current ownership row per account/tag. |
| `player_tag_state` | `minecraft_uuid TEXT PRIMARY KEY`, `selected_tag_id TEXT NULL`, `revision BIGINT NOT NULL DEFAULT 0`, `updated_at INTEGER NOT NULL`; composite FK `(minecraft_uuid, selected_tag_id)` to `player_tags(minecraft_uuid, tag_id)`, default `NO ACTION`. Check revision is non-negative. |
| `player_tag_audit` | `id TEXT PRIMARY KEY` (generated UUID), `minecraft_uuid TEXT NOT NULL`, `tag_id TEXT NOT NULL REFERENCES cosmetic_tags(id)`, `action TEXT NOT NULL` constrained to `grant`/`revoke`, `ownership_changed BOOLEAN NOT NULL`, `actor TEXT NOT NULL`, `source TEXT NOT NULL`, `reason TEXT NOT NULL`, `created_at INTEGER NOT NULL`, `source_key TEXT NULL UNIQUE`. Add index `(minecraft_uuid, created_at)`. |

Index `player_tags(tag_id)` for staff queries/definition changes. The ownership primary key already covers per-player listing. Do not index every field.

Do not foreign-key Minecraft UUIDs to `players`: that table is keyed by Discord ID; alts and unlinked accounts must work. Do not include season or expiry in ownership. Do not cascade ownership deletion into `player_tag_state`, which would reset its revision; ordinary composite `ON DELETE SET NULL` would also attempt to clear its non-null UUID. Clear selected ownership explicitly before deleting it.

Use an explicit Drizzle composite `foreignKey` matching Java exactly. Use bigint for revisions in TypeScript and a decimal string on JSON boundaries; Java uses `long`. Avoid silently narrowing database revisions through JavaScript numbers.

Initial catalogue seed:

```json
{
  "id": "halloween-2026-pumpkin",
  "name": "Halloween 2026",
  "description": "Complete all three Halloween 2026 challenges.",
  "glyph_id": "pumpkin",
  "sort_order": 10,
  "enabled": true
}
```

Keep a reviewed catalogue seed at `packages/db/seeds/tags.json`, bundled into the Velocity JAR by `processResources`, following the existing awards seed pattern. Seed/upsert definitions only; never change ownership or selection during startup. Catalogue edits are deployed changes in v1, not an additional staff CRUD interface. Disabling a definition hides it and prevents selection while retaining ownership and the saved choice; re-enabling restores that choice unless the player selected None or another tag meanwhile. Reload/refresh must also refresh catalogue metadata, even where a player's revision is unchanged.

## Transaction contract

Both Java and TypeScript writers must use the same lock order and rules:

1. Ensure the UUID's `player_tag_state` row exists with None/revision 0; acquire it `FOR UPDATE`.
2. Validate the requested operation using catalogue and ownership within that transaction.
3. Apply the change, write any grant/revoke audit row, and increment the revision once if ownership or selection changed.
4. Commit before acknowledging success or notifying caches. Exceptions must propagate to the caller; do not copy repository methods which log and return default data on SQL failure.

Specific operations:

- **Manual grant:** validate definition exists; `INSERT ... ON CONFLICT DO NOTHING`. Duplicate current ownership is a no-op, with no duplicate audit, revision or unlock notification. Record grant source and actor on a change. Never change selected tag.
- **Automatic grant:** use a stable `source_key`, for example `halloween:halloween-2026:<uuid>:halloween-2026-pumpkin`. If that source key already exists in audit, do not grant again, even if staff subsequently revoked ownership. The first occurrence must consume the source key in an audit row even if the player already owns the tag from another source: record `ownership_changed=false` in that case, without a revision increment or unlock notification. A new effective award records `ownership_changed=true`. This distinguishes duplicate delivery of the same source from the first award of a new source to an existing owner. Staff can deliberately grant it again through the manual command with a fresh operation identity.
- **Select/clear:** require the expected revision from the UI; reject stale state with a fresh snapshot. A new non-null selection must exist, be enabled and belong to the player. Retaining the same currently owned ID is a no-op, including a disabled saved choice. `None` is database NULL, not a special tag record. Clear still works if a definition was disabled.
- **Revoke:** clear matching selected ID, delete current ownership, append reason/actor to audit, bump revision in one transaction. Repeated revoke is a no-op. No other tag is auto-equipped.
- **Reads:** build each snapshot with one SQL statement or a read-only `REPEATABLE READ` transaction so revision, selected ID and ownership describe the same database state. Separate SELECTs in a default `READ COMMITTED` transaction are insufficient. Missing player state means loaded None, not a database error. Distinguish loading, available-empty and unavailable states.

The composite FK and unique keys enforce one selected, owned tag independently of UI validation. The common UUID row lock serialises bot grants, staff corrections and selections across processes. A snapshot includes UUID, revision, selected ID and the owned catalogue metadata needed by the picker/placeholder; query disabled ownership too so it can be explained to the player. Catalogue-derived visible state must hide disabled IDs.

## Player UI and protocol

- `/settings` → Tags, `/settings tags` and `/tags`: fetch a fresh snapshot and open exactly the same dialog. Register `/tags` as a Paper player command using the same access permission as settings; keep the tag opening route independent of unrelated preference loading. Do not add a separate player-facing `/crabtags` root.
- Use a Paper multi-action dialog: None first, then one clickable button per enabled owned tag, sorted by order/name/ID. Each button has the actual icon and catalogue name, for example `[pumpkin icon] Halloween 2026`; description goes in its tooltip. Visibly mark the current choice. Only unlocked tags appear in v1; a full locked-tag collection browser is deferred. Paginate when the button count exceeds a small fixed page size (for example eight tags), rather than sending an unbounded dialog.
- Clicking a tag or None sends a SELECT immediately. On committed success, reopen the same page with the new current-choice marker; show a short success message. Do not require a second Done/Save step. Closing/Escape without clicking a choice does not change selection. Back returns to settings without reverting already committed clicks.
- If the saved tag is disabled, show its name as a temporarily unavailable current choice, without a selectable button. Opening/closing the page preserves that dormant choice; clearing it requires explicitly clicking None or another tag.
- Add a discoverable Tags navigation action to the existing `/settings` screen. Preserve Cancel/Escape. Give Tags a tooltip explaining that opening it discards unsaved changes on the current page; do not silently save those inputs or add another confirmation. Reuse a small dialog builder or change the confirmation footer to explicit Done/Cancel/Tags actions as required by the pinned Paper API. Avoid redesigning the other settings.
- Tags actions commit only the tag choice. Their success messages follow the proxy's committed result. If the player has no tags, show None plus an explanation that tags are earned through events. Do not add direct tag-ID command syntax until there is a demonstrated need; the picker serves both `/tags` and `/settings tags`.
- Treat dialog responses as untrusted input and bind the callback to the issuing UUID and current session. Recheck ownership on save, including a grant revoked while the dialog was open.
- Use actual resolved glyph components/font data for the Paper preview through a small optional Nexo adapter; plain display names remain usable if Nexo is absent. Do not assume standard `MiniMessage.miniMessage()` resolves Nexo tags. Follow the optional integration style of the existing model/Nexo lookup.

Add a versioned shared `TagBridgeProtocol` on `crabcraft:tags`, modelled on `ChatBridgeProtocol`, with bounded lengths, strict decoding and these messages:

```text
QUERY(requestId)
SELECT(requestId, selectedTagId|null, expectedRevision)
RESULT(requestId, status, snapshot)
STATE(snapshot)                            # unsolicited committed refresh
```

Statuses: `OK`, `NOT_OWNED`, `DISABLED`, `UNKNOWN_TAG`, `STALE_STATE`, `UNAVAILABLE`. Include actor UUID only in server responses; derive selection actor from the authenticated carrier/current backend connection. Mark the channel handled before rejecting messages; never forward client-origin packets into a privileged backend path.

Allow one in-flight mutation per player. Set a five-second response timeout; clean pending requests on quit/reload. Timeout text: “We couldn’t confirm your tag change. Reopen Tags to check.” A timed-out change may have committed; do not claim failure or automatically replay it. Duplicate request delivery cannot overwrite later state because selection uses an expected revision. Return current state on stale rejection.

Perform JDBC on the existing bounded proxy DB executor. Queue rejection returns Unavailable. Catch database errors inside the submitted operation and send an Unavailable result: the executor's outer wrapper only logs exceptions. Send replies only to the still-current connection/session. Gate QUERY-driven dialog opening by the current dialog generation; discard superseded opening callbacks. Gate SELECT acknowledgement by its request/session, not whether its clicked dialog remains open: an action may close the dialog while database work is pending. Reopen with the new marker only if the picker interaction is still current; an intervening command/dialog/session supersedes it. Return Bukkit message/dialog operations to the main thread. A current-session authoritative refresh may update the passive cache even when its old UI callback has been removed, without reopening the old screen or showing a second success.

## Cache and TAB rendering

Implement Paper tag state as a thread-safe immutable snapshot cache. Warm it on backend join and plugin startup for players already online; query again after transfer, reload and opening Tags. Evict on quit. Repeated refreshes for one UUID should coalesce rather than grow an unbounded queue.

Velocity pushes state after committed interactive changes. The bot publishes `crabcraft:tags:invalidate` with the UUID only after its transaction commits; Velocity reloads that UUID from PostgreSQL and pushes it if online. Unknown/offline UUID invalidations can be ignored. Refresh online players in bounded batches every 30 seconds and on Redis reconnection. DB failure retains the last confirmed snapshot and marks UI refresh unavailable; it must not save an empty/default value.

Discard older player revisions on every cache boundary. Catalogue metadata can change without a player revision change, so equal revisions must not prevent a fresh authoritative catalogue update. Serialise/coalesce per-player refreshes and guard callbacks with connection/lifecycle generation so delayed loads cannot restore an earlier choice or repopulate a disconnected player's cache. Redis is not required for the interactive request/result path, and no new Redis tag hash is required.

Extend the existing `crabutilities` PAPI expansion; do not register a second expansion with the same identifier. Add exactly `%crabutilities_tag%`: selected enabled tag → glyph representation only, without a separator; None/loading/unavailable-without-snapshot/disabled → `""`, a string of length zero with no whitespace, colour reset or invisible padding. Any colour/font wrapper must exist only in the non-empty branch and must not alter neighbouring text. Do not add extra tag placeholders in v1. Keep the existing expansion class name if renaming it would add unrelated churn.

Proposed exact TAB group change, to be applied only during an authorised deployment:

```yaml
_DEFAULT_:
  tabprefix: "&r%javascript_voice%%essentials_afk%%luckperms_prefix%%condition:player_tag_prefix%"
```

Merge the following entry into the existing TAB `conditions` section; do not replace that section or create a duplicate YAML key:

```yaml
conditions:
  player_tag_prefix:
    conditions:
    - '%crabutilities_tag%='
    true: ''
    false: '%crabutilities_tag% '
```

This places the collectible closest to the displayed name, after current status/rank prefixes. TAB adds a separator only when the icon is non-empty; the reusable placeholder itself is always icon-only or exactly empty. TAB documents this empty-value comparison in its [conditional placeholders guide](https://github.com/NEZNAMY/TAB/wiki/Feature-guide:-Conditional-placeholders). A selected output can use a closed white representation of `<glyph:pumpkin>` with no trailing space, verified with installed TAB/NexoProxy versions. Persist glyph IDs, not copied Unicode/font encodings. Staging must prove the whole placeholder-to-packet chain and Paper dialog preview.

Add `'%crabutilities_tag%': 1000` to TAB's placeholder refresh intervals. Keep `tagprefix`/`tagsuffix`, existing rank/voice/AFK/nickname formatting, sorting, streak objective and backend disable conditions unchanged. Do not repeatedly call `playerListName` or mutate Essentials nicknames to implement tags.

The recipient's resource pack controls rendering. Verify SMP ↔ Creative/Duels visibility and consistency before advertising network-wide support. A backend without CrabUtilities/PAPI/bridge must return a deliberate empty result or remain outside supported formatting; raw placeholder strings must never reach players. This PAPI callback knows the displayed player, not the viewer, so it cannot selectively omit a glyph for a viewer who declined the pack. A compatible pack available to all supported viewers is a release gate for this simple rendering route. If that cannot be guaranteed, resolve a verified viewer-aware TAB rendering mechanism before shipping and adjust the plan; checking the tag owner's pack status is insufficient. Do not silently change the server's pack acceptance policy in this feature.

## Staff administration and event awards

Player-facing commands are `/settings` and `/tags`. Keep manual distribution/debugging under the existing staff-only proxy command root rather than creating `/crabtags`. The small v1 maintenance surface is:

```text
/crabutilitiesproxy tags grant <uuid|online-name> <tag-id> <reason...>
/crabutilitiesproxy tags revoke <uuid|online-name> <tag-id> <reason...>
/crabutilitiesproxy tags inspect <uuid|online-name>
```

These commands are administration/testing tools, not part of the player picker. The subsequently requested website administration provides history browsing; the commands remain useful for maintenance. Permissions: `crabutilities.tags.grant`, `.revoke`, `.inspect`; no default access for ordinary players. Add the corresponding access checks to the existing root requirement as well as its new subcommands. Console is supported. Check permissions on Velocity; do not trust a backend claim that an actor is staff. Bound reason length and inspection output. Online names resolve to exact current UUIDs; offline operations accept UUIDs. Do not invent offline identities from nicknames or `Bukkit.getOfflinePlayer(name)`. A richer historical name resolver can be added later if wanted.

Grant/revoke responses distinguish Changed from Already owned/Already absent. Log actor/target/tag/reason and commit outcome. Inspect includes disabled ownership and current selection. Staff operations never impersonate a player's own selection.

Halloween integration:

1. Add `grantTagInTransaction(tx, ...)` to `packages/db/src/queries/tags.ts`; export its module in `packages/db/package.json`. Keep schema/bootstrap in the Java repository, with the same schema declared in Drizzle.
2. In `recordHalloweenAction`, after calculating `next`, grant only on the first durable incomplete-to-complete transition: previous `completed_at` is null and all three challenges are now complete. Insert ownership, audit and revision update in the same transaction as progress. Do this before the existing notification early-return. The automatic source key is an extra replay/backfill safeguard.
3. Publish the invalidation only after the outer transaction commits. Keep existing Redis action acknowledgement after commit. If invalidation fails, the 30-second online refresh repairs display; it does not undo the award or cause the event action to be processed as an unsaved completion.
4. No Discord link, guild membership, bot role permission or player online status is required to earn the tag. The existing Discord role retry path remains independent.
5. Provide a scoped one-off `backfill-halloween-tags.ts` script with `--event halloween-2026`, `--dry-run` and explicit `--apply`. Select stored completed rows, not current online players or Discord role holders. Preserve `completed_at` as the award date. Use the same transaction helper and source keys. Skip an automatic source key already present even after revocation, so re-running the backfill cannot undo a correction. Report eligible/new/already-awarded counts without credentials or private data dumps.
6. For future events, call the grant helper at their verified completion boundary with an event-specific source key. V1 does not add a generic event/rule interpreter or bulk reward UI.

Include the Halloween award hook and historical backfill in the initial implementation: the event is still active, and its tag delivery is currently missing. Paper detects challenges; the existing bot background worker commits progress, so changing that worker does not introduce Discord tag management. Update `docs/halloween.md`, `HalloweenManager.java:124` and `apps/bot/src/scripts/renderHalloweenCard.ts:121`: the pumpkin is permanent and players equip it through `/tags` or `/settings` → Tags. Regenerate and visually inspect the announcement image using the existing renderer. Do not repost messages or publish replacement artwork without a separate instruction.

## Files in scope

Existing files:

- `packages/db/src/schema.ts`, `packages/db/package.json`, `packages/db/src/queries/halloween.ts`.
- `apps/bot/src/utils/halloweenTransport.ts` and `apps/bot/tests/halloween.test.ts` for post-commit invalidation/event coverage.
- `apps/minecraft/velocity/src/main/java/crabcraft/net/crabUtilities/velocity/CrabUtilitiesVelocity.java`, `ConnectionListener.java` as needed for lifecycle, `ReloadCommand.java` for staff maintenance subcommands, and `apps/minecraft/velocity/build.gradle`.
- `apps/minecraft/spigot/src/main/java/crabcraft/net/crabUtilities/CrabUtilities.java`, `LoginStreakExpansion.java`, `settings/SettingsCommand.java`, `settings/SettingsDialog.java`, `halloween/HalloweenManager.java`, `apps/minecraft/spigot/build.gradle`, and `apps/minecraft/spigot/src/main/resources/plugin.yml` to register `/tags` and update command help. Do not edit a legacy Minecraft-root plugin descriptor.
- `apps/bot/src/scripts/renderHalloweenCard.ts`, `docs/halloween.md`, the plan index.

New files/directories:

- `packages/db/seeds/tags.json`, `packages/db/src/queries/tags.ts`, `packages/db/scripts/backfill-halloween-tags.ts`.
- `apps/minecraft/shared/src/main/java/crabcraft/net/crabUtilities/tags/TagBridgeProtocol.java`.
- `apps/minecraft/velocity/src/main/java/crabcraft/net/crabUtilities/velocity/db/TagRepository.java` plus a focused schema class if needed.
- `apps/minecraft/velocity/src/main/java/crabcraft/net/crabUtilities/velocity/tags/` for service/bridge/staff command.
- `apps/minecraft/spigot/src/main/java/crabcraft/net/crabUtilities/tags/` for service/cache/dialog/optional Nexo preview adapter.
- Corresponding Java regression/integration tests and bot tag tests. A short deployment/runbook document may be added under `docs/` when implementation is requested.

Out of scope: web source, Discord command UI, unrelated preferences internals, nickname/chat rendering, unrelated schema cleanup, event detector correctness/outbox redesign, server restarts/config writes during planning. Existing Halloween transport durability/order issues remain separately documented; this plan guarantees rewards once the completion transaction is durably recorded.

## Implementation order and verification

Use branch `feat/player-tags` and semantic commits such as `feat(minecraft): add collectible player tags`. Do not push, open a PR or deploy unless requested. If a PR is requested, make it ready for review.

### 1. Storage, seed and transaction semantics

Implement all four matching Drizzle/Java definitions, the initial seed, Java repository operations and transactional TypeScript helper. Match table names, types, nullability, defaults, checks, FK behaviour and index names. Seed before starting bot award consumers. Repository setup must report failures as feature-unavailable rather than continue pretending tags work.

Add a PostgreSQL integration executable modelled on `velocity/.../awards/EatingAwardDbIntegrationTest.java`, using a unique disposable schema and guaranteed cleanup. Test real constraints and concurrent transactions, not SQL-string snapshots. Include a Java↔TypeScript integration check against that schema to verify each runtime can read the other's grants and selections.

Verify: `bun run --cwd apps/bot build` and `cd apps/minecraft && ./gradlew test check` exit 0. Register a new explicit `:velocity:tagDbIntegrationTest` task; with `CRABCRAFT_TEST_JDBC_URL` set to a disposable PostgreSQL instance, it must pass and remove its schema. Add a separate Bun database integration test using a disposable test database/schema for TypeScript transactions, never the production URL. These new task names do not exist yet and must be wired before running them.

### 2. Interactive service, bridge and administration

Implement the shared protocol, proxy validation/permissions, committed responses, Paper cache and lifecycle. Add query/select timeout, stale revision and malformed/wrong-source tests. Wire the cache before UI/PAPI registration; stop subscriptions, tasks and pending callbacks on reload/shutdown. Add the minimal staff grant/revoke/inspect subcommands under the existing proxy root, with bounded queries.

Verify: `cd apps/minecraft && ./gradlew check` exits 0, including new explicitly registered protocol/service/command regression executables. A database failure and executor rejection must produce zero success acknowledgements. Direct client-origin packets must cause no database writes.

### 3. Settings selector and TAB placeholder

Add one shared click-to-select Tags dialog reachable from `/settings` → Tags, `/settings tags` and `/tags`; register the new command in the active Paper descriptor. Use multi-action buttons with icon/name labels and a current-choice marker. Paper documents this dialog type in its [Dialog API guide](https://docs.papermc.io/paper/dev/dialogs/); verify the precise builder signature against the pinned dependency. Extend the one existing PAPI expansion, retaining streak placeholders. Add cached glyph output and Nexo preview adapter, then document the exact live TAB config diff. Confirm lifecycle ordering does not leave the existing PAPI expansion pointing at a stopped/replaced tag cache after a gameplay reload.

Verify: Gradle `check` passes; regressions cover identical routes to the picker, named icon buttons, immediate committed selection, no ownership changes on selection, close-without-click, stale/forged callbacks, pagination, preserved ordinary preferences and existing streak outputs. Assert `%crabutilities_tag%` output equals `""` with length 0 for None and contains no surrounding whitespace for selected tags. Stage the configuration diff and verify `/papi parse me %crabutilities_tag%` plus `/btab dump <player>`; None and the TAB conditional separator both yield empty output, and selected pumpkin renders correctly for multiple viewers. Test with two tags to prove replacement, rather than only the single pumpkin.

### 4. Halloween award, backfill and permanent wording

Implement the completion transaction hook and post-commit invalidation; write the scoped backfill script and update source wording/artwork. Grant provenance must be the completed UUID even for linked alts. Ensure automatic replay/backfill cannot regrant a revoked award.

Verify: `bun test apps/bot/tests/halloween.test.ts` and new tag tests pass; bot build and Gradle `test check` pass. Run backfill dry-run/apply/re-run against a disposable dataset with complete, incomplete, unlinked, offline and revoked accounts; the second run grants zero new tags. Render via `bun apps/bot/src/scripts/renderHalloweenCard.ts` and inspect the generated `output/halloween-challenges-2026.png` for correct permanence text and layout.

### 5. Staging and rollout preparation

Prepare an additive rollout: Velocity storage/service first, Paper on supported backends, bot transactional awards, then TAB placeholder config after every supported backend has registered it. Run the historical backfill once after code is ready; completion source keys make retries safe. Applying live configuration and the backfill remain deployment actions, outside this planning turn.

Verify staging: all test cases below, then bot build and `./gradlew check`. Capture the precise catalogue/TAB deployment diff and tag grant counts. Rollback removes the placeholder from TAB and restores previous JARs/bot; leave new tables and earned ownership intact. Do not drop tag tables as a rollback step.

## Required test matrix

- Default loaded None differs from loading/unavailable; no hidden persistence of defaults on failed load.
- Duplicate grants create one ownership and one effective award audit; first grant never equips.
- Two owned tags: selection replaces rather than stacks; None persists across restart/transfer.
- `/settings` → Tags, `/settings tags` and `/tags` use one picker, with named icon buttons and no second Save step. Exiting without a choice does not write state. Paging/opening never changes selection.
- `%crabutilities_tag%` is icon-only when selected, otherwise exactly zero characters. The TAB spacing condition adds nothing when empty.
- Unknown, unowned and disabled IDs are rejected; forged dialog values and client packets cannot grant or select for another UUID.
- SQL FK rejects a selected unowned ID; concurrent select/revoke and grant/revoke leave a valid state and monotonic revision. Concurrent reads must return revision, choice and ownership from a single database snapshot.
- Transaction failure rolls back ownership, selection, audit and revision together; success acknowledgement follows commit.
- Lost response is reported as unconfirmed; reopening shows committed state; stale retry cannot replace a later choice.
- Offline/unlinked/alt grants work per UUID; renamed players retain tags; staff targeting cannot confuse nicknames with identity.
- Bot completion and tag grant are atomic; failure causes existing stream retry; duplicate actions do not duplicate awards.
- Revoke then replay/backfill does not restore an automatic award; explicit staff regrant works and is auditable. Also test manual grant → first event award with existing ownership → revoke → backfill: the recorded no-op event source must prevent restoration.
- Lost Redis invalidation heals during periodic refresh; Redis outage does not block direct proxy selection; no per-render I/O.
- Late query cannot overwrite newer selection, repopulate quit state or show a dialog on a transferred session.
- Catalogue disable/re-enable and metadata reload refresh even at the same player revision. Disable → open/close picker without choosing → re-enable preserves the saved choice.
- Prefix colours/fonts/separators, rank/AFK/voice/nickname, streak objective, global viewer behaviour and existing server exclusions remain correct.
- Missing Nexo/PAPI/TAB-Bridge, unknown glyph and declined/unavailable resource pack have defined, tested behaviour with no raw markup leakage.

## Done criteria

- Both schema definitions and integration tests agree; no destructive schema operation is required. Do not run or recommend `db:push` for this additive rollout. If migration tooling is chosen instead, inspect all Java-managed tables against Drizzle before generating/applying any diff, including settings and both streak tables.
- `bun run --cwd apps/bot build` exits 0.
- `bun test apps/bot/tests/halloween.test.ts` and added tag tests exit 0.
- `cd apps/minecraft && ./gradlew test check` exits 0; newly added regression executables are wired to `check`.
- Disposable PostgreSQL Java/TypeScript integration tests pass, including concurrency and rollback cases.
- Staging confirms one or no icon, accurate settings acknowledgement, offline awards, event permanence, correct recipient packs and eventual recovery.
- Only in-scope files changed; no live files, credentials or generated build artefacts committed. Existing ordinary settings behaviour remains covered.
- Deployment notes list seed/backfill counts, TAB diff, supported backends and rollback; plan index is updated after implementation.

## Stop conditions and limits

Resolve before dependent implementation if the pinned Paper API cannot supply the proposed navigation without silently saving inputs; if installed NexoProxy/TAB does not render glyph tokens from the custom placeholder; if a supported backend has conflicting glyph/pack mappings; or if database integration reveals incompatible schema definitions. Do not substitute nickname mutation, raw user-controlled MiniMessage, Discord-keyed ownership or an unverified destructive schema push.

Do not build a general reward platform, store unlocks in LuckPerms permissions/prefixes, add a new player-facing `/crabtags` command, or add expiry to satisfy stale Halloween wording. Confirmed permanence takes precedence. Any new request for shared alt ownership, website/Discord management or overhead nametags changes the contract and should be planned explicitly.

The initial planning research covered the tag integration paths, shared schemas,
Halloween award boundary and the inspected live Nexo/TAB configurations. It did
not audit all event detectors, every backend pack, the website or the whole
repository.

## Implementation verification

After the user authorised implementation, the agreed in-game feature and
Halloween reward/backfill were implemented. Website and Discord management were
initially left outside the change. On 22 October 2026 these checks passed:

- `bun run --cwd apps/bot build`.
- Existing Halloween tests plus `packages/db/tests/tags.integration.test.ts`:
  14 tests, 88 assertions, including the actual backfill CLI's preview/apply/retry.
- `cd apps/minecraft && ./gradlew test check :velocity:tagDbIntegrationTest`:
  all checks passed, including Paper request/cache/lifecycle and proxy
  protocol/authentication/service/command regressions.
- Real PostgreSQL 18.4 checks using disposable schemas, including constraints,
  concurrent mutation, rollback, precise revisions, and Java ↔ TypeScript writes.
- Announcement artwork regenerated and visually inspected; the local generated
  image was kept outside the repository.

No production server files, configuration or database rows were changed. The
runbook records live TAB configuration, compatible viewer-pack verification and
the production backfill as pending rollout actions.

### Website administration extension

The user subsequently requested website tag administration. The existing admin
panel now provides catalogue creation/editing, enable/disable, ownership counts,
main/alt and offline UUID lookup, selected-tag inspection, grants/revocations
with reasons, and recent award history. Catalogue edits preserve permanent IDs
and survive proxy restarts. Administrator checks protect both reads and each
server action. Catalogue writes are serialised and rejected atomically when
they would exceed the current game protocol's count or UTF-8 payload limits.

Final verification on 22 October 2026 passed:

- Website test suite: 69 tests, 757 assertions; production build and TypeScript.
- Combined Halloween and tag database suites: 27 tests, 195 assertions, including
  real PostgreSQL transactions, capacity boundaries and concurrent writes.
- Bot build and Minecraft `test check :velocity:tagDbIntegrationTest collectJars`:
  121 tasks, including Java ↔ TypeScript data and seed-preservation checks.
- Local Chromium with synthetic administrator/moderator sessions and a
  disposable database: create/edit/duplicate validation, grants without
  auto-equipping, disabling without loss, equipped-tag revocation, audit actor,
  mobile layout, moderator restrictions and anonymous redirects.

The local website and disposable PostgreSQL server were stopped after testing.
Player-facing website selection and Discord management remain deferred. Live
deployment and production backfill remain pending.
