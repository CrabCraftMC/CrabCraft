import SlashCommand from "../../structures/SlashCommand.js";
import type {
  ChatInputCommandInteraction,
  RESTPostAPIApplicationCommandsJSONBody,
  SlashCommandBuilder,
} from "discord.js";
import {
  buildTicketMemberCommand,
  runTicketMemberCommand,
} from "../../utils/ticketMembers.js";

export default class AddToTicketCommand extends SlashCommand {
  constructor() {
    super("add", "Add a member to this ticket", {
      guildOnly: true,
      cooldown: 3,
    });
  }

  async execute(interaction: ChatInputCommandInteraction) {
    await runTicketMemberCommand(interaction, "add");
  }

  async build(
    _command: SlashCommandBuilder,
  ): Promise<SlashCommandBuilder | RESTPostAPIApplicationCommandsJSONBody> {
    return buildTicketMemberCommand("add");
  }
}
