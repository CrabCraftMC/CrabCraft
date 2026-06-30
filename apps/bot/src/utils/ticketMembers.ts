import {
  MessageFlags,
  SlashCommandBuilder,
  type ChatInputCommandInteraction,
  type RESTPostAPIApplicationCommandsJSONBody,
  type TextChannel,
} from "discord.js";

import logger from "./logger.js";
import * as appDb from "./appDb.js";
import { resolveUsername } from "./mojang.js";
import { errorContainer, primaryContainer } from "./embeds.js";

/** Build the /add or /remove command definition (shared option shape). */
export function buildTicketMemberCommand(
  name: "add" | "remove",
): RESTPostAPIApplicationCommandsJSONBody {
  const verb = name === "add" ? "Add" : "Remove";
  const preposition = name === "add" ? "to" : "from";
  return new SlashCommandBuilder()
    .setName(name)
    .setDescription(`${verb} a member ${preposition} this ticket`)
    .addUserOption((opt) =>
      opt
        .setName("user")
        .setDescription(`The Discord user to ${name}`)
        .setRequired(false),
    )
    .addStringOption((opt) =>
      opt
        .setName("username")
        .setDescription(
          `Their Minecraft username (if you don't know their Discord)`,
        )
        .setRequired(false),
    )
    .setDMPermission(false)
    .toJSON();
}

type Target = { id: string; label: string };

/** Resolve the target Discord user from the `user` or `username` option. */
async function resolveTarget(
  interaction: ChatInputCommandInteraction,
): Promise<Target | { error: string }> {
  const userOpt = interaction.options.getUser("user", false);
  if (userOpt) return { id: userOpt.id, label: `<@${userOpt.id}>` };

  const username = interaction.options.getString("username", false)?.trim();
  if (!username) {
    return { error: "Provide either a Discord user or a Minecraft username." };
  }

  // Prefer the linked account in our database (no Mojang call, case-insensitive).
  let discordId = await appDb.getDiscordIdByMinecraftUsername(username);
  let displayName = username;

  // Fall back to resolving via Mojang, then matching by UUID (handles renames).
  if (!discordId) {
    const resolved = await resolveUsername(username);
    if (!resolved) {
      return {
        error: `\`${username}\` is not a valid Minecraft Java username.`,
      };
    }
    displayName = resolved.name;
    discordId = await appDb.getDiscordIdByMinecraftUuid(resolved.uuid);
  }

  if (!discordId) {
    return {
      error: `\`${displayName}\` isn't linked to a Discord account, so I can't add them by username. Provide their Discord user instead.`,
    };
  }

  return { id: discordId, label: `<@${discordId}> (\`${displayName}\`)` };
}

/** Shared executor for the /add and /remove ticket-member commands. */
export async function runTicketMemberCommand(
  interaction: ChatInputCommandInteraction,
  action: "add" | "remove",
): Promise<void> {
  if (!interaction.guild || !interaction.channelId) {
    await interaction.reply({
      components: [errorContainer("**Error!** This command can only be used in a server.")],
      flags: MessageFlags.IsComponentsV2 | MessageFlags.Ephemeral,
    });
    return;
  }

  // Public reply so the add/remove is visible to everyone in the ticket. Anyone
  // who can use the command here already has access to this private channel.
  await interaction.deferReply();

  // Must be run inside a ticket channel.
  const ticket = await appDb.getTicketByChannelId(interaction.channelId);
  if (!ticket) {
    await reply(
      interaction,
      errorContainer("**Error!** This command can only be used inside a ticket channel."),
    );
    return;
  }

  const target = await resolveTarget(interaction);
  if ("error" in target) {
    await reply(interaction, errorContainer(`**Error!** ${target.error}`));
    return;
  }

  const channel = interaction.channel as TextChannel;

  if (action === "add") {
    // The user must be in the server for the overwrite to mean anything.
    const member = await interaction.guild.members
      .fetch(target.id)
      .catch(() => null);
    if (!member) {
      await reply(
        interaction,
        errorContainer(`**Error!** ${target.label} is not in this server.`),
      );
      return;
    }

    try {
      await channel.permissionOverwrites.edit(target.id, {
        ViewChannel: true,
        SendMessages: true,
        ReadMessageHistory: true,
      });
    } catch (e) {
      logger.error("Ticket: failed to add member:", e);
      await reply(interaction, errorContainer("**Error!** Failed to add them. Please try again."));
      return;
    }

    await reply(interaction, primaryContainer(`Added ${target.label} to this ticket.`));
    return;
  }

  // Remove
  if (target.id === interaction.user.id) {
    await reply(
      interaction,
      errorContainer("**Error!** You can't remove yourself from the ticket."),
    );
    return;
  }
  if (target.id === ticket.opener_discord_id) {
    await reply(
      interaction,
      errorContainer("**Error!** You can't remove the ticket opener."),
    );
    return;
  }
  if (!channel.permissionOverwrites.cache.has(target.id)) {
    await reply(
      interaction,
      errorContainer(`**Error!** ${target.label} hasn't been added to this ticket.`),
    );
    return;
  }

  try {
    await channel.permissionOverwrites.delete(target.id, "Removed from ticket");
  } catch (e) {
    logger.error("Ticket: failed to remove member:", e);
    await reply(interaction, errorContainer("**Error!** Failed to remove them. Please try again."));
    return;
  }

  await reply(interaction, primaryContainer(`Removed ${target.label} from this ticket.`));
}

async function reply(
  interaction: ChatInputCommandInteraction,
  component: ReturnType<typeof primaryContainer>,
): Promise<void> {
  await interaction.editReply({
    components: [component],
    flags: MessageFlags.IsComponentsV2,
  });
}
