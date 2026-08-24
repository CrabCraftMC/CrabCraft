# CrabBingoCard4Test

This is a standalone Paper 26.2 test harness for Bingo #4. It has no Redis, database, Discord or
production CrabUtilities dependency. Creative and Survival players are both tracked.

## Install

1. Put `CrabBingoCard4Test.jar` in the server's `plugins` directory.
2. Remove any older Crab bingo test JAR.
3. Restart Paper and join the server.

The plugin sends the 16-task checklist when you join. `/bingotest` or `/bingotest list` shows it
again, `/bingotest details <1-16>` explains an exact detector, and `/bingotest reset` clears your
memory-only progress and all detector attribution state.

The test harness registers only the four Bingo #4 detector groups. It does not announce to
Discord, store progress or activate any detector from an earlier card. Starting the harness
invalidates attribution left on entities by an earlier test run.

## Important test setup

- **Sulfur Cube bucket:** use an empty Bucket on an adult, unignited Sulfur Cube while it is
  carrying a Diamond Block. It does not matter who gave the block to the cube.
- **Snow Golem:** build the golem yourself and let one of its snowballs deal the Blaze's final
  damage.
- **Nether portal:** the open portal interior must be exactly four blocks wide and four blocks
  high; light it yourself.
- **Crossbow Firework:** one rocket fired from your crossbow must deal the final blow to two
  different hostile mobs.
- **Happy Ghast Boat:** equip the adult Happy Ghast with a harness yourself, then personally
  leash a Boat to that same ghast while the Boat still contains a hostile mob.
- **Silverfish:** use a named Name Tag on the Silverfish yourself, then leave that exact
  Silverfish beside stone, cobblestone, stone bricks or deepslate until it enters the block.
- **Brown Mooshroom:** feed the Wither Rose and collect the Suspicious Stew from the same Brown
  Mooshroom.
- **Ender Pearl:** the completed teleport must finish at least 100 horizontal blocks from where you threw; height does not count
  that one pearl.
- **Breeze:** deflect the Breeze's own Wind Charge and make it deal the final blow to that Breeze.
- **Decorated Pot:** put an item inside the pot before firing the projectile from at least ten
  blocks away.
- **Skeleton:** place the Powder Snow yourself and keep the Skeleton inside it until conversion.
- **Honey Bottle:** give yourself Poison, then drink one Honey Bottle while the effect is active.

Use `/bingotest details <number>` for the detector-specific rules for every other square.

Build from `apps/minecraft` with:

```sh
./gradlew :bingo-test:clean :bingo-test:check :bingo-test:build
```

The JAR is written to `bingo-test/build/libs/CrabBingoCard4Test.jar`.
