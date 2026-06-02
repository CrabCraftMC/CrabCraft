import { MessageFlags, type StringSelectMenuInteraction } from "discord.js";
import Event from "../structures/Event.js";
import logger from "../utils/logger.js";
import { errorContainer } from "../utils/embeds.js";
import { getPlayerByMinecraftUuid } from "../utils/appDb.js";
import {
  SEASON_SELECT_PREFIX,
  buildPlayerInfoReply,
  fetchPlayerSeasons,
  type ResolvedTarget,
} from "../utils/playerInfoView.js";

export default class SelectMenuInteractionEvent extends Event {
  constructor() {
    super("SelectMenuInteraction", "interactionCreate", false);
  }

  async execute(interaction: StringSelectMenuInteraction) {
    if (!interaction.isStringSelectMenu()) return;
    if (!interaction.customId.startsWith(SEASON_SELECT_PREFIX)) return;

    const uuid = interaction.customId.slice(SEASON_SELECT_PREFIX.length);
    const seasonId = interaction.values[0];
    if (!seasonId) return;

    // Re-render in place; the card fetch + skin can take >3s, so ack first.
    await interaction.deferUpdate();

    try {
      const identity = await getPlayerByMinecraftUuid(uuid);
      const target: ResolvedTarget = {
        uuid,
        username: identity?.minecraft_username ?? null,
        discordUsername: identity?.discord_username ?? null,
      };
      const seasons = await fetchPlayerSeasons(uuid);
      const seasonName = seasons.find((s) => s.id === seasonId)?.name ?? null;

      const reply = await buildPlayerInfoReply(target, seasonId, seasonName, seasons);
      if ("error" in reply) {
        await interaction.followUp({
          components: [errorContainer(reply.error)],
          flags: MessageFlags.IsComponentsV2 | MessageFlags.Ephemeral,
        });
        return;
      }

      await interaction.editReply({
        components: reply.components,
        files: reply.files,
      });
    } catch (error) {
      logger.error("playerinfo season select failed", error);
      await interaction
        .followUp({
          components: [errorContainer("Could not switch seasons. Try again.")],
          flags: MessageFlags.IsComponentsV2 | MessageFlags.Ephemeral,
        })
        .catch(() => {});
    }
  }
}
