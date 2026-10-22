# EssentialsX command replacements

Install the updated CrabUtilities JARs on Velocity and each Paper backend. Nicknames use PostgreSQL and the Redis mirror. Primary accounts retain their existing nickname fields; the Java schema initialiser adds matching fields for alt accounts. The commands below belong to CrabUtilities even while EssentialsX remains installed.

## Nicknames

- `/nick <nickname>` sets your nickname; `/nickname` is an alias.
- `/nick off` or `/nick reset` restores your account name.
- `/nick <account-name> <nickname|off>` changes another registered player's nickname, including while they are offline.

`crabutilities.nick` is allowed by default unless explicitly denied on Velocity. `crabutilities.nick.others` must be granted to moderators on Velocity. Nicknames support legacy colour codes and MiniMessage colours, gradients and decorations, with 1–32 visible characters and no spaces. Nicknames apply to public join/leave announcements, chat, private messages, tab names, death and advancement messages, mentions, sleep announcements and other existing nickname-aware displays. Account names remain the identifiers used by moderation commands.

PostgreSQL is authoritative. A failed database write leaves the previous nickname intact; failed Redis publication is retried after the database save succeeds.

## Game modes

| Mode | Commands | Required permission |
| --- | --- | --- |
| Survival | `/gms`, `/survival`, `/gamemode survival` | `crabutilities.gamemode.survival` |
| Creative | `/gmc`, `/creative`, `/gamemode creative` | `crabutilities.gamemode.creative` |
| Spectator | `/gmsp`, `/spectator`, `/gamemode spectator` | `crabutilities.gamemode.spectator` |
| Adventure | `/gma`, `/adventure`, `/gamemode adventure` | `crabutilities.gamemode.adventure` |

`/gm` aliases `/gamemode`. Modes also accept `s`, `c`, `sp`, `a`, and vanilla numeric IDs. An optional final account name changes an online player's mode and additionally requires `crabutilities.gamemode.others`. Each mode permission defaults to operators. For moderators who need spectator and a return to survival, grant only `.spectator` and `.survival`; creative and adventure remain separately controlled. `/specback` continues to track spectator entry locations.

## Inventory and ender chests

- `/invsee <account-name>` opens the player's main inventory with armour and offhand. The bottom row holds boots, leggings, chestplate, helmet and offhand, in that order.
- `/echest [account-name]` opens your own or another player's ender chest. `/enderchest` and `/ec` are aliases.
- Use normal clicks, number keys or dragging to edit. Shift-click and double-click collection are disabled inside inspection views to protect the unused slots.

| Permission | Access | Default |
| --- | --- | --- |
| `crabutilities.invsee` | View another player's inventory | Operator |
| `crabutilities.invsee.modify` | Edit an inspected inventory | Operator |
| `crabutilities.echest` | Open your own ender chest | Everyone |
| `crabutilities.echest.others` | View another player's ender chest | Operator |
| `crabutilities.echest.modify` | Edit another player's ender chest | Operator |

Inspection works with online players and offline player data saved on the current backend. Completion includes all registered primary and alt accounts, refreshed through Redis every minute, plus locally online players. A player who has never joined this backend has no local inventory to open.

Online inspection edits the live inventory directly. Offline edits use Minecraft's normal data conversion and preserve the other player data. Only one inspection per target is allowed at a time. Changes are saved after edits and on close; the final login validation flushes and closes an offline editor before the player data is loaded. Writes use atomic replacement, reject externally changed files, preserve vanilla `.dat_old` and retain the previous file as `<uuid>.dat.crab-inventory-backup`.

If the player file changes externally, pending edits are written to a separate file in `plugins/CrabUtilities/inventory-recovery/`. The current player file stays intact, the inspection closes and its lock is released so the player can join and moderators can reopen the current data. The server log records the recovery file path; an administrator can compare that snapshot with the current player data to reconcile the pending inventory edits. Temporary save failures retain the editor state and retry. If the recovery file itself cannot be written, the retained edits stay locked until recovery saving succeeds; check server logs if a write failure persists.

## AFK

`/afk` toggles cosmetic AFK status and requires `crabutilities.afk`, allowed by default. Players become AFK after five minutes without activity. Movement, looking around, chat, commands and interactions clear AFK. Announcements use the nickname and respect player visibility.

With PlaceholderAPI installed, `%crabutilities_afk%` returns `yes` or `no`. This uses the existing `crabutilities` expansion alongside the login-streak placeholders. AFK does not change damage, invulnerability, collisions or other gameplay.

## Completing the EssentialsX migration

If EssentialsX remains installed for other features, disable its automatic AFK timer (`auto-afk: -1`) and AFK protection (`freeze-afk-players: false`, `disable-item-pickup-while-afk: false`) in its configuration. Disable its display-name and tab-name rewriting (`change-displayname: false`, `change-playerlist: false`) so its stored nicknames cannot overwrite CrabUtilities names. These settings are documented in the [EssentialsX default configuration](https://github.com/EssentialsX/Essentials/blob/2.x/Essentials/src/main/resources/config.yml). Remove legacy Essentials game-mode permission grants from moderator roles; namespaced Essentials and vanilla commands still follow their own permissions. Remove or replace third-party plugin formats that explicitly use Essentials nickname or AFK placeholders.
