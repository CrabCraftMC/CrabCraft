import { afterAll, beforeAll, beforeEach, describe, expect, test } from "bun:test";
import { eq } from "drizzle-orm";
import { drizzle } from "drizzle-orm/postgres-js";
import { generateDrizzleJson, generateMigration } from "drizzle-kit/api";
import postgres from "postgres";
import * as schema from "../src/schema";
import {
  createTag, getAdminTags, getTagPlayerDetails, grantPlayerTag, revokePlayerTag, searchTagPlayers,
  TagAdminError, updateTag, validateTagDefinition, validateTagPlayerUuid, type TagDefinitionInput,
} from "../src/queries/tag-admin";
import { getHalloweenTagSourceKey, grantTagInTransaction, HALLOWEEN_2026_TAG_ID } from "../src/queries/tags";

const url = process.env.CRABCRAFT_TEST_DATABASE_URL;
const namespace = `tag_admin_test_${crypto.randomUUID().replaceAll("-", "")}`;
const tag: TagDefinitionInput = {
  id: HALLOWEEN_2026_TAG_ID, name: "Halloween 2026", description: "Completed all three challenges",
  glyph_id: "pumpkin", sort_order: 10, enabled: true,
};
let admin: ReturnType<typeof postgres>;
let client: ReturnType<typeof postgres>;
let database: ReturnType<typeof drizzle<typeof schema>>;
const actor = "discord:123456789012345678";
const mutation = (uuid: string, tagId = tag.id) => ({ minecraftUuid: uuid, tagId, actor, reason: "Event participation" });

test("catalogue validation matches Java definition bounds and rejects unsafe types and control text", () => {
  expect(validateTagDefinition(tag)).toEqual(tag);
  expect(validateTagDefinition({ ...tag, id: "a".repeat(96), glyph_id: "g".repeat(128), name: "n".repeat(160), description: "d".repeat(1024), sort_order: -2147483648 })).toBeDefined();
  expect(validateTagDefinition({ ...tag, sort_order: 2147483647 })).toBeDefined();
  for (const invalid of [
    { id: "a".repeat(97) }, { id: "Halloween" }, { id: "<tag>" }, { glyph_id: "g".repeat(129) }, { glyph_id: "nexo:pumpkin" },
    { name: " " }, { name: "n".repeat(161) }, { description: "d".repeat(1025) }, { name: "hello\u0000" }, { description: "line\nbreak" },
    { sort_order: 0.5 }, { sort_order: NaN }, { sort_order: Infinity }, { sort_order: 2147483648 }, { sort_order: -2147483649 },
    { sort_order: "10" }, { enabled: "true" },
  ]) {
    expect(() => validateTagDefinition({ ...tag, ...invalid } as TagDefinitionInput)).toThrow(TagAdminError);
  }
  const uuid = crypto.randomUUID();
  expect(validateTagPlayerUuid(uuid.toUpperCase())).toBe(uuid);
  expect(() => validateTagPlayerUuid(uuid.replaceAll("-", ""))).toThrow(TagAdminError);
});

