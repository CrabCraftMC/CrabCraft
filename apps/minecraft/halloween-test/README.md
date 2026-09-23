# Halloween manual test server

Minecraft Java **26.2**, **127.0.0.1:25565** on the computer running the server.
This harness uses the production `HalloweenManager`, bell detector, action
consumer, PostgreSQL progress queries and Minecraft command output. The separate
local event is active immediately. It does not connect to Discord or grant roles.
No start announcement is sent.

Joining players become operators and receive a carved pumpkin, armour, sword,
food, mob spawn eggs, bell, pressure plate, golden apple and weakness potions.
The harness refuses to run unless the server binds to `127.0.0.1`. Do not install
it on a public server.

Run `/halloween` for a four-line progress checklist. Hover over a task for its
rules and remaining mobs.
Use Creative to arrange the test, then `/gamemode survival` to perform the tasks:

- Wear the pumpkin, kill some of the five named mobs, and run `/halloween`.
  `/kill` should reset an unfinished hunt; finishing all five makes it permanent.
- Personally place the plate directly beside a bell, then lure a creeper over it.
  Replace the plate after a server restart, as in the production detector.
- Splash a zombie villager with Weakness, give it a golden apple, and stay online
  in Survival wearing the pumpkin until it finishes curing. Operators can speed
  up the wait after feeding it with
  `/data modify entity @e[type=minecraft:zombie_villager,sort=nearest,limit=1] ConversionTime set value 100`.

Allow a few seconds for progress to reach the database before repeating the
command. Relogging preserves saved progress. The two previously reviewed queue
persistence and cross-backend ordering issues remain outside this command change.

## Start

Create isolated services once (these ports are separate from normal development):

```sh
docker run -d --name crabcraft-halloween-test-db -p 127.0.0.1:15439:5432 -e POSTGRES_USER=halloween_test -e POSTGRES_PASSWORD=halloween_local -e POSTGRES_DB=halloween_test postgres:18-alpine
docker run -d --name crabcraft-halloween-test-redis -p 127.0.0.1:16389:6379 redis:alpine
```

On later runs use `docker start crabcraft-halloween-test-db crabcraft-halloween-test-redis`.
From the repository root, keep the worker running in one terminal:

```sh
bun apps/bot/src/scripts/runHalloweenTest.ts
```

In another terminal:

```sh
cd apps/minecraft
./gradlew :halloween-test:runServer --console=plain
```

Enter `stop` in the Minecraft console to stop the server. Stop the worker with
Ctrl+C, then stop the two named Docker containers. Test data stays in those
containers; it never touches the production database.
