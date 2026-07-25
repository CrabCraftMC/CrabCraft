import SlashCommand from "../../structures/SlashCommand.js";
import { errorContainer } from "../../utils/embeds.js";
import {
  buildPlayerInfoReply,
  fetchPlayerSeasons,
  pickInitialSeason,
  type ResolvedTarget,
} from "../../utils/playerInfoView.js";
import {
  getPlayerByMinecraftUsername,
  getPlayerByMinecraftUuid,
  getPlayerPrimaryUuid,
  getCurrentSeason,
  searchPlayersByUsername,
} from "../../utils/appDb.js";
import { resolveUsername, isValidUsername } from "../../utils/mojang.js";
import {
  MessageFlags,
  SlashCommandBuilder,
  type AutocompleteInteraction,
  type ChatInputCommandInteraction,
  type RESTPostAPIApplicationCommandsJSONBody,
} from "discord.js";

const UUID_RE = /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/;

export default class PlayerInfoCommand extends SlashCommand {
  constructor() {
    super("playerinfo", "Show a player's season profile card", { cooldown: 10 });
  }

  /**
   * Resolve the target player from the optional `username` arg (Minecraft
   * username or UUID), or the caller's linked account when omitted. Identity
   * (uuid/discord) comes from the bot's DB; the Mojang fallback lets the command
   * work for whitelisted players who aren't Discord-linked.
   */
  private async resolveTarget(
    arg: string | null,
    discordId: string,
  ): Promise<ResolvedTarget | { error: string }> {
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

    const [seasons, current] = await Promise.all([
      fetchPlayerSeasons(target.uuid),
      getCurrentSeason().catch(() => null),
    ]);
    const initial = pickInitialSeason(seasons, current);

    const reply = await buildPlayerInfoReply(target, initial.id, initial.name, seasons);
    if ("error" in reply) {
      await interaction.editReply({
        components: [errorContainer(reply.error)],
        flags: MessageFlags.IsComponentsV2,
      });
      return;
    }

    await interaction.editReply({
      components: reply.components,
      files: reply.files,
    });
  }

  async build(): Promise<RESTPostAPIApplicationCommandsJSONBody> {
    const builder = new SlashCommandBuilder()
      .setName(this.name)
      .setDescription(this.description)
      .addStringOption((opt) =>
        opt
          .setName("username")
          .setDescription("Minecraft username or UUID (defaults to your linked account)")
          .setRequired(false)
          .setAutocomplete(true),
      )
      .setDMPermission(false);

    return builder.toJSON();
  }

  async autocomplete(interaction: AutocompleteInteraction) {
    const focused = interaction.options.getFocused().trim();
    const matches = await searchPlayersByUsername(focused, 25);
    await interaction.respond(
      matches.map((m) => ({ name: m.minecraft_username, value: m.minecraft_username })),
    );
  }
}
