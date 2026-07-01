import {
  ActionRowBuilder,
  ButtonBuilder,
  ButtonStyle,
  ContainerBuilder,
  ModalBuilder,
  SectionBuilder,
  TextInputBuilder,
  TextInputStyle,
  ThumbnailBuilder,
  resolveColor,
  type ButtonInteraction,
  type GuildMember,
  type ModalSubmitInteraction,
  type TextChannel,
} from "discord.js";
import * as appDb from "./appDb.js";
import config from "./config.js";
import mysql from "./database.js";
import { errorContainer, logAccept, logAutoReject } from "./embeds.js";
import logger from "./logger.js";

export const FAST_TRACK_EDIT_USERNAME_BUTTON_ID =
  "fast-track-edit-username";

export interface FastTrackIdentity {
  minecraftUsername: string;
  minecraftUuid: string;
}

export type FastTrackResult =
  | { ok: true; seasonName: string }
  | { ok: false; seasonName: string; message: string };

type FastTrackInteraction = ButtonInteraction | ModalSubmitInteraction;

export function buildFastTrackUsernameModal(): ModalBuilder {
  const modal = new ModalBuilder()
    .setCustomId("fast-application")
    .setTitle("Fast Track Access");

  const minecraftUsername = new TextInputBuilder()
    .setCustomId("minecraft-username")
    .setLabel("Minecraft Username")
    .setPlaceholder("Steve")
    .setRequired(true)
    .setStyle(TextInputStyle.Short);

  modal.addComponents(
    new ActionRowBuilder<TextInputBuilder>().addComponents(minecraftUsername),
  );

  return modal;
}

export function buildFastTrackSuccessComponents(
  identity: FastTrackIdentity,
  seasonName: string,
) {
  const container = new ContainerBuilder()
    .setAccentColor(resolveColor("Green"))
    .addSectionComponents(
      new SectionBuilder()
        .addTextDisplayComponents((td) =>
          td.setContent(
            `## Fast Track Confirmed\n\`${identity.minecraftUsername}\` has been whitelisted for ${seasonName}.`,
          ),
        )
        .setThumbnailAccessory(
          new ThumbnailBuilder().setURL(
            `https://api.mineatar.io/body/full/${identity.minecraftUuid}?scale=12`,
          ),
        ),
    );

  const editButton = new ActionRowBuilder<ButtonBuilder>().addComponents(
    new ButtonBuilder()
      .setCustomId(FAST_TRACK_EDIT_USERNAME_BUTTON_ID)
      .setLabel("Not your username?")
      .setStyle(ButtonStyle.Secondary),
  );

  return [container, editButton];
}

export async function grantFastTrackAccess(
  interaction: FastTrackInteraction,
  identity: FastTrackIdentity,
): Promise<FastTrackResult> {
  const currentSeason = await appDb.getCurrentSeason().catch(() => null);
  const seasonName = currentSeason?.name ?? "the next season";
  const guild = interaction.guild;
  const user = interaction.user;
  const member = interaction.member as GuildMember | null;

  if (!guild || !member) {
    return {
      ok: false,
      seasonName,
      message: "**Error!** Fast track access can only be used in a server.",
    };
  }

  const logChannel = await guild.channels
    .fetch(config.LOG_CHANNEL_ID)
    .catch(() => null) as TextChannel | null;

  try {
    await mysql.query("DELETE FROM discordsrv_accounts WHERE discord = ?", [
      user.id,
    ]);
  } catch (e) {
    logger.error("Fast track: failed to clean up stale whitelist record:", e);
  }

  const rows = await mysql.query(
    "SELECT * FROM discordsrv_accounts WHERE uuid = ? OR discord = ?",
    [identity.minecraftUuid, user.id],
  );

  if (rows.length > 0) {
    if (logChannel) {
      await logChannel.send({
        content: logAutoReject(user.id, "Already a member"),
      }).catch(() => null);
    }

    return {
      ok: false,
      seasonName,
      message: "**Error!** That Minecraft account is already whitelisted.",
    };
  }

  try {
    await mysql.query(
      "INSERT INTO discordsrv_accounts (uuid, discord) VALUES (?, ?)",
      [identity.minecraftUuid, user.id],
    );
  } catch (e) {
    logger.error("Fast track: failed to insert whitelist record:", e);
    return {
      ok: false,
      seasonName,
      message:
        "**Error!** Failed to whitelist that Minecraft account. Please try again.",
    };
  }

  const memberRole = guild.roles.cache.get(config.MEMBER_ROLE_ID);
  if (memberRole) {
    await member.roles
      .add(memberRole)
      .catch((e: unknown) => logger.error("Fast track: failed to add member role:", e));
  }

  if (logChannel) {
    await logChannel.send({
      content: logAccept(
        user.id,
        identity.minecraftUsername,
        identity.minecraftUuid,
      ),
    }).catch(() => null);
  }

  try {
    await appDb.upsertUser({
      discordId: user.id,
      discordUsername: user.username,
      minecraftUsername: identity.minecraftUsername,
      minecraftUuid: identity.minecraftUuid,
    });
    await appDb.createApplication({
      discordId: user.id,
      discordUsername: user.username,
      minecraftUsername: identity.minecraftUsername,
      minecraftUuid: identity.minecraftUuid,
      ageMet: true,
      voiceChat: true,
      season: currentSeason?.id ?? null,
    });
    await appDb.acceptApplication(user.id, user.id);
  } catch (e) {
    logger.error("Fast track: failed to persist application record:", e);
  }

  return { ok: true, seasonName };
}

export function buildFastTrackErrorComponents(message: string) {
  return [errorContainer(message)];
}
