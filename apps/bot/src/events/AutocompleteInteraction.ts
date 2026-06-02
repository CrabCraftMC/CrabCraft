import type { AutocompleteInteraction } from "discord.js";
import { commands } from "../index.js";
import Event from "../structures/Event.js";
import logger from "../utils/logger.js";

export default class AutocompleteInteractionEvent extends Event {
  constructor() {
    super("AutocompleteInteraction", "interactionCreate", false);
  }

  async execute(interaction: AutocompleteInteraction) {
    if (!interaction.isAutocomplete()) return;

    const command = commands.get(interaction.commandName);
    if (!command) return;

    try {
      await command.autocomplete(interaction);
    } catch (error) {
      logger.error(
        `Error in autocomplete for ${interaction.commandName}`,
        error,
      );
      // Best-effort: respond with nothing so the client doesn't hang.
      if (!interaction.responded) await interaction.respond([]).catch(() => {});
    }
  }
}
