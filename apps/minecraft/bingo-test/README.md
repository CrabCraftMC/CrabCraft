# CrabBingoCard6Test

This is a standalone Paper 26.2 test harness for Bingo #6. It has no Redis, database, Discord or
production CrabUtilities dependency. Creative and Survival players are both tracked. The bundled
local server binds to `127.0.0.1`; joining players are made Creative operators for manual testing.

## Install

1. Put `CrabBingoCard6Test.jar` in the server's `plugins` directory.
2. Remove any older Crab bingo test JAR.
3. Restart Paper and join the server.

The plugin sends the 16-task checklist when you join. `/bingotest` or `/bingotest list` shows it
again, `/bingotest details <1-16>` explains an exact detector, and `/bingotest reset` clears your
memory-only progress and all detector attribution state.

The test harness registers only the three Bingo #6 detector groups. It does not announce to
Discord, store checklist progress or activate any detector from an earlier card. Card-scoped
intermediate progress and entity attribution survive a server restart so production restart
behaviour can be tested; `/bingotest reset` starts a fresh detector window for the issuing player.

## Important test setup

- **Painting:** place a Painting whose selected art occupies exactly 4×4 blocks.
- **Hanging Sign:** write text on a Hanging Sign, then use a Glow Ink Sac to outline that text.
- **Campfire:** personally insert the fourth cookable item so all four cooking slots are occupied.
- **Button:** fire an Arrow that hits and activates a wooden Button.
- **Fishing:** catch at least one vanilla Treasure item and one vanilla Junk item yourself. The two
  categories can be caught in either order and the partial result is card-scoped across restarts.
- **Llama:** equip a Carpet on a living Llama, directly or through its inventory.
- **Enchanting:** successfully enchant five distinct base item materials at an Enchanting Table.
  Re-enchanting the same material does not add another one.
- **Mending Book:** drop the exact Enchanted Book containing Mending as an item and let it enter
  Lava. Placing or dispensing somebody else's book does not count.
- **Ravager:** temporarily switch to Survival with `/gamemode survival`, block a Ravager's attack
  with your raised Shield, and repeat until the Ravager actually becomes stunned.
- **Conduit:** complete a 42-block valid frame by personally placing the final frame block, leaving
  the Conduit at full power.
- **Bee:** hit a Bee with a thrown Splash or Lingering Potion that actually applies Poison.
- **Nether Fish:** empty a Cod, Salmon, Pufferfish or Tropical Fish Bucket in the Nether.
- **Ghast:** name-tag a Ghast yourself, then send that same Ghast through a Nether Portal into the
  Overworld. Its card-scoped ownership marker survives a restart.
- **Ender Chest:** personally fill the final empty slot so all 27 slots contain an item.
- **Armour Trim:** take a successful Smithing Table result that applies an Armour Trim.
- **Decorated Pot:** craft one using four different Pottery Sherds in its four ingredient slots.

Use `/bingotest details <number>` for the exact detector rule for each square.

Build from `apps/minecraft` with:

```sh
./gradlew :bingo-test:clean :bingo-test:check :bingo-test:build
```

The JAR is written to `bingo-test/build/libs/CrabBingoCard6Test.jar`.

## Launch the isolated test server

From `apps/minecraft`, run:

```sh
./gradlew :bingo-test:runServer --console=plain
```

Connect with a Minecraft 26.2 client at `localhost:25565`. The server is online-mode and
loopback-only. Do not copy the harness's automatic operator grant into a public server.
