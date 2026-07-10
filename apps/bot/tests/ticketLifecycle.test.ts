import { beforeEach, describe, expect, mock, test } from "bun:test";
import { ChannelType, ComponentType } from "discord.js";

let ticket: {
  id: number;
  status: "open" | "closed";
  channel_id: string;
  opener_discord_id: string;
  opener_discord_username: string;
  category: "general" | "grief" | "appeal";
  closed_by_discord_id: string | null;
  delete_after: number | null;
  created_at: number;
};
let order: string[] = [];

const getTicketById = mock(async () => ticket);
const closeTicket = mock(async () => {
  order.push("db");
  return ticket;
});
const reopenTicket = mock(async () => {
  order.push("db");
  return ticket;
});
const createTicket = mock(async () => ticket);
const deleteTicketRow = mock(async () => {});

mock.module("../src/utils/config.js", () => ({
  default: {
    MOD_ROLE_ID: "mod",
    TICKET_CATEGORY_ID: "ticket-category",
    DISCORD_DATABASE_URL: "unused",
    CRABCRAFT_API_URL: "https://example.test",
    TICKET_LOG_CHANNEL_ID: "log",
  },
}));
mock.module("../src/utils/logger.js", () => ({
  default: {
    error: mock(() => {}),
    info: mock(() => {}),
    warn: mock(() => {}),
  },
}));
mock.module("../src/utils/database.js", () => ({
  default: { query: mock(async () => []) },
}));
mock.module("../src/utils/appDb.js", () => ({
  getTicketById,
  closeTicket,
  reopenTicket,
  createTicket,
  deleteTicketRow,
}));

const { default: ButtonInteractionEvent } = await import(
  "../src/events/ButtonInteraction.js"
);
const { buildChannelName, TICKET_CATEGORIES } = await import(
  "../src/utils/ticket.js"
);
const { openTicket } = await import("../src/utils/ticketFlow.js");

function interaction(customId: string, components: Array<{ type: number }>) {
  const sentMessage = { pin: mock(async () => {}) };
  return {
    isButton: () => true,
    customId,
    channelId: "chan-2",
    user: { bot: false, id: "closer", tag: "closer" },
    member: { roles: { cache: { has: () => true } } },
    guild: { roles: { everyone: { id: "everyone" } } },
    deferUpdate: mock(async () => {
      order.push("defer");
    }),
    reply: mock(async () => {}),
    followUp: mock(async () => {}),
    editReply: mock(async () => {}),
    message: {
      components,
      edit: mock(async () => {}),
      pin: mock(async () => {}),
      delete: mock(async () => {}),
    },
    channel: {
      guild: { roles: { everyone: { id: "everyone" } } },
      name: "steve-general-0002",
      setName: mock(async () => {}),
      permissionOverwrites: {
        cache: { values: () => [] },
        edit: mock(async () => {}),
      },
      send: mock(async () => sentMessage),
    },
    sentMessage,
  };
}

function buttonIds(payload: { components?: unknown[] }): string[] {
  return (payload.components ?? []).flatMap((component: any) => {
    const row = component.toJSON?.() ?? component;
    return (row.components ?? [])
      .map((button: any) => {
        const data = button.toJSON?.() ?? button;
        return data.custom_id ?? data.customId;
      })
      .filter(Boolean);
  });
}

beforeEach(() => {
  ticket = {
    id: 2,
    status: "open",
    channel_id: "chan-2",
    opener_discord_id: "opener",
    opener_discord_username: "Steve",
    category: "general",
    closed_by_discord_id: null,
    delete_after: null,
    created_at: 1_700_000_000,
  };
  order = [];
  getTicketById.mockClear();
  closeTicket.mockClear();
  reopenTicket.mockClear();
  createTicket.mockClear();
  deleteTicketRow.mockClear();
});

