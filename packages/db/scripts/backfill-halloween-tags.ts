const args = Bun.argv.slice(2);
const eventIndex = args.indexOf("--event");
const apply = args.includes("--apply");
if (eventIndex < 0 || args[eventIndex + 1] !== "halloween-2026"
  || args.includes("--dry-run") === apply
  || args.some((arg, index) => index !== eventIndex + 1 && !["--event", "--apply", "--dry-run"].includes(arg))
  || args.length !== 3) {
  throw new Error("Usage: bun packages/db/scripts/backfill-halloween-tags.ts --event halloween-2026 (--dry-run | --apply)");
}
if (!process.env.DATABASE_URL) throw new Error("DATABASE_URL is required");
const { db, closeDatabase } = await import("../src/client");
const { backfillHalloweenTags } = await import("../src/queries/tags");
try {
  const counts = await backfillHalloweenTags(db, "halloween-2026", apply);
  console.log(`${apply ? "Applied" : "Dry run"}: eligible=${counts.eligible}, new=${counts.new}, already-awarded=${counts.alreadyAwarded}, already-owned=${counts.alreadyOwned}`);
  if (!apply) console.log("Preview only. Use --apply to persist awards and automatic-award receipts.");
  else console.log("Online tag caches refresh within 30 seconds; selections are unchanged.");
} finally {
  await closeDatabase();
}
export {};
