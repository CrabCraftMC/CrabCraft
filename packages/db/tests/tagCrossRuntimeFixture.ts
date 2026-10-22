import { eq } from "drizzle-orm";
import { drizzle } from "drizzle-orm/postgres-js";
import postgres from "postgres";
import * as schema from "../src/schema";
import { getHalloweenTagSourceKey, grantTagInTransaction, HALLOWEEN_2026_TAG_ID } from "../src/queries/tags";

const url = process.env.CRABCRAFT_TEST_DATABASE_URL;
const namespace = process.env.CRABCRAFT_TEST_SCHEMA;
const phase = Bun.argv[Bun.argv.indexOf("--phase") + 1];
if (!url || !namespace || !/^tags?_test_[a-z0-9_]+$/.test(namespace) || !["prepare", "verify"].includes(phase)) {
  throw new Error("Cross-runtime fixture requires explicit test URL, tag_* disposable schema and --phase prepare|verify");
}
const client = postgres(url, { max: 1, connection: { search_path: namespace } });
const database = drizzle(client, { schema });
const uuid = "11111111-1111-4111-8111-111111111111";
const award = {
  minecraftUuid: uuid, tagId: HALLOWEEN_2026_TAG_ID, grantedAt: 1_790_300_000,
  actor: "system:halloween", source: "halloween:halloween-2026", reason: "Cross-runtime fixture",
  sourceKey: getHalloweenTagSourceKey("halloween-2026", uuid),
};
try {
  if (phase === "prepare") {
    const result = await database.transaction((tx) => grantTagInTransaction(tx, award));
    if (!result.ownershipChanged || result.revision !== 1n) throw new Error("TypeScript fixture grant failed");
  } else {
    const [state] = await database.select().from(schema.playerTagState).where(eq(schema.playerTagState.minecraft_uuid, uuid));
    if (state?.revision !== 5n || state.selected_tag_id !== HALLOWEEN_2026_TAG_ID) {
      throw new Error("TypeScript did not read the Java selection/regrant at revision 5");
    }
    const replay = await database.transaction((tx) => grantTagInTransaction(tx, award));
    if (replay.ownershipChanged || !replay.sourceAlreadyRecorded || replay.revision !== 5n) {
      throw new Error("Java mutation lost the TypeScript automatic award receipt");
    }
  }
  console.log(`TypeScript cross-runtime ${phase} passed`);
} finally {
  await client.end({ timeout: 5 });
}
