import SlashCommand from "../../structures/SlashCommand.js";
import type {
  ChatInputCommandInteraction,
  RESTPostAPIApplicationCommandsJSONBody,
} from "discord.js";
import {
  buildTicketMemberCommand,
  runTicketMemberCommand,
} from "../../utils/ticketMembers.js";

export default class RemoveFromTicketCommand extends SlashCommand {
  constructor() {
    super("remove", "Remove a member from this ticket", {
      guildOnly: true,
      cooldown: 3,
    });
  }

  async execute(interaction: ChatInputCommandInteraction) {
    await runTicketMemberCommand(interaction, "remove");
  }

  async build(): Promise<RESTPostAPIApplicationCommandsJSONBody> {
    return buildTicketMemberCommand("remove");
  }
}