describe("ticket lifecycle controls", () => {
  test("close acknowledges first and replaces standalone controls", async () => {
    const i = interaction("ticket_close:2", [
      { type: ComponentType.ActionRow },
    ]);

    await new ButtonInteractionEvent().execute(i as any);

    expect(order).toEqual(["defer", "db"]);
    expect(closeTicket).toHaveBeenCalledWith(2, "closer", expect.any(Number));
    expect(i.message.edit).toHaveBeenCalledTimes(1);
    expect(buttonIds(i.message.edit.mock.calls[0]![0] as any)).toEqual([
      "ticket_reopen:2",
      "ticket_delete:2",
    ]);
    expect(i.channel.send).not.toHaveBeenCalled();
  });

  test("reopen restores the standalone Close control", async () => {
    ticket.status = "closed";
    const i = interaction("ticket_reopen:2", [
      { type: ComponentType.Container },
      { type: ComponentType.ActionRow },
    ]);

    await new ButtonInteractionEvent().execute(i as any);

    expect(order).toEqual(["defer", "db"]);
    expect(reopenTicket).toHaveBeenCalledWith(2);
    expect(i.message.edit).toHaveBeenCalledTimes(1);
    expect(buttonIds(i.message.edit.mock.calls[0]![0] as any)).toEqual([
      "ticket_close:2",
    ]);
  });

  test("legacy header gets a separate pinned closed notice", async () => {
    const i = interaction("ticket_close:2", [
      { type: ComponentType.Container },
      { type: ComponentType.ActionRow },
    ]);

    await new ButtonInteractionEvent().execute(i as any);

    expect(i.message.edit).not.toHaveBeenCalled();
    expect(i.channel.send).toHaveBeenCalledTimes(1);
    expect(buttonIds(i.channel.send.mock.calls[0]![0] as any)).toEqual([
      "ticket_reopen:2",
      "ticket_delete:2",
    ]);
    expect(i.sentMessage.pin).toHaveBeenCalledTimes(1);
  });

  test("DB close failure follows up after acknowledging", async () => {
    closeTicket.mockImplementationOnce(async () => {
      order.push("db");
      throw new Error("database unavailable");
    });
    const i = interaction("ticket_close:2", [
      { type: ComponentType.ActionRow },
    ]);

    await new ButtonInteractionEvent().execute(i as any);

    expect(order).toEqual(["defer", "db"]);
    expect(i.followUp).toHaveBeenCalledTimes(1);
    expect(i.reply).not.toHaveBeenCalled();
    expect(i.message.edit).not.toHaveBeenCalled();
  });

  test("a stale legacy Close is harmless and normalizes the channel name", async () => {
    ticket.status = "closed";
    ticket.closed_by_discord_id = "original-closer";
    ticket.delete_after = 2_000_000_000;
    const i = interaction("ticket_close:2", [
      { type: ComponentType.Container },
      { type: ComponentType.ActionRow },
    ]);
    i.channel.name = "steve-general";

    await new ButtonInteractionEvent().execute(i as any);

    expect(closeTicket).not.toHaveBeenCalled();
    expect(i.channel.setName).toHaveBeenCalledWith("steve-general-0002");
    expect(i.channel.send).not.toHaveBeenCalled();
    expect(i.followUp).not.toHaveBeenCalled();
  });

  test("failed closed controls leave the ticket open for retry", async () => {
    const i = interaction("ticket_close:2", [{ type: ComponentType.ActionRow }]);
    i.message.edit.mockRejectedValueOnce(new Error("edit failed"));
    i.channel.send.mockRejectedValueOnce(new Error("send failed"));

    await new ButtonInteractionEvent().execute(i as any);

    expect(reopenTicket).toHaveBeenCalledWith(2);
    expect(i.followUp).toHaveBeenCalledTimes(1);
  });

  test("reopen replaces a stale control when editing it fails", async () => {
    ticket.status = "closed";
    const i = interaction("ticket_reopen:2", [
      { type: ComponentType.Container },
      { type: ComponentType.ActionRow },
    ]);
    i.message.edit.mockRejectedValueOnce(new Error("edit failed"));

    await new ButtonInteractionEvent().execute(i as any);

    expect(buttonIds(i.channel.send.mock.calls[0]![0] as any)).toEqual([
      "ticket_close:2",
    ]);
    expect(i.sentMessage.pin).toHaveBeenCalledTimes(1);
    expect(i.message.delete).toHaveBeenCalledTimes(1);
  });

  test("failed Close controls leave a reopened ticket closed for retry", async () => {
    ticket.status = "closed";
    ticket.closed_by_discord_id = "original-closer";
    ticket.delete_after = 2_000_000_000;
    const i = interaction("ticket_reopen:2", [
      { type: ComponentType.Container },
      { type: ComponentType.ActionRow },
    ]);
    i.message.edit.mockRejectedValueOnce(new Error("edit failed"));
    i.channel.send.mockRejectedValueOnce(new Error("send failed"));

    await new ButtonInteractionEvent().execute(i as any);

    expect(closeTicket).toHaveBeenCalledWith(
      2,
      "original-closer",
      2_000_000_000,
    );
    expect(i.followUp).toHaveBeenCalledTimes(1);
  });

  test("a stale Delete control cannot delete a reopened ticket", async () => {
    const i = interaction("ticket_delete:2", [
      { type: ComponentType.ActionRow },
    ]);

    await new ButtonInteractionEvent().execute(i as any);

    expect(deleteTicketRow).not.toHaveBeenCalled();
    expect(i.reply).toHaveBeenCalledTimes(1);
  });
});

