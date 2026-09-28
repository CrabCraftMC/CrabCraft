# Minecraft feature review — second pass, 24 September 2026

This pass statically reviewed every production Java file in the active module
source roots when it began: 273 files and 47,553 lines across Paper, Velocity,
shared code and the two manual test-server plugins. It builds on the [first audit](refactoring-audit.md).
The subsequent [Kotlin migration](minecraft-kotlin-migration.md) preserves this
refactor; the Java counts below describe the earlier stage.
Eight feature workstreams and a core review covered the inventory below; separate
reviewers checked the resulting algorithms, network flows, integrations and media.

This covers the active source inventory, not every directory or live execution path.
The later unused-file review below found an obsolete root source tree omitted from
that inventory.
External plugin internals, real server timing and database query plans remain outside
that claim. No feature was deliberately removed, and all work remains local.

## Coverage

Counts are physical Java source lines at the start of this pass, including comments
and blank lines. Every file belongs to exactly one row.

| Review area | Files | Lines |
| --- | ---: | ---: |
| Core, chat, configuration and shared protocols | 36 | 5,056 |
| Statistics, database, awards and advancements | 25 | 3,859 |
| Portals, placement, BlueMap, view distance and restricted areas | 18 | 3,864 |
| Jade, AppleSkin and Xaero integrations | 59 | 4,406 |
| Bingo, Halloween and their manual test-server plugins | 29 | 10,678 |
| Player settings and all small gameplay tweaks | 24 | 2,786 |
| Media playback and Nexo model tools | 29 | 4,739 |
| Voice relay, groups, calls, music and animations | 20 | 5,645 |
| Proxy lifecycle, API, messaging, staff chat and LiteBans | 33 | 6,520 |
| **Total** | **273** | **47,553** |

The gameplay row includes heads, slime maps, sleep, coordinates, happy ghasts,
villagers, recipes, spectator return, cauldrons, shulkers, End portals, XP clumping
and enderman grief prevention. Core review includes optional-dependency loading,
module configuration, nickname rendering, vanish publication, chat bridges,
login-streak caching, command registration and updater scheduling.

## Implemented reductions

At the end of this pass, active production Java totalled **46,611 lines: 942 fewer in this pass (2.0%)**. Including
the earlier pass, Minecraft production Java is 1,705 lines smaller. New shared
files are included in these totals; moving code between files does not count as
removing it. No tests were removed in this pass. Two existing suites gained 136
lines for portal geometry and sample-buffer boundary checks.

- **Media/models:** disc and horn commands/dialogs share their real common workflow;
  cached and streamed horn playback share setup; PCM reads and cache hashes use
  equivalent JDK operations. Model tools reuse storage searches and metadata copying.
- **Jade/AppleSkin:** direct construction replaces single-use accessor builders while
  preserving lazy lookups; shears lookup no longer needs a singleton/list/handler
  layer; a one-byte payload no longer allocates a ByteBuffer.
- **Voice:** one roster entry per player replaces nested group maps and repeated
  whole-group searches. Invitation consumption, ringtone termination and manual
  group events share equivalent operations. Exactly four identical Redis retry
  loops share a helper; their separate threads and shutdown responsibilities remain.
- **Proxy:** configuration directly initialises fields rather than passing 46
  positional values through a constructor. HTTP UUID/resource dispatch and LiteBans
  punishment processing share their common branches, retaining their distinct modes.
- **Bingo:** shared block keys, bounded-map eviction, tick checks and ownership-index
  operations. Conduit detection reads 42 candidate positions instead of 125. Portal
  shape detection avoids redundant occupancy checks; ore traversal avoids repeated
  enum-array allocation. All 96 tasks remain.
- **World/performance:** a bounded queue replaces self-mutating concurrent portal
  traversal; tick-time samples use a primitive ring buffer instead of boxed values.
- **Statistics:** snapshots parse JSON once, qualified streaks skip an unnecessary
  query, and award eligibility SQL is shared. Pattern caching now initialises safely
  across the two stats workers.
- **Nicknames:** Paper and Velocity share safe styling/legacy parsing, while their
  wrappers retain different null, blank and plain-text behaviour.

Explicit corrections: unfinished/over-budget portal searches are now rejected,
including a previously accepted closed 4×4 interior with a 3×3 area budget; award
pattern-cache initialisation is concurrent-safe; invite expiry is checked inside
its lifecycle lock. No protocol, schema, catalogue or permission checks were removed.

## Verification

