import {
  SlashCommandBuilder,
  type AutocompleteInteraction,
  type ChatInputCommandInteraction,
  type PermissionResolvable,
  type RESTPostAPIApplicationCommandsJSONBody,
} from "discord.js";

export type SlashCommandOptions = {
  requiredPermissions?: PermissionResolvable[];
  cooldown?: number;
  guildOnly?: boolean;
};

export default class SlashCommand {
  name: string;
  description: string;
  options: SlashCommandOptions | undefined;

  constructor(
    name: string,
    description: string,
    options?: SlashCommandOptions
  ) {
    this.name = name;
    this.description = description;
    this.options = options;
  }

  execute(_: ChatInputCommandInteraction) {
    throw new Error("Method not implemented.");
  }

  /**
   * Optional autocomplete handler. Commands with an autocomplete option override
   * this; the default is a no-op so the dispatcher can call it unconditionally.
   */
  async autocomplete(_: AutocompleteInteraction): Promise<void> {}

  async build(): Promise<RESTPostAPIApplicationCommandsJSONBody> {
    return new SlashCommandBuilder()
      .setName(this.name)
      .setDescription(this.description)
      .toJSON();
  }
}
