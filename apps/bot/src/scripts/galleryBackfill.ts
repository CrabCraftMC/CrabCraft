import {
  Client,
  Events,
  GatewayIntentBits,
  Partials,
} from "discord.js";
import { parseBackfillArguments } from "../utils/galleryBackfillArgs.js";
import logger from "../utils/logger.js";

function usage(): string {
  return [
    "Usage: bun run gallery:backfill -- [options]",
    "",
    "Options:",
    "  --dry-run          Inspect Discord without uploading or changing the database",
    "  --season <1-7>     Backfill one season; repeat to select more than one",
    "  --help             Show this help",
  ].join("\n");
}

async function main(): Promise<number> {
  const args = parseBackfillArguments(process.argv.slice(2));
  if (args.help) {
    console.info(usage());
    return 0;
  }

  const [
    { closeDatabase },
    { default: config },
    { reconcileGallery },
  ] = await Promise.all([
    import("@crabcraft/db/client"),
    import("../utils/config.js"),
    import("../utils/gallerySync.js"),
  ]);

  if (config.GALLERY_CONFIGURATION_ERRORS.length > 0) {
    throw new Error(
      `Invalid Gallery configuration: ${config.GALLERY_CONFIGURATION_ERRORS.join(" ")}`,
    );
  }
  if (config.GALLERY_CHANNELS.length === 0) {
    throw new Error("No gallery channels are configured in apps/bot/config.json.");
  }

  const configuredSeasons = new Set(
    config.GALLERY_CHANNELS.map((mapping) => mapping.seasonId),
  );
  const unmapped = [...args.seasonIds].filter(
    (seasonId) => !configuredSeasons.has(seasonId),
  );
  if (unmapped.length > 0) {
    throw new Error(`No gallery channel is configured for Season ${unmapped.join(", ")}.`);
  }

  const client = new Client({
    intents: [
      GatewayIntentBits.Guilds,
      GatewayIntentBits.GuildMessages,
      GatewayIntentBits.MessageContent,
    ],
    partials: [Partials.Message, Partials.Channel, Partials.User],
  });

  const ready = new Promise<void>((resolve) => {
    client.once(Events.ClientReady, () => resolve());
  });

  try {
    await client.login(config.DISCORD_BOT_TOKEN);
    await ready;
    if (!client.isReady()) throw new Error("Discord client did not become ready.");

    const selected = args.seasonIds.size > 0 ? args.seasonIds : undefined;
    const stats = await reconcileGallery(client, {
      dryRun: args.dryRun,
      // This standalone client has no Gateway event handlers to repair the
      // narrow active/archive transition race during its one-off inventory.
      deleteAbsentPosts: false,
      seasonIds: selected,
      reason: args.dryRun ? "historical backfill dry run" : "historical backfill",
    });
    return stats.failures > 0 ? 1 : 0;
  } finally {
    await client.destroy();
    await closeDatabase();
  }
}

if (import.meta.main) {
  main()
    .then((exitCode) => {
      process.exitCode = exitCode;
    })
    .catch((error) => {
      logger.error(`[gallery] Backfill failed: ${String(error)}`);
      console.error(usage());
      process.exitCode = 1;
    });
}