The final `./gradlew test build` compiles all plugins, executes all 79 retained
executable regression suites, and checks packaged JAR contents. Focused portal and
sample-window cases were added because traversal bounds and buffer reuse are
non-trivial behaviour. Existing nickname, privacy, media, voice, award, persistence
and lifecycle regressions remain.

Temporary, wholly synthetic baseline comparisons also checked:

- 1,000 connected/obstructed portal shapes across both axes, plus the explicit
  over-budget correction above.
- 40,000 sample-window medians covering capacity, wraparound, invalid samples and reuse.
- 276 configuration getter results across six normal/fallback/boundary cases.
- 98,665 HTTP paths for routing, UUID validation and error behaviour.
- 22 captured prepared SQL statements, identical apart from whitespace.
- 1,600 concurrent evaluations across 128 synthetic awards and login-progress branches.
- Redis reconnect callbacks, resource closure, null/closed pools, interruption and
  classloader shutdown without contacting Redis.

Independent static reviews found no actionable introduced regressions. No live
Minecraft, Redis, PostgreSQL, LiteBans or voice-client integration was exercised.
The operation-count improvements above are explicit; no production latency claim
is made. Existing Gradle/Paper deprecation warnings remain.

## What still earns its size

Bingo's task-specific event and persistence rules, voice routing/session recovery,
Jade protocol/provider compatibility, media security/cancellation and reversible
item metadata account for much of the remaining code. Most small gameplay features
are already single handlers of roughly 40–110 lines. The HTTP server also contains
roughly 550 lines of API documentation/schema content; relocating that content
would improve navigation but would not eliminate it.

No source-level evidence supports removing tens of thousands of lines while keeping
all current behaviour. This does not establish a minimum possible implementation.
Further opportunities include sharing narrowly identical Bingo persistent-marker
codecs (an estimated 70–100 lines), specialising small Jade providers, reading offline
player NBT once for multiple consumers, and reducing redundant leaderboard work.
Each needs comparison of persistence/error semantics or real database query plans;
introducing a generic framework solely to compress these files is not automatically
simpler.

## Separate follow-up findings

These pre-existing concerns were left outside the reduction patch and need focused
reproduction/verification before changing behaviour:

- Retired media sessions can stop a replacement session after delayed resolution;
  cancellation should identify the session, not just the player/block key.
- Settings broadcasts or an in-flight join load can repopulate an offline player's
  cache entry; correcting this needs session-aware ordering.
- A queued save from a retired BlueMap marker store may overwrite a newer store's
  file after reload; shutdown needs a barrier against old writers.
- The persistent-head explosion handler can remove an ordinary head from explosion
  processing even when no saved custom item was recovered.

## Structure and unused-file review

A follow-up review checked Gradle source roots, plugin metadata, registrations,
static imports, reflective protocol hooks, resources and documented manual tools.
It removed the unused `apps/minecraft/src/` tree: an old entry point, nickname
listener, `PackCommand`, `PackJoinListener`, `ResourcePackManager`, configuration
and plugin descriptor. These seven files contained 447 lines (427 Java); the root
Gradle project applies no Java plugin and does not compile or package them. Their
removal therefore does not remove a resource-pack feature from the current build.
These obsolete lines were not included in the active-source totals above.

Two MP3 ringtone resources were also removed (294,002 bytes). Playback already
loads the retained PCM versions; the two obsolete MP3-presence assertions were
removed while PCM decoding and audibility checks remain. Unused Lombok compile
and annotation-processor dependencies were removed as no source uses Lombok.

All 200 remaining Spigot, 59 Velocity and nine shared production Java files have
live references through plugin entry points or the documented `eatingHistory`
tool. The Bingo and Halloween modules are deliberate manual server harnesses.
Optional integrations and schema initialisers remain active and were retained.

The feature packages are generally useful. The main structural weakness is mixed
responsibilities: Paper's 802-line `CrabUtilities` owns 28 fields and coordinates
startup, reload and shutdown; Velocity's 686-line `ConnectionListener` handles
several unrelated connection concerns; its 1,234-line `WebServer` combines routes,
HTTP lifecycle, rate limiting and embedded API documentation. Feature-owned
lifecycle classes and moving feature-specific root classes into packages would
improve navigation, but these are organisational changes rather than unused code.
A [module map](../apps/minecraft/README.md) documents the active layout.

Verification after cleanup: `./gradlew test build` passed, including all 79
executable regression suites and both JAR-content checks. The production Paper
JAR contains both PCM ringtones and none of the obsolete pack classes or MP3s.
`git diff --check` also passed. No live server was started for this cleanup.
