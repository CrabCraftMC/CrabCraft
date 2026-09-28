# Minecraft Kotlin migration

This migration was written from the locally refactored sources. PR #113 supplied
scope context only: its patch and branch were not applied or copied. The preceding
[refactoring review](minecraft-refactoring-review.md) and unused-file cleanup are
retained. The changes remain local; no push or pull request was made.

## Scope

- All 354 maintained Minecraft source files moved from Java to Kotlin: 274 main
  files and 80 retained test/helper files across Paper, Velocity, shared code and
  both manual server harnesses.
- Six Groovy Gradle build/settings scripts became Kotlin DSL. Gradle remains the
  build tool, with the existing wrapper, dependency versions and Java 25 target.
- Kotlin JVM 2.4.20 compiles the plugins. Velocity's existing plugin annotation is
  processed through kapt, and its generated version constant is now Kotlin.
- All four deployable plugin/harness JARs include the Kotlin standard library and
  its upstream licence/notice. Production filenames, descriptors, relocations and
  PostgreSQL driver registration are preserved.
- The 79 executable regression suites remain registered under `test`; existing CI
  invokes `./gradlew test build`. The two packaging checks remain enabled.
- Source-path documentation and dependency notices now point at the Kotlin files.
  Kotlin cache files are ignored. Minecraft's EditorConfig defines four-space
  indentation and a 120-column limit; ktfmt 0.64 was used for the migration.

## Behaviour and compatibility

Existing command names, permissions, feature registrations, optional integrations,
SQL, persistence markers and wire formats were retained. The database schema was
not changed. The previous shared updater, Bingo lifecycle, media, voice, portal,
statistics and nickname reductions remain in the converted code.

The JVM comparison checks existing public/protected classes, method and field
signatures, constructors, static/visibility modifiers, extensibility, annotations,
constants and declared checked exceptions. Kotlin defaults that would have made
previously extensible classes final were corrected. Original guarded absent-service
behaviour was retained for startup and shutdown rather than replacing null returns
with uninitialised-property exceptions.

Java records became Kotlin data/value-holder classes with the existing record-style
accessor methods retained. They no longer inherit `java.lang.Record`. Repository
searches found no record-reflection consumers; integrations outside this repository
that inspect `Class.isRecord()` are not covered by that conclusion.

## Verification

`./gradlew clean test build --continue --console=plain` passed: 115 tasks,
including all 79 executable regression suites and both JAR-content checks. No
regression suite was skipped or considered up to date. Existing Gradle/API
deprecation warnings remain. A clean build exposed the default 512 MiB compiler
heap limit; Gradle now receives 1 GiB and the Kotlin compiler 2 GiB.

Final checks also confirmed:

- Every baseline Java source has a Kotlin counterpart; no maintained Java or
  Groovy build scripts remain in the Minecraft tree.
- Production descriptors and bundled application resources match the baseline;
  PostgreSQL's service-loader registration remains intact.
- All four JARs contain Kotlin runtime classes and exact upstream licence/notice
  text. Production bytecode targets Java 25, with no stale Java class output.
- The JVM API comparison found no unintended member/constructor/extensibility,
  annotation, static, constant, exception or concurrency-modifier differences.
  The record-superclass limitation above is explicit.
- The final Jade probe resolved all five annotated payloads and preserved both
  unknown-provider-ID decoders. SQL and voice Lua comparisons against the baseline
  retained the existing statements; all HTTP documentation strings match.
- `git diff --check` and the whole-tree Kotlin formatting check passed.

## Size after formatting

Counts include comments and blank lines, and include new files. The baseline is
the already-refactored local Java code immediately before this migration.

| Maintained sources | Before | After | Change |
| --- | ---: | ---: | ---: |
| Production and manual harness code | 46,611 | 39,932 | -6,679 |
| Retained tests and test helpers | 8,864 | 9,899 | +1,035 |
| **Total source** | **55,475** | **49,831** | **-5,644 (10.2% smaller)** |
| Six Gradle scripts | 671 | 584 | -87 |

The test suite count is unchanged; formatted Kotlin mocks are more verbose in some
places. Smaller source code also does not mean smaller deployment JARs: those now
bundle Kotlin's runtime.

No live Paper/Velocity server, player gameplay, production database or cross-server
voice session was exercised during this migration. Compilation, retained regression
suites, isolated protocol/reflection checks and source/artefact comparisons support
behaviour preservation; they do not prove every live integration path. No production
latency improvement is claimed solely from changing language.
