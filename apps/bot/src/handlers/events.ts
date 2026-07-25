import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import type Event from "../structures/Event.js";
import logger from "../utils/logger.js";
import { client } from "../index.js";

const __dirname = path.dirname(fileURLToPath(import.meta.url));

export async function loadEvents(): Promise<void> {
  const eventsDir = path.join(__dirname, "../events");
  for (const eventFile of fs.readdirSync(eventsDir, { withFileTypes: true })) {
    if (
      !eventFile.isFile()
      || eventFile.name.endsWith(".d.ts")
      || !/\.[jt]s$/.test(eventFile.name)
    ) {
      continue;
    }

    const eventModule = await import(`../events/${eventFile.name}`);
    const event: Event = new eventModule.default();
    event.register(client);
    logger.info(`> Loaded event ${event.name}`);
  }
}
