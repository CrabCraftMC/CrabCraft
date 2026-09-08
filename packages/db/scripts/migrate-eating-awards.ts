/** Switch only the nine eating readers; preview unless --apply is supplied. */
const ids = [
  "eat_bread", "eat_cookie", "eat_fish", "eat_junkfood", "eat_meat",
  "eat_rawmeat", "eat_soup", "eat_veggie", "eat_sweet_berries",
];
const rows = (await Bun.file(new URL("../seeds/awards.json", import.meta.url)).json())
  .filter((row: { id: string }) => ids.includes(row.id));
if (rows.length !== ids.length || new Set(rows.map((row: { id: string }) => row.id)).size !== ids.length
  || rows.some((row: { id: string; reader: { type: string; path: string[] } }) =>
    row.reader.type !== "custom-int" || JSON.stringify(row.reader.path) !== JSON.stringify([row.id]))) {
  throw new Error("Eating award definitions do not match the consumption tracker");
}

console.log("Eating readers to update:", ids.join(", "));
if (!Bun.argv.includes("--apply")) {
  console.log("Preview only. Add --apply with DATABASE_URL set to update the definitions. Scores are preserved.");
} else {
  if (!process.env.DATABASE_URL) throw new Error("DATABASE_URL is required");
  const { eq, inArray, sql } = await import("drizzle-orm");
  const { awards } = await import("../src/schema");
  const { db, closeDatabase } = await import("../src/client");
  try {
    await db.transaction(async (tx) => {
      const existing = await tx.select({ id: awards.id }).from(awards).where(inArray(awards.id, ids));
      if (existing.length !== ids.length) throw new Error("An eating award is missing from the database");
      for (const row of rows) {
        await tx.update(awards).set({
          description: row.description,
          reader_type: row.reader.type,
          reader_path: row.reader.path,
          reader_patterns: null,
          updated_at: sql`EXTRACT(EPOCH FROM NOW())::INTEGER`,
        }).where(eq(awards.id, row.id));
      }
    });
    console.log("Updated nine eating readers. Restart Velocity to load them; existing scores remain until confirmed totals arrive.");
  } finally {
    await closeDatabase();
  }
}