describe.skipIf(!url)("Website tag administration in disposable PostgreSQL schema", () => {
  beforeAll(async () => {
    // Test credentials are explicit; never use the application's DATABASE_URL.
    admin = postgres(url!, { max: 1 });
    await admin.unsafe(`CREATE SCHEMA "${namespace}"`);
    client = postgres(url!, { max: 8, connection: { search_path: namespace } });
    database = drizzle(client, { schema });
    const tables = {
      playerRoleEnum: schema.playerRoleEnum, players: schema.players, playerAlts: schema.playerAlts,
      cosmeticTags: schema.cosmeticTags, playerTags: schema.playerTags,
      playerTagState: schema.playerTagState, playerTagAudit: schema.playerTagAudit,
    };
    const ddl = await generateMigration(generateDrizzleJson({}), generateDrizzleJson(tables));
    for (const statement of ddl) await client.unsafe(statement.replaceAll('"public".', `"${namespace}".`));
  });
  beforeEach(async () => {
    await client.unsafe("TRUNCATE cosmetic_tags, player_tags, player_tag_state, player_tag_audit, players, player_alts CASCADE");
    await createTag(tag, database);
  });
  afterAll(async () => {
    if (client) await client.end({ timeout: 5 });
    if (admin) {
      await admin.unsafe(`DROP SCHEMA IF EXISTS "${namespace}" CASCADE`);
      await admin.end({ timeout: 5 });
    }
  });

  test("create rejects duplicates without overwriting metadata; updates require an existing immutable ID", async () => {
    await expect(createTag({ ...tag, name: "Duplicate" }, database)).rejects.toMatchObject({ code: "TAG_EXISTS" });
    expect((await getAdminTags(database))[0]).toEqual({ ...tag, owner_count: 0, selected_count: 0 });
    await expect(updateTag({ ...tag, id: "missing" }, database)).rejects.toMatchObject({ code: "TAG_NOT_FOUND" });
    expect(await updateTag({ ...tag, name: "New name", glyph_id: "new_pumpkin", enabled: false }, database))
      .toMatchObject({ id: tag.id, name: "New name", glyph_id: "new_pumpkin", enabled: false });
    expect(await database.select().from(schema.cosmeticTags)).toHaveLength(1);
  });

  test("catalogue counts remain independent and metadata edits preserve selections, revisions and unlocks", async () => {
    const [first, second] = [crypto.randomUUID(), crypto.randomUUID()];
    await grantPlayerTag(mutation(first), database);
    await grantPlayerTag(mutation(second), database);
    await database.update(schema.playerTagState).set({ selected_tag_id: tag.id }).where(eq(schema.playerTagState.minecraft_uuid, first));
    await createTag({ ...tag, id: "second", name: "Second", sort_order: 0 }, database);
    await updateTag({ ...tag, enabled: false }, database);
    const catalogue = await getAdminTags(database);
    expect(catalogue.map((row) => row.id)).toEqual(["second", tag.id]);
    expect(catalogue[1]).toMatchObject({ enabled: false, owner_count: 2, selected_count: 1 });
    expect(await getTagPlayerDetails(first, database)).toMatchObject({ selected_tag_id: tag.id, revision: "1", tags: [{ id: tag.id, enabled: false }] });
    expect(await getTagPlayerDetails(second, database)).toMatchObject({ selected_tag_id: null, revision: "1" });
  });

  test("catalogue writes enforce the exact 30,000-byte RESULT limit and roll back overflowing edits", async () => {
    await database.delete(schema.cosmeticTags);
    const definitions = Array.from({ length: 30 }, (_, index) => ({
      ...tag, id: `capacity-${index.toString().padStart(2, "0")}`, name: "T", glyph_id: "g",
      description: "d".repeat(index < 28 ? 1000 : 450), enabled: false,
    }));
    await database.insert(schema.cosmeticTags).values(definitions.slice(0, 29));
    // 69 fixed bytes + 11 selected-ID bytes + 30 × 34 record bytes + 28,900 description bytes.
    const last = definitions[29];
    expect(await createTag(last, database)).toEqual(last);
    await expect(updateTag({ ...last, description: "d".repeat(451) }, database)).rejects.toMatchObject({ code: "INVALID_INPUT" });
    await expect(createTag({ ...last, id: "extra", description: "" }, database)).rejects.toMatchObject({ code: "INVALID_INPUT" });
    expect((await getAdminTags(database)).find((row) => row.id === last.id)?.description).toBe(last.description);
    expect(await database.select().from(schema.cosmeticTags)).toHaveLength(30);
    expect(await updateTag({ ...last, description: "d".repeat(449) }, database)).toMatchObject({ description: "d".repeat(449) });
    // Fewer characters can still require more UTF-8 bytes than the saved ASCII text.
    await expect(updateTag({ ...last, description: "é".repeat(300) }, database)).rejects.toMatchObject({ code: "INVALID_INPUT" });
    expect((await getAdminTags(database)).find((row) => row.id === last.id)?.description).toBe("d".repeat(449));
  });

  test("catalogue capacity includes disabled tags and serialises concurrent creates for the final slot", async () => {
    await database.delete(schema.cosmeticTags);
    await database.insert(schema.cosmeticTags).values(Array.from({ length: 127 }, (_, index) => ({
      ...tag, id: `tag-${index}`, name: "T", glyph_id: "g", description: "", enabled: false,
    })));
    const outcomes = await Promise.allSettled(Array.from({ length: 4 }, (_, index) => createTag({
      ...tag, id: `last-${index}`, name: "T", glyph_id: "g", description: "",
    }, database)));
    expect(outcomes.filter((outcome) => outcome.status === "fulfilled")).toHaveLength(1);
    const rejected = outcomes.filter((outcome) => outcome.status === "rejected");
    expect(rejected).toHaveLength(3);
    for (const outcome of rejected) if (outcome.status === "rejected") expect(outcome.reason).toMatchObject({ code: "INVALID_INPUT" });
    await expect(createTag({ ...tag, id: "too-many" }, database)).rejects.toMatchObject({ code: "INVALID_INPUT" });
    expect(await database.select().from(schema.cosmeticTags)).toHaveLength(128);
    expect(await updateTag({ ...tag, id: "tag-0", name: "T", glyph_id: "g", description: "" }, database)).toMatchObject({ id: "tag-0" });
  });

  test("player search finds main and alt identities, treats wildcard characters literally and accepts unlinked UUIDs", async () => {
    const [main, alt, other, unknown] = Array.from({ length: 4 }, () => crypto.randomUUID());
    await database.insert(schema.players).values([
      { discord_id: "1", discord_username: "discord-main", minecraft_uuid: main, minecraft_username: "Alex_One" },
      { discord_id: "2", discord_username: "discord-other", minecraft_uuid: other, minecraft_username: "AlexTwo" },
    ]);
    await database.insert(schema.playerAlts).values({ discord_id: "1", minecraft_uuid: alt, minecraft_username: "Alex_Alt" });
    expect(await searchTagPlayers("alex_", database)).toEqual([
      { minecraft_uuid: alt, minecraft_username: "Alex_Alt", account_type: "alt" },
      { minecraft_uuid: main, minecraft_username: "Alex_One", account_type: "main" },
    ]);
    expect(await searchTagPlayers("%", database)).toEqual([]);
    expect(await searchTagPlayers(main.toUpperCase(), database)).toEqual([
      { minecraft_uuid: main, minecraft_username: "Alex_One", account_type: "main" },
    ]);
    expect(await searchTagPlayers(unknown.toUpperCase(), database)).toEqual([
      { minecraft_uuid: unknown, minecraft_username: null, account_type: "unlinked" },
    ]);
    expect(await searchTagPlayers("does-not-exist", database)).toEqual([]);
    expect(await searchTagPlayers(" ", database)).toEqual([]);
    await expect(searchTagPlayers("x".repeat(65), database)).rejects.toMatchObject({ code: "INVALID_INPUT" });
    await expect(searchTagPlayers("Alex\u0000", database)).rejects.toMatchObject({ code: "INVALID_INPUT" });
    expect((await getTagPlayerDetails(alt, database)).minecraft_username).toBe("Alex_Alt");
  });

  test("search is limited to 20 unique accounts and prefers a main identity for duplicate links", async () => {
    const playerRows = Array.from({ length: 25 }, (_, index) => ({
      discord_id: `${index}`, discord_username: `discord${index}`, minecraft_uuid: crypto.randomUUID(), minecraft_username: `Player${index.toString().padStart(2, "0")}`,
    }));
    await database.insert(schema.players).values(playerRows);
    await database.insert(schema.playerAlts).values({ discord_id: "0", minecraft_uuid: playerRows[0].minecraft_uuid, minecraft_username: "Player00" });
    const found = await searchTagPlayers("Player", database);
    expect(found).toHaveLength(20);
    expect(new Set(found.map((row) => row.minecraft_uuid)).size).toBe(20);
    expect(found[0].account_type).toBe("main");
  });

  test("unlinked account inspection is read-only and exact revisions survive JSON serialisation", async () => {
    const uuid = crypto.randomUUID();
    expect(await getTagPlayerDetails(uuid, database)).toEqual({
      minecraft_uuid: uuid, minecraft_username: null, selected_tag_id: null, revision: "0", tags: [], history: [],
    });
    expect(await database.select().from(schema.playerTagState)).toHaveLength(0);
    await grantPlayerTag(mutation(uuid), database);
    await database.update(schema.playerTagState).set({ revision: 9_007_199_254_740_993n });
    const details = await getTagPlayerDetails(uuid, database);
    expect(JSON.parse(JSON.stringify(details)).revision).toBe("9007199254740993");
    expect(details.tags[0]).toMatchObject({ source: "website-admin", granted_by: actor });
    expect(details.history[0]).toMatchObject({ action: "grant", tag_name: tag.name, source: "website-admin", actor, reason: "Event participation" });
  });

  test("grant never auto-equips and revoke clears an equipped tag once without disturbing other ownership", async () => {
    const uuid = crypto.randomUUID();
    await createTag({ ...tag, id: "second" }, database);
    expect(await grantPlayerTag(mutation(uuid), database)).toBe(true);
    expect(await grantPlayerTag(mutation(uuid), database)).toBe(false);
    expect((await getTagPlayerDetails(uuid, database)).selected_tag_id).toBeNull();
    await database.update(schema.playerTagState).set({ selected_tag_id: tag.id }).where(eq(schema.playerTagState.minecraft_uuid, uuid));
    expect(await grantPlayerTag(mutation(uuid, "second"), database)).toBe(true);
    expect((await getTagPlayerDetails(uuid, database)).selected_tag_id).toBe(tag.id);
    expect(await revokePlayerTag(mutation(uuid, "second"), database)).toBe(true);
    expect((await getTagPlayerDetails(uuid, database)).selected_tag_id).toBe(tag.id);
    expect(await revokePlayerTag(mutation(uuid), database)).toBe(true);
    expect(await revokePlayerTag(mutation(uuid), database)).toBe(false);
    const details = await getTagPlayerDetails(uuid, database);
    expect(details).toMatchObject({ selected_tag_id: null, revision: "4", tags: [] });
    expect(details.history).toHaveLength(4);
    expect(details.history.filter((entry) => entry.action === "revoke")).toHaveLength(2);
    expect(details.history.every((entry) => entry.actor === actor && entry.source === "website-admin")).toBe(true);
  });

  test("concurrent duplicate and competing mutations follow the shared persistent state lock", async () => {
    const uuid = crypto.randomUUID();
    const grants = await Promise.all(Array.from({ length: 6 }, () => grantPlayerTag(mutation(uuid), database)));
    expect(grants.filter(Boolean)).toHaveLength(1);
    await database.update(schema.playerTagState).set({ selected_tag_id: tag.id }).where(eq(schema.playerTagState.minecraft_uuid, uuid));
    const revokes = await Promise.all(Array.from({ length: 6 }, () => revokePlayerTag(mutation(uuid), database)));
    expect(revokes.filter(Boolean)).toHaveLength(1);
    expect(await getTagPlayerDetails(uuid, database)).toMatchObject({ selected_tag_id: null, revision: "2", tags: [] });
    const mixed = await Promise.all(Array.from({ length: 8 }, (_, index) => index % 2
      ? revokePlayerTag(mutation(uuid), database) : grantPlayerTag(mutation(uuid), database)));
    const details = await getTagPlayerDetails(uuid, database);
    expect(details.revision).toBe(`${2 + mixed.filter(Boolean).length}`);
    expect(details.history).toHaveLength(2 + mixed.filter(Boolean).length);
    expect(details.selected_tag_id).toBeNull();
    expect(details.tags.length).toBe(Number(mixed.filter(Boolean).length % 2 === 1));
  });

  test("revocation preserves automatic award receipts so Halloween replays cannot restore ownership", async () => {
    const uuid = crypto.randomUUID();
    const automatic = {
      minecraftUuid: uuid, tagId: tag.id, grantedAt: 1_790_300_000, actor: "system:halloween",
      source: "halloween:halloween-2026", reason: "Completed challenges", sourceKey: getHalloweenTagSourceKey("halloween-2026", uuid),
    };
    await database.transaction((tx) => grantTagInTransaction(tx, automatic));
    await revokePlayerTag(mutation(uuid), database);
    expect((await database.transaction((tx) => grantTagInTransaction(tx, automatic))).sourceAlreadyRecorded).toBe(true);
    expect(await getTagPlayerDetails(uuid, database)).toMatchObject({ revision: "2", tags: [] });
    const receipts = await database.select().from(schema.playerTagAudit).where(eq(schema.playerTagAudit.source_key, automatic.sourceKey));
    expect(receipts).toHaveLength(1);
    expect(await grantPlayerTag(mutation(uuid), database)).toBe(true);
    expect((await getTagPlayerDetails(uuid, database)).revision).toBe("3");
  });

  test("unknown definitions and invalid mutation data cannot write state, ownership or audit", async () => {
    const uuid = crypto.randomUUID();
    await expect(grantPlayerTag(mutation(uuid, "missing"), database)).rejects.toMatchObject({ code: "TAG_NOT_FOUND" });
    await expect(revokePlayerTag(mutation(uuid, "missing"), database)).rejects.toMatchObject({ code: "TAG_NOT_FOUND" });
    for (const invalid of [{ reason: " " }, { reason: "x".repeat(513) }, { reason: "hidden\u0000" }, { actor: "console" }, { minecraftUuid: "bad-uuid" }]) {
      await expect(grantPlayerTag({ ...mutation(uuid), ...invalid }, database)).rejects.toMatchObject({ code: "INVALID_INPUT" });
      await expect(revokePlayerTag({ ...mutation(uuid), ...invalid }, database)).rejects.toMatchObject({ code: "INVALID_INPUT" });
    }
    expect(await database.select().from(schema.playerTags)).toHaveLength(0);
    expect(await database.select().from(schema.playerTagState)).toHaveLength(0);
    expect(await database.select().from(schema.playerTagAudit)).toHaveLength(0);
  });

  test("history returns the latest 50 entries with catalogue names", async () => {
    const uuid = crypto.randomUUID();
    await database.insert(schema.playerTagAudit).values(Array.from({ length: 60 }, (_, index) => ({
      id: crypto.randomUUID(), minecraft_uuid: uuid, tag_id: tag.id, action: "grant", ownership_changed: false,
      actor, source: "test", reason: `Receipt ${index}`, created_at: 1000 + index,
    })));
    const { history } = await getTagPlayerDetails(uuid, database);
    expect(history).toHaveLength(50);
    expect(history[0]).toMatchObject({ created_at: 1059, reason: "Receipt 59", tag_name: tag.name });
    expect(history[49].created_at).toBe(1010);
  });
});
