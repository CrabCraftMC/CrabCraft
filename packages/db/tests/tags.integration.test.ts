import { afterAll, beforeAll, beforeEach, describe, expect, test } from "bun:test";
import { and, eq } from "drizzle-orm";
import { drizzle } from "drizzle-orm/postgres-js";
import { generateDrizzleJson, generateMigration } from "drizzle-kit/api";
import postgres from "postgres";
import * as schema from "../src/schema";
import { recordHalloweenAction } from "../src/queries/halloween";
import { backfillHalloweenTags, getHalloweenTagSourceKey, grantTagInTransaction, HALLOWEEN_2026_TAG_ID } from "../src/queries/tags";
import catalogue from "../seeds/tags.json";

const url = process.env.CRABCRAFT_TEST_DATABASE_URL;
const namespace = `tag_test_${crypto.randomUUID().replaceAll("-", "")}`;
const eventId = "halloween-2026";
const completedAt = 1_790_300_000;
let admin: ReturnType<typeof postgres>;
let client: ReturnType<typeof postgres>;
let database: ReturnType<typeof drizzle<typeof schema>>;
const tables = {
  cosmeticTags: schema.cosmeticTags, playerTags: schema.playerTags,
  playerTagState: schema.playerTagState, playerTagAudit: schema.playerTagAudit,
  halloweenEvents: schema.halloweenEvents, halloweenPlayerProgress: schema.halloweenPlayerProgress,
};
const grant = (minecraftUuid: string, sourceKey?: string) => ({
  minecraftUuid, tagId: HALLOWEEN_2026_TAG_ID, grantedAt: completedAt,
  actor: "system:halloween", source: `halloween:${eventId}`, reason: "Completed challenges", sourceKey,
});
async function state(uuid: string) {
  const [row] = await database.select().from(schema.playerTagState).where(eq(schema.playerTagState.minecraft_uuid, uuid));
  return row;
}
async function revokeForTest(uuid: string) {
  // Exercise the same state-lock contract as the independent Java staff writer.
  await database.transaction(async (tx) => {
    await tx.select().from(schema.playerTagState).where(eq(schema.playerTagState.minecraft_uuid, uuid)).for("update");
    await tx.update(schema.playerTagState).set({ selected_tag_id: null }).where(eq(schema.playerTagState.minecraft_uuid, uuid));
    await tx.delete(schema.playerTags).where(eq(schema.playerTags.minecraft_uuid, uuid));
  });
}