describe("ticket opening controls", () => {
  function openingInteraction() {
    const headerMessage = { pin: mock(async () => {}) };
    const controlsMessage = { pin: mock(async () => {}) };
    const ticketChannel = {
      id: "chan-2",
      setName: mock(async () => {}),
      setTopic: mock(async () => {}),
      send: mock()
        .mockResolvedValueOnce(headerMessage)
        .mockResolvedValueOnce(controlsMessage),
      delete: mock(async () => {}),
    };
    const interaction = {
      user: { id: "opener", username: "Steve" },
      guildId: "guild",
      guild: {
        roles: { everyone: { id: "everyone" } },
        channels: {
          fetch: mock(async () => ({
            id: "ticket-category",
            type: ChannelType.GuildCategory,
          })),
          create: mock(async () => ticketChannel),
        },
      },
      editReply: mock(async () => {}),
    };
    return { interaction, ticketChannel, headerMessage, controlsMessage };
  }

  test("creates unique channel names and pinned standalone controls", async () => {
    const { interaction, ticketChannel, headerMessage, controlsMessage } =
      openingInteraction();

    await openTicket({
      interaction: interaction as any,
      meta: TICKET_CATEGORIES.general,
      player: {
        discordId: "opener",
        discordTag: "Steve",
        minecraftUsername: null,
        minecraftUuid: null,
        isWhitelisted: false,
        skinUrl: null,
      },
      intake: {},
    });

    expect(ticketChannel.setName).toHaveBeenCalledWith("steve-general-0002");
    expect(buttonIds(ticketChannel.send.mock.calls[0]![0] as any)).toEqual([]);
    expect(buttonIds(ticketChannel.send.mock.calls[1]![0] as any)).toEqual([
      "ticket_close:2",
    ]);
    expect(headerMessage.pin).toHaveBeenCalledTimes(1);
    expect(controlsMessage.pin).toHaveBeenCalledTimes(1);
  });

  test("removes an incomplete ticket when controls cannot be sent", async () => {
    const { interaction, ticketChannel } = openingInteraction();
    ticketChannel.send.mockReset();
    ticketChannel.send
      .mockResolvedValueOnce({ pin: mock(async () => {}) })
      .mockRejectedValueOnce(new Error("send failed"));

    await openTicket({
      interaction: interaction as any,
      meta: TICKET_CATEGORIES.general,
      player: {
        discordId: "opener",
        discordTag: "Steve",
        minecraftUsername: null,
        minecraftUuid: null,
        isWhitelisted: false,
        skinUrl: null,
      },
      intake: {},
    });

    expect(deleteTicketRow).toHaveBeenCalledWith(2);
    expect(ticketChannel.delete).toHaveBeenCalledWith("Ticket controls failed");
    expect(interaction.editReply).toHaveBeenCalledTimes(1);
  });
});

test("ticket channel names include their padded ticket id", () => {
  expect(buildChannelName("Steve", TICKET_CATEGORIES.grief, 41)).toBe(
    "steve-grief-0041",
  );
  expect(buildChannelName("Steve", TICKET_CATEGORIES.grief, 42)).toBe(
    "steve-grief-0042",
  );
});
