import Event from "../structures/Event.js";
import config from "../utils/config.js";
import logger from "../utils/logger.js";
import { primaryContainer } from "../utils/embeds.js";
import {
  ActionRowBuilder,
  ButtonBuilder,
  ButtonStyle,
  ChannelType,
  MessageFlags,
  PermissionFlagsBits,
  TextChannel,
  type GuildMember,
} from "discord.js";

export default class MemberJoinedEvent extends Event {
  constructor() {
    super("MemberJoined", "guildMemberAdd", false);
  }

  async execute(member: GuildMember) {
    if (member.user.bot) return;

    let channel = member.guild.channels.cache.find(
      (channel) => channel.name === `app-${member.user.username}`,
    ) as TextChannel | undefined;

    try {
      if (!channel) {
        channel = await member.guild.channels.create({
          name: `app-${member.user.username}`,
          type: ChannelType.GuildText,
          parent: config.APPLICATION_CATEGORY_ID,
          topic: member.user.id,
          permissionOverwrites: [
            {
              id: member.user.id,
              allow: [PermissionFlagsBits.ViewChannel],
            },
            {
              id: member.guild.roles.everyone,
              deny: [PermissionFlagsBits.ViewChannel],
            },
            {
              id: config.MOD_ROLE_ID,
              allow: [PermissionFlagsBits.ViewChannel],
            },
          ],
        });
      } else {
        await channel.permissionOverwrites.create(member.user.id, {
          ViewChannel: true,
        });
      }
    } catch (error) {
      logger.error("Failed to create/configure application channel:", error);
      return;
    }

    const welcomeContainer = primaryContainer(
      `## <:Crab:1397355651822256299> Welcome to CrabCraft ${member.user.displayName}!\nPlease click the button below this message to start your application.\n-# Any problems? Send a message in this channel.`,
    );

    const applyButton = new ActionRowBuilder<ButtonBuilder>().addComponents(
      new ButtonBuilder()
        .setCustomId("apply")
        .setLabel("Apply")
        .setStyle(ButtonStyle.Primary)
        .setEmoji("📝"),
    );

    try {
      await channel.send({ content: `<@!${member.user.id}>` });
      await channel.send({
        components: [welcomeContainer, applyButton],
        flags: MessageFlags.IsComponentsV2,
      });
    } catch (error) {
      logger.error("Failed to send welcome message:", error);
    }
  }
}
