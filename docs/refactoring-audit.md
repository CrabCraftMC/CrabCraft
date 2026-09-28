# Local refactoring audit — first pass, 24 September 2026

This report records the first pass. See the [complete Minecraft source review](minecraft-refactoring-review.md) for the subsequent pass and current Minecraft counts.

Baseline: `ff2dc66` from `origin/main`. Work remains local on
`refactor/reduce-code-and-tests`; no commits, pushes or pull requests were made.
Seven workstreams audited tests, CI and implementations, followed by an
independent production-diff review.

## Size and scope

The baseline contains 137,328 tracked text lines, including JSON catalogues,
documentation and tooling. Tests account for 13,947 lines (10.2%); production
source files account for 97,744. Tests alone do not explain most of the size.
Counts include blank lines and comments, exclude binary/generated/ignored files,
and include newly shared files in the resulting totals.

| Area | Before | After | Reduction |
| --- | ---: | ---: | ---: |
| Bot tests | 3,252 | 2,831 | 421 |
| Web tests | 840 | 663 | 177 |
| Minecraft tests | 9,855 | 8,730 | 1,125 |
| All tests | 13,947 | 12,224 | 1,723 (12.4%) |
| Production source | 97,744 | 96,846 | 898 |

Including build configuration, the patch removes 2,635 text lines before this
report. Three obsolete image fixtures totalling 279,821 bytes were also removed.

## Test decisions and CI

Removed exact wording/colour snapshots, source-substring assertions, catalogue
constant copies, third-party self-tests, trivial wrappers and duplicate updater
coverage. Repeated award-reader and Bingo reset checks were consolidated.
Player cards retain real image render/decode smoke checks in place of exact-pixel
goldens; future visual changes need visual review.

Retained protections cover authentication, permissions, privacy, unsafe media,
protocol encoding, migration/persistence, reward replay, deletion fencing,
concurrency, failure recovery, gameplay boundaries and performance limits.
The larger ticket/gallery suites remain because they protect state transitions
and data-loss scenarios rather than merely proving a feature exists.

Bot and web tests already gate their CI builds. Minecraft's custom executable
regressions previously ran through `check`/`build` but not `test`. They now run
through `test`; CI explicitly invokes `./gradlew test build`. The previously
unregistered verification-reminder cooldown suite is now included. Every retained
executable Java suite has a Gradle registration; packaging checks remain on `check`.

## Implementation changes

- Minecraft: five updater classes now share the existing common source tree;
  16 Bingo listeners share reset/token handling; coordinate HUD updates use one
  timer and one location snapshot per enabled player. Duplicate accurate-placement
  listener registration was removed. Velocity response, JSON traversal and player
  update code was consolidated; invitation/settings handling avoids needless copies.
- Bot: bounded player-name parsing avoids repeated suffix copies; image rendering
  uses buffer fills and fewer conversions. Transcript dates reuse one formatter.
- Web: gradient alternatives share colour-distance calculations and preset
  filtering. Enchantment restoration filters against the saved item once, fixing
  non-sword loadouts being pruned against the initial sword selection.
- Database: shared application/season mappings and award SQL; consolidated Gallery
  soft-deletion with existing lock order and revision guards; atomic conditional
  Bingo seeding replaces two queries. No schema changes were made.

Local synthetic benchmarks measured gradient matching at about 4.3 times faster,
name parsing at 6.7 times faster, transcript rendering at 12.5 times faster and
player-list PNG rendering at 1.07 times faster. These are isolated local timings,
not production latency guarantees. HUD joins now follow the shared two-tick cadence.

## Verification and limits

- 112 bot tests and 39 web tests passed, including a combined run under CI's
  pinned Bun 1.3.13 (151 passed, zero failures).
- Bot TypeScript build and web production build passed.
- `./gradlew test build` passed: 79 executable regression suites and JAR checks.
- Baseline comparisons matched 6,500 gradient rankings, 1,000 parser inputs,
  28 byte-identical PNGs and a 2,000-message transcript (excluding its timestamp).
- Four award-query SQL statements and parameters matched the baseline apart from
  whitespace; synthetic application/season/result mappings matched too.
- Browser checks confirmed a pickaxe loadout survives reload and gradient
  alternatives still change the displayed palette.
- Independent review found no actionable production regressions; diff whitespace
  checks passed. No live PostgreSQL, Discord or Minecraft server was exercised,
  and remote CI was not triggered. Existing Gradle deprecation warnings remain.

Large hunt catalogues, award data, gameplay-specific detectors, and ticket/gallery
recovery paths were deliberately retained. Further reductions should target
measured duplication or unnecessary runtime work; shortening those areas wholesale
would trade away content, clarity or failure handling.
