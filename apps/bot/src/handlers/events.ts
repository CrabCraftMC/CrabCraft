import type Event from "../structures/Event.js";
import logger from "../utils/logger.js";
import fs from "fs";
import path from "path";
import { client } from "../index.js";

import { fileURLToPath } from "url";
import { dirname } from "path";

const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);

export const loadEvents = (): Promise<void> => {
  return new Promise<void>((resolve, reject) => {
    (async () => {
      try {
        const events = fs.readdirSync(path.join(__dirname, "../events"));

        for (const eventFile of events) {
          if (
            !fs.lstatSync(path.join(__dirname, "../events", eventFile)).isFile()
          )
            continue;

          const eventModule = await import(`../events/${eventFile}`);
          const event: Event = new eventModule.default();

          event.register(client);
          logger.info(`> Loaded event ${event.name}`);
        }

        resolve();
      } catch (err) {
        reject(err);
      }
    })();
  });
};
