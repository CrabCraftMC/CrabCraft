import type { Message } from "discord.js";
import Event from "../structures/Event.js";
import { handleStaffChatMessage } from "../utils/staffChat.js";

export default class StaffChatMessageCreateEvent extends Event {
  constructor() {
    super("StaffChatMessageCreate", "messageCreate", false);
  }

  async execute(message: Message) {
    await handleStaffChatMessage(message);
  }
}
