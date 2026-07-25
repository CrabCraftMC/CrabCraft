import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { commands } from "../index.js";
import type SlashCommand from "../structures/SlashCommand.js";
import logger from "../utils/logger.js";

const __dirname = path.dirname(fileURLToPath(import.meta.url));

export async function loadCommands(): Promise<void> {
  const commandsDir = path.join(__dirname, "../commands");
  for (const category of fs.readdirSync(commandsDir, { withFileTypes: true })) {
    if (!category.isDirectory()) continue;

    const categoryDir = path.join(commandsDir, category.name);
    for (const file of fs.readdirSync(categoryDir, { withFileTypes: true })) {
      if (
        !file.isFile()
        || file.name.endsWith(".d.ts")
        || !/\.[jt]s$/.test(file.name)
      ) {
        continue;
      }

      const commandFile = await import(
        `../commands/${category.name}/${file.name}`
      );
      const command: SlashCommand = new commandFile.default();
      commands.set(command.name, command);
      logger.info(`> Loaded command ${command.name}`);
    }
  }
}
