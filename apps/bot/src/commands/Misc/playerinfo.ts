import SlashCommand from "../../structures/SlashCommand.js";
import { errorContainer } from "../../utils/embeds.js";
import { generatePlayerCard, type PlayerCardStats } from "../../utils/playerCard.js";
import {
  getPlayerByMinecraftUsername,
  getPlayerByMinecraftUuid,
  getPlayerPrimaryUuid,
  getCurrentSeason,
} from "../../utils/appDb.js";
import { resolveUsername, isValidUsername } from "../../utils/mojang.js";
import logger from "../../utils/logger.js";
import {
  AttachmentBuilder,
  ContainerBuilder,
  MediaGalleryBuilder,
  MediaGalleryItemBuilder,
  MessageFlags,
  SlashCommandBuilder,
  type ChatInputCommandInteraction,
  type RESTPostAPIApplicationCommandsJSONBody,
} from "discord.js";

const API = "https://api.crabcraft.net";
const UUID_RE = /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/;

interface CrownResponse {
  crown?: {
    rank: number;
    gold: number;
    silver: number;
    bronze: number;
    crown_score: number;
  } | null;
  username?: string | null;
}

interface StatsResponse {
  username?: string | null;
  stats?: PlayerCardStats | null;
}

interface FetchResult<T> {
  ok: boolean;
  status: number;
  data: T | null;
  /** true when the request never completed (network / timeout), vs a clean HTTP error. */
  threw: boolean;
}

async function fetchJson<T>(url: string): Promise<FetchResult<T>> {
  try {
    const ctrl = new AbortController();
    const t = setTimeout(() => ctrl.abort(), 5000);
    const res = await fetch(url, { signal: ctrl.signal }).finally(() => clearTimeout(t));
    if (!res.ok) return { ok: false, status: res.status, data: null, threw: false };
    return { ok: true, status: res.status, data: (await res.json()) as T, threw: false };
  } catch (err) {
    logger.warn(`playerinfo: fetch failed for ${url}: ${(err as Error).message}`);
    return { ok: false, status: 0, data: null, threw: true };
  }
}

interface Target {
  uuid: string;
  username: string | null;
  discordUsername: string | null;
}

export default class PlayerInfoCommand extends SlashCommand {
  constructor() {
    super("playerinfo", "Show a player's season profile card", { cooldown: 10 });
  }

  /**
   * Resolve the target player from the optional `username` arg (Minecraft
   * username or UUID), or the caller's linked account when omitted. Identity
   * (uuid/discord/role) comes from the bot's DB; the Mojang fallback lets the
   * command work for whitelisted players who aren't Discord-linked.
   */
  private async resolveTarget(
    arg: string | null,
    discordId: string,
  ): Promise<Target | { error: string }> {
    if (arg) {
      if (UUID_RE.test(arg)) {
        const uuid = arg.toLowerCase();
        const identity = await getPlayerByMinecraftUuid(uuid);
        return {
          uuid,
          username: identity?.minecraft_username ?? null,
          discordUsername: identity?.discord_username ?? null,
        };
      }
      if (!isValidUsername(arg)) return { error: "**Invalid username.** 3–16 letters, numbers, or underscores." };
      const identity = await getPlayerByMinecraftUsername(arg);
      if (identity) {
        return {
          uuid: identity.minecraft_uuid,
          username: identity.minecraft_username,
          discordUsername: identity.discord_username,
        };
      }
      // Not Discord-linked — fall back to Mojang so any whitelisted player works.
      const resolved = await resolveUsername(arg);
      if (!resolved) return { error: `**Player not found.** No data for \`${arg}\`.` };
      return { uuid: resolved.uuid, username: resolved.name, discordUsername: null };
    }

    const uuid = await getPlayerPrimaryUuid(discordId);
    if (!uuid) {
      return { error: "**You're not linked.** Pass a `username`, or link your Minecraft account first." };
    }
    const identity = await getPlayerByMinecraftUuid(uuid);
    return {
      uuid,
      username: identity?.minecraft_username ?? null,
      discordUsername: identity?.discord_username ?? null,
    };
  }

  async execute(interaction: ChatInputCommandInteraction) {
    await interaction.deferReply();

    const arg = interaction.options.getString("username", false)?.trim() || null;
    const target = await this.resolveTarget(arg, interaction.user.id);
    if ("error" in target) {
      await interaction.editReply({
        components: [errorContainer(target.error)],
        flags: MessageFlags.IsComponentsV2,
      });
      return;
    }

    const [stats, awards, season] = await Promise.all([
      fetchJson<StatsResponse>(`${API}/players/${target.uuid}/stats`),
      fetchJson<CrownResponse>(`${API}/players/${target.uuid}/awards`),
      getCurrentSeason().catch(() => null),
    ]);

    // Only hard-fail when the API is unreachable; a 404 is a valid "no data" state.
    if (stats.threw && awards.threw) {
      await interaction.editReply({
        components: [errorContainer("Could not reach the CrabCraft API. Try again in a bit.")],
        flags: MessageFlags.IsComponentsV2,
      });
      return;
    }

    const crown = awards.data?.crown ?? null;
    const displayName =
      target.username || stats.data?.username || awards.data?.username || target.uuid.slice(0, 8);

    let buffer: Buffer;
    try {
      buffer = await generatePlayerCard({
        uuid: target.uuid,
        username: displayName,
        discordUsername: target.discordUsername,
        rank: crown?.rank ?? 0,
        points: crown?.crown_score ?? 0,
        gold: crown?.gold ?? 0,
        silver: crown?.silver ?? 0,
        bronze: crown?.bronze ?? 0,
        season: season?.name ?? null,
        stats: stats.data?.stats ?? null,
      });
    } catch (err) {
      logger.error("playerinfo: render failed:", err);
      await interaction.editReply({
        components: [errorContainer("Could not render the player card.")],
        flags: MessageFlags.IsComponentsV2,
      });
      return;
    }

    const file = new AttachmentBuilder(buffer, { name: "playerinfo.png" });
    const container = new ContainerBuilder()
      .addTextDisplayComponents((td) => td.setContent(`## ${displayName}`))
      .addMediaGalleryComponents(
        new MediaGalleryBuilder().addItems(
          new MediaGalleryItemBuilder().setURL("attachment://playerinfo.png"),
        ),
      );

    await interaction.editReply({
      components: [container],
      files: [file],
      flags: MessageFlags.IsComponentsV2,
    });
  }

  async build(
    _command: SlashCommandBuilder,
  ): Promise<SlashCommandBuilder | RESTPostAPIApplicationCommandsJSONBody> {
    const builder = new SlashCommandBuilder()
      .setName(this.name)
      .setDescription(this.description)
      .addStringOption((opt) =>
        opt
          .setName("username")
          .setDescription("Minecraft username or UUID (defaults to your linked account)")
          .setRequired(false),
      )
      .setDMPermission(false);

    return builder.toJSON();
  }
}
