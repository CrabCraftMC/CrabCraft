import { MessageFlags, type ChatInputCommandInteraction } from "discord.js";
import { commands } from "../index.js";
import Event from "../structures/Event.js";
import logger from "../utils/logger.js";
import { errorContainer } from "../utils/embeds.js";

const cooldowns = new Map<string, Map<string, number>>();

export default class ChatInteractionEvent extends Event {
  constructor() {
    super("ChatInteraction", "interactionCreate", false);
  }

  async execute(interaction: ChatInputCommandInteraction) {
    if (!interaction.isChatInputCommand()) return;

    const command = commands.get(interaction.commandName);

    if (!command) {
      logger.error(
        `Unknown command ${interaction.commandName} executed by ${interaction.user.username}`,
      );
      return;
    }

    if (command.options?.guildOnly && !interaction.guild)
      return void (await interaction.reply({
        components: [
          errorContainer("**Error!** This command can only be used in a server"),
        ],
        flags: MessageFlags.IsComponentsV2,
      }));

    // Cooldown enforcement
    if (command.options?.cooldown) {
      if (!cooldowns.has(command.name)) {
        cooldowns.set(command.name, new Map());
      }

      const timestamps = cooldowns.get(command.name)!;
      const cooldownMs = command.options.cooldown * 1000;
      const now = Date.now();
      const userId = interaction.user.id;

      if (timestamps.has(userId)) {
        const expiresAt = timestamps.get(userId)! + cooldownMs;
        if (now < expiresAt) {
          const remaining = Math.ceil((expiresAt - now) / 1000);
          await interaction.reply({
            components: [
              errorContainer(
                `**Slow down!** Please wait ${remaining} second${remaining !== 1 ? "s" : ""} before using this command again.`,
              ),
            ],
            flags: MessageFlags.IsComponentsV2 | MessageFlags.Ephemeral,
          });
          return;
        }
      }

      timestamps.set(userId, now);
      setTimeout(() => timestamps.delete(userId), cooldownMs);
    }

    try {
      await command.execute(interaction);
    } catch (error) {
      logger.error(
        `Error executing command ${interaction.commandName} by ${interaction.user.username}`,
        error,
      );
    }
  }
}
