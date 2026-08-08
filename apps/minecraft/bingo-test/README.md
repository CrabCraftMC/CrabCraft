# CrabBingoCard2Test

This is a standalone Paper 26.2 test harness for Bingo #2. It has no Redis, database, Discord or
production CrabUtilities dependency. Creative and Survival players are both tracked.

## Install

1. Put `CrabBingoCard2Test.jar` in the server's `plugins` directory.
2. Remove any older Crab bingo test JAR.
3. Restart Paper and join the server.

The plugin sends the 16-task checklist when you join. `/bingotest` or `/bingotest list` shows it
again, `/bingotest details <1-16>` explains an exact detector, and `/bingotest reset` clears your
memory-only progress and all detector attribution state.

The test harness registers only the Bingo #2 listener. It does not announce to Discord, store
progress or activate any Bingo #1 detector.

## Important test setup

- **Self-arrow Totem:** use Survival, set `pvp=true`, leave any no-friendly-fire scoreboard team,
  hold a Totem and arrange for your own returning arrow to deal the fatal hit.
- **Copper golem statue:** use Survival and keep `doTileDrops` enabled so the mined statue can
  produce its item drop.
- **TNT minecart:** this is not a direct right-click action. Break its rail, light the supporting
  block so fire overlaps the minecart, then wait for the minecart to explode.
- **Shelf hotbar swap:** use exactly three connected, powered shelves facing the same direction,
  then interact to exchange all nine shelf slots with all nine hotbar slots.

Use `/bingotest details <number>` for the detector-specific rules for every other square.

Build from `apps/minecraft` with:

```sh
./gradlew :bingo-test:clean :bingo-test:check :bingo-test:build
```

The JAR is written to `bingo-test/build/libs/CrabBingoCard2Test.jar`.
