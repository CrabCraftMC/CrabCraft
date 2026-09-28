# CrabUtilities Minecraft plugins

This directory is a Gradle multi-project build using Kotlin and Kotlin DSL. Production code lives in the
module source roots below; the root project coordinates builds and JAR collection.

| Directory | Purpose | Entry point |
| --- | --- | --- |
| `spigot/` | Paper gameplay features, commands and client integrations | `spigot/src/main/kotlin/crabcraft/net/crabUtilities/CrabUtilities.kt` |
| `velocity/` | Proxy services, cross-server messaging, persistence and public API | `velocity/src/main/kotlin/crabcraft/net/crabUtilities/velocity/CrabUtilitiesVelocity.kt` |
| `shared/` | Kotlin protocols and helpers compiled into both production plugins | Included by the Spigot and Velocity Gradle files; no separate plugin |
| [`bingo-test/`](bingo-test/README.md) | Standalone manual Bingo server harness | See its README for setup and commands |
| [`halloween-test/`](halloween-test/README.md) | Manual Halloween server harness with isolated services | See its README for setup and commands |

Within each production module, `src/main/kotlin` contains implementation,
`src/main/resources` contains bundled configuration and assets, and
`src/test/kotlin` contains automated regression checks. Feature packages such as
`bingo`, `media`, `voicechat` and `settings` group related commands, listeners and
services. Paper's `plugin.yml` declares its entry point and Bukkit commands;
Velocity's entry point uses `@Plugin`. Additional registrations happen in Kotlin.

Kotlin 2.4.20 is managed by Gradle, so no standalone Kotlin installation is needed.
See the [migration review](../../docs/minecraft-kotlin-migration.md) for compatibility
and verification details.

From this directory, using Java 25:

```sh
./gradlew test build
```

This runs the retained automated regression checks and build checks. The two
production artefacts are collected in `jars/CrabUtilities.jar` and
`jars/CrabUtilities-Velocity.jar`. The manual harnesses are checked by the build,
but their servers are started separately using their documented `runServer` tasks.
