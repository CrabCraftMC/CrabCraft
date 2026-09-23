import fs from "node:fs";
import Redis from "ioredis";

// Deliberately refuse remote services: this harness must never touch production.
const databaseUrl = "postgres://halloween_test:halloween_local@127.0.0.1:15439/halloween_test";
process.env.DATABASE_URL = databaseUrl;
const { db } = await import("@crabcraft/db/client");
const redisOptions = { host: "127.0.0.1", port: 16389, maxRetriesPerRequest: null };
const control = new Redis(redisOptions);
const { configureHalloweenEvent } = await import("@crabcraft/db/queries/halloween");
const { consumeHalloweenActions, consumeHalloweenProgressRequests } = await import("../utils/halloweenTransport.js");

// Use the production bootstrap DDL so the local schema cannot drift from Velocity.
const repository = fs.readFileSync(new URL("../../../minecraft/velocity/src/main/java/crabcraft/net/crabUtilities/velocity/db/HalloweenRepository.java", import.meta.url), "utf8");
for (const statement of repository.matchAll(/"""([\s\S]*?)"""/g)) await db.$client.unsafe(statement[1]);
const eventId = "halloween-local-test";
const startsAt = Math.floor(Date.now() / 1000) - 86400;
const endsAt = startsAt + 8 * 86400;
await configureHalloweenEvent({ id: eventId, starts_at: startsAt, ends_at: endsAt, guild_id: "local-test", role_id: "" });
await control.set("crabcraft:halloween:active-event", JSON.stringify({ id: eventId, startsAt, endsAt }), "EXAT", endsAt);
void consumeHalloweenActions(new Redis(redisOptions));
void consumeHalloweenProgressRequests(new Redis(redisOptions), eventId, true);
console.log("Local Halloween worker ready. Discord is not connected; no roles or messages will be sent.");
