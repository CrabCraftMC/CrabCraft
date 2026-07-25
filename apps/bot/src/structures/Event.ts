import type { Client } from "discord.js";

export default class Event {
  name: string;
  event: string;
  once?: boolean;

  constructor(name: string, event: string, once?: boolean) {
    this.name = name;
    this.event = event;
    this.once = once;
  }

  async execute(..._args: any[]) {
    throw new Error("Method not implemented.");
  }

  register(client: Client): void {
    if (this.once) client.once(this.event, this.execute.bind(this));
    else client.on(this.event, this.execute.bind(this));
  }
}
