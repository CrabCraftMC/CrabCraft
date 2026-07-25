# CrabCraft Agent Instructions

## Keep PostgreSQL schemas synchronized

CrabCraft defines its PostgreSQL schema in two places:

- TypeScript/Drizzle: `packages/db/src/schema.ts`
- Java/JDBC: inline SQL under `apps/minecraft/velocity/src/main/java/crabcraft/net/crabUtilities/velocity/db/`

Whenever a PostgreSQL table, column, index, constraint, or enum is added, changed, or removed, audit both definitions and update both sides in the same change. Do not leave a table managed only by Java `CREATE TABLE` SQL or only by Drizzle.

Pay particular attention to Java-owned tables such as `player_settings`, `player_login_streaks`, and `player_login_streak_progress`. Before recommending or running `db:push`, compare all Java `CREATE TABLE` statements with the Drizzle schema so schema synchronization cannot treat a live Java table as unknown drift.

After schema changes, verify both runtimes:

```sh
bun run --cwd apps/bot build
cd apps/minecraft && ./gradlew test
```
