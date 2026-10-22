# Collectible player tags

Tags are permanent cosmetics belonging to individual Minecraft UUIDs. Players
open the same picker through `/tags`, `/settings tags`, or `/settings` → **Tags**.
Each unlocked tag has its Nexo icon and display name. Clicking a tag saves that
choice; **No tag** hides it without losing unlocks. There is no second Save step.
New awards do not change the player's selection. Main and alt accounts have
separate collections, and Discord linking is unnecessary.

## Storage and awards

PostgreSQL is authoritative for the catalogue (`cosmetic_tags`), unlocks
(`player_tags`), selection/revision (`player_tag_state`) and award/correction
history (`player_tag_audit`). The selection has a composite foreign key to
ownership. The proxy serialises database changes per account and acknowledges
selection only after commit. Both Java and Drizzle declare the same schema.

Administrators manage the catalogue and player unlocks on the website at
`/admin?tab=tags`. This admin-only page creates and edits definitions, inspects
players' unlocks and award history, and grants or revokes tags with a recorded
reason. Players continue to choose their active tag in-game.

### Website administration

Sign in with an administrator account, open **Admin Panel** and choose **Tags**.
Moderators cannot view this tab or perform its actions.

- **Create tag** records a permanent internal ID, display name, optional
  description, existing Nexo glyph ID, display order and enabled state.
- Expand an existing tag to edit its metadata. The internal ID stays fixed.
  Owner counts include disabled tags; selected counts include saved dormant
  choices.
- Search Minecraft names across main and alt accounts, or enter a full UUID for
  an offline or unlinked account. Choose the account to inspect its collection,
  selected tag and latest 50 award-history entries.
- Grant an enabled, unowned tag or expand an owned tag to revoke it. A reason is
  required; the audit records the authenticated administrator's Discord ID.
  Granting does not equip the tag. Revoking an equipped tag clears the selection
  in the same transaction.

The page manages definitions, not resource-pack assets. Install and verify the
Nexo glyph before creating its definition. The catalogue is bounded by the
current in-game protocol: at most 128 definitions, including disabled tags,
whose full collection fits a 30,000-byte result. An oversized create or edit is
rejected without changing the catalogue; shorten names or descriptions if the
text budget is exhausted.

`packages/db/seeds/tags.json` supplies initial defaults, bundled into the
Velocity JAR and inserted only when the definition is missing. The database is
the catalogue source of truth: website edits survive proxy restarts and reloads.
Add the Nexo glyph to the supported resource packs before creating a catalogue
entry. Internal tag IDs are stable; display names and glyph IDs can change
independently of ownership.
Disabling a definition hides its icon and prevents new selection while keeping
ownership and the saved choice. Choosing another option replaces that saved
choice. Catalogue edits reach online players through the existing refresh cycle
within 30 seconds; a proxy reload is unnecessary. Definitions are disabled rather
than deleted, preserving ownership and award history.

Tag access is stored in the database rather than per-tag permission nodes.
Command access uses the existing `crabutilities.settings` permission. Staff
maintenance uses these separate proxy permissions:

| Command | Permission |
| --- | --- |
| `/crabutilitiesproxy tags grant <uuid-or-online-name> <tag-id> <reason>` | `crabutilities.tags.grant` |
| `/crabutilitiesproxy tags revoke <uuid-or-online-name> <tag-id> <reason>` | `crabutilities.tags.revoke` |
| `/crabutilitiesproxy tags inspect <uuid-or-online-name>` | `crabutilities.tags.inspect` |

Console is supported. Offline targets require a full Minecraft UUID; names
resolve exact online Minecraft accounts, rather than nicknames. A revoke clears
the selected tag if necessary, without equipping another tag. Repeated manual
grants/revokes are harmless. Reasons are required and recorded with the actor.

For automatic awards, call `grantTagInTransaction` inside the event's completion
transaction with an event-specific source key. A source is recorded even if
another source already unlocked the tag. Replaying an event or backfill cannot
restore an automatic award which staff revoked; an intentional manual regrant
remains possible.

## Halloween 2026

Completing all three challenges unlocks **Halloween 2026**, internal ID
`halloween-2026-pumpkin`, using the existing Nexo glyph `pumpkin`. Ownership and
completion commit together. The tag remains after Halloween and season changes.
Discord role delivery is independent.

