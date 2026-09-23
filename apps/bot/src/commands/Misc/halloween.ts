import { EmbedBuilder, SlashCommandBuilder, type ChatInputCommandInteraction, type RESTPostAPIApplicationCommandsJSONBody } from "discord.js";
import { getHalloweenProgress } from "@crabcraft/db/queries/halloween";
import { HALLOWEEN_MOBS, isHalloweenComplete } from "@crabcraft/db/halloweenLogic";
import SlashCommand from "../../structures/SlashCommand.js";
import config from "../../utils/config.js";

export default class HalloweenCommand extends SlashCommand {
  constructor() {
    super("halloween", "Show your progress on the three Halloween challenges", { cooldown: 10, guildOnly: true });
  }

  async execute(interaction: ChatInputCommandInteraction) {
    await interaction.deferReply({ ephemeral: true });
    if (!config.HALLOWEEN_ENABLED) {
      await interaction.editReply("The Halloween event is currently disabled.");
      return;
    }
    const accounts = await getHalloweenProgress(interaction.user.id, config.HALLOWEEN_EVENT_ID);
    const now = Math.floor(Date.now() / 1000);
    const timing = now < config.HALLOWEEN_STARTS_AT
      ? `Starts <t:${config.HALLOWEEN_STARTS_AT}:F>.`
      : now >= config.HALLOWEEN_ENDS_AT ? "The event has ended." : "The event is live!";
    const embed = new EmbedBuilder().setColor(0xe88c30).setTitle("🎃 Halloween challenges")
      .setDescription(`${timing} Ends <t:${config.HALLOWEEN_ENDS_AT}:F>.\n\n`
        + "**Pumpkin Head’s Hunt:** Kill a zombie, skeleton, spider, creeper and witch while wearing a carved pumpkin, without dying.\n"
        + "**Trick or Treat:** Place a pressure plate directly beside a bell and get a creeper to step on it and ring the bell.\n"
        + "**Back from the Dead:** Cure a zombie villager; wear a carved pumpkin when the cure finishes.\n\n"
        + "Complete all three on one Minecraft account to earn "
        + (config.HALLOWEEN_ROLE_ID ? `<@&${config.HALLOWEEN_ROLE_ID}>.` : "the Halloween Discord role.")
        + " Completed challenges stay completed; death only resets an unfinished hunt.");
    for (const account of accounts.slice(0, 25)) {
      const progress = account.progress;
      const remaining = HALLOWEEN_MOBS.filter((_, index) => !(progress.hunt_mask & (1 << index)));
      embed.addFields({ name: account.username, value: [
        `${progress.hunt_mask === 31 ? "✅" : "⬜"} Pumpkin Head’s Hunt (${5 - remaining.length}/5)${remaining.length ? ` — remaining: ${remaining.join(", ")}` : ""}`,
        `${progress.trick_or_treat ? "✅" : "⬜"} Trick or Treat`,
        `${progress.back_from_the_dead ? "✅" : "⬜"} Back from the Dead`,
        ...(isHalloweenComplete(progress) ? ["All three complete! 🎃"] : []),
      ].join("\n") });
    }
    if (!accounts.length) embed.setFooter({ text: "Link your Minecraft account to see your progress and receive the role." });
    await interaction.editReply({ embeds: [embed] });
  }

  async build(): Promise<RESTPostAPIApplicationCommandsJSONBody> {
    return new SlashCommandBuilder().setName(this.name).setDescription(this.description).setDMPermission(false).toJSON();
  }
}
