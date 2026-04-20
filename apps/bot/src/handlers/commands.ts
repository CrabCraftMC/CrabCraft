import fs from "fs";
import path from "path";
import { commands } from "../index.js";
import type SlashCommand from "../structures/SlashCommand.js";
import logger from "../utils/logger.js";

import { fileURLToPath } from "url";
import { dirname } from "path";

const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);

export const loadCommands = (): Promise<void> => {
  return new Promise<void>((resolve, reject) => {
    (async () => {
      try {
        const categories = fs.readdirSync(path.join(__dirname, "../commands"));

        for (const category of categories) {
          if (
            !fs
              .lstatSync(path.join(__dirname, "../commands", category))
              .isDirectory()
          )
            continue;

          const files = fs.readdirSync(
            path.join(__dirname, "../commands", category)
          );

          for (const file of files) {
            const commandFile = await import(`../commands/${category}/${file}`);
            const command: SlashCommand = new commandFile.default();
            commands.set(command.name, command);
            logger.info(`> Loaded command ${command.name}`);
          }
        }

        resolve();
      } catch (err) {
        reject(err);
      }
    })();
  });
};
