# CrabBingoCard3Test

This is a standalone Paper 26.2 test harness for Bingo #3. It has no Redis, database, Discord or
production CrabUtilities dependency. Creative and Survival players are both tracked.

## Install

1. Put `CrabBingoCard3Test.jar` in the server's `plugins` directory.
2. Remove any older Crab bingo test JAR.
3. Restart Paper and join the server.

The plugin sends the 16-task checklist when you join. `/bingotest` or `/bingotest list` shows it
again, `/bingotest details <1-16>` explains an exact detector, and `/bingotest reset` clears your
memory-only progress and all detector attribution state.

The test harness registers only the four Bingo #3 detector groups. It does not announce to
Discord, store progress or activate any detector from an earlier card.

## Important test setup

- **Third-colour sheep:** use two differently coloured parents; the lamb must be a different
  colour from both of them.
- **Fox Totem:** drop the Totem yourself, let the fox pick it up, then make that exact fox take
  otherwise-fatal damage.
- **Johnny Vindicator:** apply the `Johnny` name yourself after starting the test, then let that
  exact Vindicator deal the final blow to another hostile mob.
- **Shulker duplication:** the bullet must originally target you and then hit a different Shulker.
- **Leashed Frog:** leash the Frog yourself, then either keep holding its lead or tie it to a
  fence while it eats a small Magma Cube and creates a Froglight.
- **Charged Creeper head:** charge the Creeper with lightning from your Channeling trident, then
  use that exact Creeper's explosion to produce the head.
- **Copper trumpet:** play the Note Block sound once for each of the four copper oxidation stages.

Use `/bingotest details <number>` for the detector-specific rules for every other square.

Build from `apps/minecraft` with:

```sh
./gradlew :bingo-test:clean :bingo-test:check :bingo-test:build
```

The JAR is written to `bingo-test/build/libs/CrabBingoCard3Test.jar`.
