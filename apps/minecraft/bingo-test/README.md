# CrabBingoCard5Test

This is a standalone Paper 26.2 test harness for Bingo #5. It has no Redis, database, Discord or
production CrabUtilities dependency. Creative and Survival players are both tracked. The bundled
local server binds to `127.0.0.1`; joining players are made Creative operators for manual testing.

## Install

1. Put `CrabBingoCard5Test.jar` in the server's `plugins` directory.
2. Remove any older Crab bingo test JAR.
3. Restart Paper and join the server.

The plugin sends the 16-task checklist when you join. `/bingotest` or `/bingotest list` shows it
again, `/bingotest details <1-16>` explains an exact detector, and `/bingotest reset` clears your
memory-only progress and all detector attribution state.

The test harness registers only the four Bingo #5 detector groups. It does not announce to
Discord, store checklist progress or activate any detector from an earlier card. Card-scoped
block and entity attribution survives a server restart so the production restart behavior can be
tested; `/bingotest reset` starts a fresh detector window for the issuing player.

## Important test setup

- **Big Dripleaf:** place or grow every block in one vertical column at least ten blocks tall.
- **Mob hat:** ordinary Zombies are not guaranteed to pick up loot. For a deterministic test, run
  `/gamerule mobGriefing true`, then
  `/summon minecraft:zombie ~ ~ ~ {CanPickUpLoot:1b,PersistenceRequired:1b}` and drop the headgear
  yourself at that Zombie's feet.
- **Banner:** clean a patterned Banner in a water-filled Cauldron.
- **Panda:** drop a Cake close enough for a Panda to pick up that exact dropped item.
- **Grindstone:** take a result that actually removes a non-curse enchantment.
- **Zoglin:** name-tag a Hoglin yourself before moving it out of the Nether and waiting for its
  conversion.
- **Lodestone:** bind a Compass by using it on the Lodestone.
- **Enderman:** name-tag it first. From that point onwards, any non-Endermite damage invalidates
  that attempt; an Endermite must deal the final blow.
- **Bookshelf:** personally insert the sixth Enchanted Book into one Chiseled Bookshelf.
- **Pillager:** in this loopback harness, stand within 32 blocks in Creative while the Pillager
  shoots another valid target such as an Iron Golem. Give it a nearly broken Crossbow for a quick
  test. Production only awards this square when the Pillager is targeting the player.
- **Chicken:** one of the Eggs you throw must hatch a Chicken.
- **Snow:** build one connected arrangement containing Snow layers of every thickness, 1–8.
- **Iron Golem:** use an Iron Ingot on a genuinely damaged Iron Golem.
- **Furnace Minecart:** add Coal or Charcoal to it yourself.
- **End Crystal:** place the exact Crystal whose explosion kills a hostile mob.
- **Armour:** equip four armour slots using four distinct base material families.

Use `/bingotest details <number>` for the detector-specific rules for every other square.

Build from `apps/minecraft` with:

```sh
./gradlew :bingo-test:clean :bingo-test:check :bingo-test:build
```

The JAR is written to `bingo-test/build/libs/CrabBingoCard5Test.jar`.

## Launch the isolated test server

From `apps/minecraft`, run:

```sh
./gradlew :bingo-test:runServer --console=plain
```

Connect with a Minecraft 26.2 client at `localhost:25565`. The server is online-mode and
loopback-only. Do not copy the harness's automatic operator grant into a public server.