After deploying tag storage, preview and apply awards for existing completions
from the repository root, using the normal deployment `DATABASE_URL`:

```sh
bun packages/db/scripts/backfill-halloween-tags.ts --event halloween-2026 --dry-run
bun packages/db/scripts/backfill-halloween-tags.ts --event halloween-2026 --apply
```

The script prints counts, preserves original completion dates and selections,
and can be retried safely. It only supports the reviewed `halloween-2026` event.
No live backfill was run during implementation.

## Placeholder and TAB

`%crabutilities_tag%` returns the selected icon representation, without surrounding
spaces. No selection, a disabled definition or a missing local glyph returns
exactly `""`: no spaces, reset codes or invisible padding. It reads an immutable
in-memory snapshot, with no database or Redis I/O in the callback. The existing
`crabutilities` PlaceholderAPI expansion continues to supply streak placeholders.

The supported rendering path is Paper Nexo/PlaceholderAPI → TAB-Bridge → proxy
TAB → NexoProxy → the viewer's resource pack. Verify the same glyph/texture is
available to every supported viewer, including cross-server tab entries. A
player placeholder cannot check the viewing client's pack status. Preserve the
existing pack acceptance policy and TAB server exclusions.

On the proxy, append `%condition:player_tag_prefix%` to the existing
`_DEFAULT_.tabprefix` in `plugins/tab/groups.yml`. Preserve its current voice,
AFK and rank formatting, including any formatters updated during the
EssentialsX migration.

Merge the following into the existing `conditions` and
`placeholder-refresh-intervals` sections of `plugins/tab/config.yml`; do not
replace other entries or create duplicate YAML section keys:

```yaml
conditions:
  player_tag_prefix:
    conditions:
    - '%crabutilities_tag%='
    true: ''
    false: '%crabutilities_tag% '
placeholder-refresh-intervals:
  '%crabutilities_tag%': 1000
```

The separator exists only when an icon exists. Rank, AFK, voice, nickname,
sorting, overhead nametags and streak objective settings remain unchanged.
Verify `/papi parse me %crabutilities_tag%` and `/btab dump <player>` on staging.
Check two different owned tags, No tag, colour/font isolation, a disabled tag,
and viewers on different supported backends.

## Rollout and recovery

1. Deploy the Velocity JAR first. Confirm tag schema/seed initialisation succeeds.
   This is additive startup DDL; no `db:push` is needed.
2. Deploy Paper to supported backends with Nexo, PlaceholderAPI and TAB-Bridge.
   Verify `/tags` and the settings navigation before changing TAB formatting.
3. Deploy the website and verify **Admin Panel** → **Tags** with an administrator
   account. It requires the tag tables created by Velocity; this interface adds
   no further schema changes.
4. Deploy the bot's Halloween completion worker, then preview/apply the backfill.
5. Verify viewer pack compatibility and merge the TAB configuration above.

Interactive selection uses an authenticated player-bound plugin channel. A
five-second timeout is reported as unconfirmed: reopen `/tags` to check instead
of assuming that the transaction failed. Backend transfer/reload invalidates old
menu requests. Redis carries post-commit award invalidations; bounded online
refreshes every 30 seconds repair missed notifications. Existing confirmed state
is retained during a database outage, and failed loads never overwrite it with
defaults.

To roll back deployment, restore the previous JARs/bot and remove the new TAB
condition from the prefix. Preserve the tag tables and ownership. Player-facing
website and Discord tag management remain outside this implementation.

## Verification

```sh
bun run --cwd apps/bot build
bun test apps/bot/tests/halloween.test.ts
bun run --cwd apps/web test
GALLERY_MEDIA_BASE_URL='<gallery-https-origin>' bun run --cwd apps/web build
# Requires a disposable PostgreSQL instance, with a dedicated test URL:
CRABCRAFT_TEST_DATABASE_URL='<test-postgres-url>' bun run --cwd packages/db test:tags-db
cd apps/minecraft
./gradlew test check
CRABCRAFT_TEST_JDBC_URL='<test-jdbc-url>' ./gradlew :velocity:tagDbIntegrationTest
```

Database tests create and remove unique schemas. The Java integration test also
runs a Bun fixture to verify Java and TypeScript can read each other's grants,
selections, revocations and automatic-award receipts. Never point these test
variables at production.