describe.skipIf(!url)("Tag transactions in disposable PostgreSQL schema", () => {
  beforeAll(async () => {
    // Never fall back to DATABASE_URL: the caller must explicitly provide a test database.
    admin = postgres(url!, { max: 1 });
    await admin.unsafe(`CREATE SCHEMA "${namespace}"`);
    client = postgres(url!, { max: 6, connection: { search_path: namespace } });
    database = drizzle(client, { schema });
    const ddl = await generateMigration(generateDrizzleJson({}), generateDrizzleJson(tables));
    for (const statement of ddl) await client.unsafe(statement.replaceAll('"public".', `"${namespace}".`));
  });
  beforeEach(async () => {
    await client.unsafe("TRUNCATE cosmetic_tags, player_tags, player_tag_state, player_tag_audit, halloween_events, halloween_player_progress CASCADE");
    await database.insert(schema.cosmeticTags).values(catalogue);
    await database.insert(schema.halloweenEvents).values({
      id: eventId, starts_at: completedAt - 100, ends_at: completedAt + 100, guild_id: "unused", role_id: "",
    });
  });
  afterAll(async () => {
    if (client) await client.end({ timeout: 5 });
    if (admin) {
      await admin.unsafe(`DROP SCHEMA IF EXISTS "${namespace}" CASCADE`);
      await admin.end({ timeout: 5 });
    }
  });

  test("concurrent duplicate grants create one ownership/audit and never auto-select", async () => {
    const uuid = crypto.randomUUID();
    const outcomes = await Promise.all(Array.from({ length: 5 }, () => database.transaction((tx) => grantTagInTransaction(tx, grant(uuid)))));
    expect(outcomes.filter((result) => result.ownershipChanged)).toHaveLength(1);
    expect(await state(uuid)).toMatchObject({ revision: 1n, selected_tag_id: null });
    expect(await database.select().from(schema.playerTags)).toHaveLength(1);
    expect(await database.select().from(schema.playerTagAudit)).toHaveLength(1);
  });

  test("automatic receipt survives revoke; an existing manual owner still consumes its first automatic source", async () => {
    const uuid = crypto.randomUUID();
    const sourceKey = getHalloweenTagSourceKey(eventId, uuid);
    await database.transaction((tx) => grantTagInTransaction(tx, grant(uuid)));
    const automatic = await database.transaction((tx) => grantTagInTransaction(tx, grant(uuid, sourceKey)));
    expect(automatic).toEqual({ ownershipChanged: false, sourceAlreadyRecorded: false, revision: 1n });
    const receipts = await database.select().from(schema.playerTagAudit).where(eq(schema.playerTagAudit.source_key, sourceKey));
    expect(receipts).toHaveLength(1);
    expect(receipts[0].ownership_changed).toBe(false);
    await revokeForTest(uuid);
    const replay = await database.transaction((tx) => grantTagInTransaction(tx, grant(uuid, sourceKey)));
    expect(replay.sourceAlreadyRecorded).toBe(true);
    expect(await database.select().from(schema.playerTags)).toHaveLength(0);
    expect((await database.transaction((tx) => grantTagInTransaction(tx, grant(uuid)))).ownershipChanged).toBe(true);
  });

  test("real constraints reject unowned selections, negative revisions and duplicate automatic keys", async () => {
    const uuid = crypto.randomUUID();
    await database.transaction((tx) => grantTagInTransaction(tx, grant(uuid)));
    await expect(database.update(schema.playerTagState).set({ selected_tag_id: "not-owned" })
      .where(eq(schema.playerTagState.minecraft_uuid, uuid)).execute()).rejects.toThrow();
    await expect(database.update(schema.playerTagState).set({ revision: -1n })
      .where(eq(schema.playerTagState.minecraft_uuid, uuid)).execute()).rejects.toThrow();
    const audit = {
      id: crypto.randomUUID(), minecraft_uuid: uuid, tag_id: HALLOWEEN_2026_TAG_ID,
      action: "grant", ownership_changed: false, actor: "test", source: "test", reason: "test", created_at: completedAt, source_key: "unique-test",
    };
    await database.insert(schema.playerTagAudit).values(audit);
    await expect(database.insert(schema.playerTagAudit).values({ ...audit, id: crypto.randomUUID() }).execute()).rejects.toThrow();
    expect(await state(uuid)).toMatchObject({ revision: 1n, selected_tag_id: null });
  });

  test("failed transaction rolls back ownership, revision, state and audit", async () => {
    const uuid = crypto.randomUUID();
    await expect(database.transaction(async (tx) => {
      await grantTagInTransaction(tx, grant(uuid, getHalloweenTagSourceKey(eventId, uuid)));
      throw new Error("synthetic failure after award");
    })).rejects.toThrow("synthetic failure");
    expect(await state(uuid)).toBeUndefined();
    expect(await database.select().from(schema.playerTags)).toHaveLength(0);
    expect(await database.select().from(schema.playerTagAudit)).toHaveLength(0);
  });

  test("completion awards offline/unlinked UUID atomically and replay cannot restore a revoked tag", async () => {
    const uuid = crypto.randomUUID();
    await database.insert(schema.halloweenPlayerProgress).values({
      event_id: eventId, minecraft_uuid: uuid, hunt_mask: 31, trick_or_treat: true,
    });
    const action = { eventId, minecraftUuid: uuid, action: "back_from_the_dead" as const, occurredAt: completedAt };
    const result = await recordHalloweenAction(action, "100-1", database);
    expect(result?.tagAwarded).toBe(true);
    expect(result?.completedTasks).toBe(3);
    expect(await state(uuid)).toMatchObject({ selected_tag_id: null, revision: 1n });
    const [ownership] = await database.select().from(schema.playerTags);
    expect(ownership).toMatchObject({ minecraft_uuid: uuid, granted_at: completedAt });
    expect(await recordHalloweenAction(action, "100-1", database)).toBeUndefined();
    await revokeForTest(uuid);
    await recordHalloweenAction(action, "100-2", database);
    expect((await backfillHalloweenTags(database, eventId, true)).new).toBe(0);
    expect(await database.select().from(schema.playerTags)).toHaveLength(0);
  });

  test("missing tag definition rolls completion back so the same stream action can retry", async () => {
    const uuid = crypto.randomUUID();
    await database.insert(schema.halloweenPlayerProgress).values({
      event_id: eventId, minecraft_uuid: uuid, hunt_mask: 31, trick_or_treat: true,
    });
    await database.delete(schema.cosmeticTags);
    const action = { eventId, minecraftUuid: uuid, action: "back_from_the_dead" as const, occurredAt: completedAt };
    await expect(recordHalloweenAction(action, "100-1", database)).rejects.toThrow("Unknown cosmetic tag");
    const [progress] = await database.select().from(schema.halloweenPlayerProgress);
    expect(progress).toMatchObject({ completed_at: null, back_from_the_dead: false, last_stream_id: "0-0" });
    expect(await state(uuid)).toBeUndefined();
    await database.insert(schema.cosmeticTags).values(catalogue);
    expect((await recordHalloweenAction(action, "100-1", database))?.tagAwarded).toBe(true);
  });

  test("dry-run/apply/re-run covers completed, incomplete, existing and revoked accounts", async () => {
    const [newPlayer, incomplete, revoked, manual] = Array.from({ length: 4 }, () => crypto.randomUUID());
    for (const uuid of [newPlayer, revoked, manual]) await database.insert(schema.halloweenPlayerProgress).values({
      event_id: eventId, minecraft_uuid: uuid, hunt_mask: 31, trick_or_treat: true, back_from_the_dead: true, completed_at: completedAt,
    });
    await database.insert(schema.halloweenPlayerProgress).values({ event_id: eventId, minecraft_uuid: incomplete });
    await database.transaction((tx) => grantTagInTransaction(tx, grant(revoked, getHalloweenTagSourceKey(eventId, revoked))));
    await revokeForTest(revoked);
    await database.transaction((tx) => grantTagInTransaction(tx, grant(manual)));
    expect(await backfillHalloweenTags(database, eventId, false)).toEqual({ eligible: 3, new: 1, alreadyAwarded: 1, alreadyOwned: 1 });
    expect(await state(newPlayer)).toBeUndefined();
    expect(await backfillHalloweenTags(database, eventId, true)).toEqual({ eligible: 3, new: 1, alreadyAwarded: 1, alreadyOwned: 1 });
    expect(await backfillHalloweenTags(database, eventId, true)).toEqual({ eligible: 3, new: 0, alreadyAwarded: 3, alreadyOwned: 0 });
    const [ownership] = await database.select().from(schema.playerTags).where(and(
      eq(schema.playerTags.minecraft_uuid, newPlayer), eq(schema.playerTags.tag_id, HALLOWEEN_2026_TAG_ID),
    ));
    expect(ownership.granted_at).toBe(completedAt);
    expect(await state(newPlayer)).toMatchObject({ revision: 1n, selected_tag_id: null });
    expect(await state(incomplete)).toBeUndefined();
  });

  test("revisions above the JavaScript safe integer range retain exact bigint precision", async () => {
    const uuid = crypto.randomUUID();
    await database.transaction((tx) => grantTagInTransaction(tx, grant(uuid)));
    await database.update(schema.playerTagState).set({ revision: 9_007_199_254_740_992n })
      .where(eq(schema.playerTagState.minecraft_uuid, uuid));
    await database.insert(schema.cosmeticTags).values({ ...catalogue[0], id: "second-tag" });
    const result = await database.transaction((tx) => grantTagInTransaction(tx, { ...grant(uuid), tagId: "second-tag" }));
    expect(result.revision).toBe(9_007_199_254_740_993n);
    expect((await state(uuid)).revision).toBe(result.revision);
  });

  test("backfill CLI previews, applies and safely reruns with explicit flags", async () => {
    const uuid = crypto.randomUUID();
    await database.insert(schema.halloweenPlayerProgress).values({
      event_id: eventId, minecraft_uuid: uuid, hunt_mask: 31, trick_or_treat: true,
      back_from_the_dead: true, completed_at: completedAt,
    });
    const scopedUrl = new URL(url!);
    scopedUrl.searchParams.set("options", `-c search_path=${namespace}`);
    async function cli(args: string[], databaseUrl = scopedUrl.toString()) {
      const process = Bun.spawn([Bun.which("bun")!, new URL("../scripts/backfill-halloween-tags.ts", import.meta.url).pathname, ...args], {
        env: { ...Bun.env, DATABASE_URL: databaseUrl }, stdout: "pipe", stderr: "pipe",
      });
      const [stdout, stderr, code] = await Promise.all([
        new Response(process.stdout).text(), new Response(process.stderr).text(), process.exited,
      ]);
      return { stdout, stderr, code };
    }
    const preview = await cli(["--event", eventId, "--dry-run"]);
    expect(preview.code).toBe(0);
    expect(preview.stdout).toContain("Dry run: eligible=1, new=1, already-awarded=0, already-owned=0");
    expect(await state(uuid)).toBeUndefined();
    const applied = await cli(["--event", eventId, "--apply"]);
    expect(applied.code).toBe(0);
    expect(applied.stdout).toContain("Applied: eligible=1, new=1, already-awarded=0, already-owned=0");
    expect(await state(uuid)).toMatchObject({ revision: 1n, selected_tag_id: null });
    const repeated = await cli(["--event", eventId, "--apply"]);
    expect(repeated.code).toBe(0);
    expect(repeated.stdout).toContain("Applied: eligible=1, new=0, already-awarded=1, already-owned=0");
    expect(await state(uuid)).toMatchObject({ revision: 1n, selected_tag_id: null });
    for (const args of [["--event", eventId], ["--event", eventId, "--apply", "--dry-run"], ["--event", "another-event", "--apply"]]) {
      const invalid = await cli(args, "not-a-database-url");
      expect(invalid.code).not.toBe(0);
      expect(invalid.stderr).toContain("Usage:");
    }
  });
});
