# Implementation plans

The user authorised implementation of Plan 001. Live deployment remains separate.

| Plan | Title | Priority | Effort | Depends on | Status |
| --- | --- | --- | --- | --- | --- |
| [001](001-player-tags.md) | Add permanent collectible player tags | P1 | L | — | IMPLEMENTED; live rollout pending |

Plan 001 was prepared on 22 October 2026 against `eb1fd9a1`, then revised after the user's UI clarification. It covers database-owned unlocks/selection, a shared named-icon picker at `/settings` → Tags and `/tags`, the exactly-empty-or-icon-only `%crabutilities_tag%` placeholder, minimal staff maintenance under the existing proxy root, TAB/Nexo rendering, Halloween awards/backfill, testing and rollout. The user confirmed that all earned tags, including the pumpkin, are permanent. The user subsequently requested website administration: catalogue editing and player grants/revocations are now included at `/admin?tab=tags`. Player-facing website selection and Discord management remain deferred.

Implementation order within the plan: storage/transaction tests → proxy and backend bridge → selector/placeholder → Halloween/backfill → staging and rollout preparation.

Implemented on `feat/player-tags`. Verification passed on 22 October 2026:
bot build; 14 Bun tests with 88 assertions (including nine PostgreSQL/CLI
integration tests); Minecraft `test check`; and the explicit Java ↔ TypeScript
PostgreSQL integration executable. The temporary PostgreSQL instance was used
only for disposable schemas. See [the rollout guide](../docs/tags.md) for TAB,
resource-pack checks, deployment order and production backfill commands. No
production configuration or data was changed.

The subsequent website administration extension also passed the production web
build, 69 web tests (757 assertions), 27 combined Halloween/database tests
(195 assertions), the bot build and all 121 Minecraft verification/build tasks.
Local Chromium checks exercised catalogue and ownership forms, mobile layout,
administrator/moderator access and anonymous redirects using synthetic sessions
and disposable PostgreSQL data. Website edits survive proxy restarts; oversized
catalogue changes are rejected within the existing game protocol limits.

Alternatives considered: ownership in ordinary settings JSON would bypass relational checks and conflict with full-object settings saves; LuckPerms ownership would add a separate event delivery and permission-resolution policy while catalogue/selection persistence is still needed; mirrored DB/permission ownership creates two sources of truth; direct nickname/tab-list mutation would compete with the existing proxy TAB configuration; a generic reward engine and expiry support exceed this feature's scope.
