import { beforeEach, describe, expect, mock, test } from "bun:test";
import {
  ChannelType,
  ComponentType,
  RESTJSONErrorCodes,
} from "discord.js";

let ticket: {
  id: number;
  status: "open" | "closed";
  channel_id: string;
  opener_discord_id: string;
  opener_discord_username: string;
  category: "general" | "council" | "grief" | "appeal";
  closed_by_discord_id: string | null;
  closed_at: number | null;
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
const listOpenTicketsForUser = mock(async () => [ticket]);

mock.module("../src/utils/config.js", () => ({
  default: {
    MOD_ROLE_ID: "mod",
    COUNCIL_ROLE_ID: "council",
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
  listOpenTicketsForUser,
}));

const { default: ButtonInteractionEvent } = await import(
  "../src/events/ButtonInteraction.js"
);
const {
  buildChannelName,
  buildIntakeModal,
  buildTriggerButtons,
  buildTriggerEmbed,
  TICKET_CATEGORIES,
} = await import(
  "../src/utils/ticket.js"
);
const { getLiveOpenTicketsForCategory, openTicket } = await import(
  "../src/utils/ticketFlow.js"
);
const { cleanupExpiredTicket } = await import(
  "../src/utils/ticketCleanup.js"
);

function interaction(customId: string, components: Array<{ type: number }>) {
  const sentMessage = { pin: mock(async () => {}) };
  return {
    isButton: () => true,
    customId,
    channelId: "chan-2",
    user: { bot: false, id: "closer", tag: "closer" },
    member: { roles: { cache: { has: () => true } } },
    guild: {
      roles: { everyone: { id: "everyone" } },
      channels: { fetch: mock(async () => null) },
    },
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
      messages: {
        fetch: mock(async () => []),
        fetchPins: mock(async () => ({ items: [], hasMore: false })),
      },
      name: "steve-general-0002",
      setName: mock(async () => {}),
      permissionOverwrites: {
        cache: { values: () => [] },
        edit: mock(async () => {}),
      },
      send: mock(async () => sentMessage),
      delete: mock(async () => {}),
    },
    sentMessage,
  };
}

function buttonData(payload: { components?: unknown[] }): any[] {
  return (payload.components ?? []).flatMap((component: any) => {
    const row = component.toJSON?.() ?? component;
    return (row.components ?? [])
      .map((button: any) => {
        return button.toJSON?.() ?? button;
      })
      .filter((button: any) => button.custom_id ?? button.customId);
  });
}

function buttonIds(payload: { components?: unknown[] }): string[] {
  return buttonData(payload).map(
    (button) => button.custom_id ?? button.customId,
  );
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
    closed_at: null,
    delete_after: null,
    created_at: 1_700_000_000,
  };
  order = [];
  getTicketById.mockClear();
  closeTicket.mockClear();
  reopenTicket.mockClear();
  createTicket.mockClear();
  deleteTicketRow.mockClear();
  listOpenTicketsForUser.mockClear();
});

describe("ticket lifecycle controls", () => {
  test("close acknowledges first and sends a closed notice", async () => {
    const i = interaction("ticket_close:2", [
      { type: ComponentType.Container },
      { type: ComponentType.ActionRow },
    ]);

    await new ButtonInteractionEvent().execute(i as any);

    expect(order).toEqual(["defer", "db"]);
    expect(closeTicket).toHaveBeenCalledWith(2, "closer", expect.any(Number));
    expect(i.message.edit).not.toHaveBeenCalled();
    expect(i.channel.send).toHaveBeenCalledTimes(1);
    expect(buttonIds(i.channel.send.mock.calls[0]![0] as any)).toEqual([
      "ticket_reopen:2",
      "ticket_delete:2",
    ]);
  });

  test("reopen disables its closed notice controls", async () => {
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
      "ticket_reopen:2",
      "ticket_delete:2",
    ]);
    expect(
      buttonData(i.message.edit.mock.calls[0]![0] as any).map(
        (button) => button.disabled,
      ),
    ).toEqual([true, true]);
    expect(i.channel.send).toHaveBeenCalledTimes(1);
    expect(buttonIds(i.channel.send.mock.calls[0]![0] as any)).toEqual([]);
    expect(i.message.delete).not.toHaveBeenCalled();
  });

  test("DB close failure follows up after acknowledging", async () => {
    closeTicket.mockImplementationOnce(async () => {
      order.push("db");
      throw new Error("database unavailable");
    });
    const i = interaction("ticket_close:2", [
      { type: ComponentType.Container },
      { type: ComponentType.ActionRow },
    ]);

    await new ButtonInteractionEvent().execute(i as any);

    expect(order).toEqual(["defer", "db"]);
    expect(i.followUp).toHaveBeenCalledTimes(1);
    expect(i.reply).not.toHaveBeenCalled();
    expect(i.message.edit).not.toHaveBeenCalled();
  });

  test("close still posts its notice when an overwrite cannot be locked", async () => {
    const actions: string[] = [];
    const i = interaction("ticket_close:2", [
      { type: ComponentType.Container },
      { type: ComponentType.ActionRow },
    ]);
    i.channel.permissionOverwrites.cache.values = () => [{ id: "member" }];
    i.channel.send.mockImplementationOnce(async () => {
      actions.push("notice");
      return i.sentMessage;
    });
    i.channel.permissionOverwrites.edit.mockImplementationOnce(async () => {
      actions.push("lock");
      throw new Error("missing permission");
    });

    await new ButtonInteractionEvent().execute(i as any);

    expect(actions).toEqual(["notice", "lock"]);
    expect(i.channel.send).toHaveBeenCalledTimes(1);
    expect(reopenTicket).not.toHaveBeenCalled();
    expect(i.followUp).toHaveBeenCalledTimes(1);
  });

  test("a stale Close repairs missing Discord state", async () => {
    ticket.status = "closed";
    ticket.closed_by_discord_id = "original-closer";
    ticket.closed_at = 1_700_000_100;
    ticket.delete_after = 2_000_000_000;
    const i = interaction("ticket_close:2", [
      { type: ComponentType.Container },
      { type: ComponentType.ActionRow },
    ]);
    i.channel.name = "steve-general";

    await new ButtonInteractionEvent().execute(i as any);

    expect(closeTicket).not.toHaveBeenCalled();
    expect(i.channel.setName).toHaveBeenCalledWith("steve-general-0002");
    expect(i.channel.send).toHaveBeenCalledTimes(1);
    expect(buttonIds(i.channel.send.mock.calls[0]![0] as any)).toEqual([
      "ticket_reopen:2",
      "ticket_delete:2",
    ]);
    expect(i.followUp).toHaveBeenCalledTimes(1);
  });

  test("a stale Close does not duplicate active closed controls", async () => {
    ticket.status = "closed";
    ticket.closed_at = 1_700_000_100;
    const i = interaction("ticket_close:2", [
      { type: ComponentType.Container },
      { type: ComponentType.ActionRow },
    ]);
    i.channel.messages.fetchPins.mockResolvedValueOnce({
      items: [
        {
          message: {
            components: [
              {
                type: ComponentType.ActionRow,
                components: [
                  {
                    type: ComponentType.Button,
                    customId: "ticket_reopen:2",
                    disabled: false,
                  },
                  {
                    type: ComponentType.Button,
                    customId: "ticket_delete:2",
                    disabled: false,
                  },
                ],
              },
            ],
          },
        },
      ],
      hasMore: false,
    });

    await new ButtonInteractionEvent().execute(i as any);

    expect(i.channel.send).not.toHaveBeenCalled();
    expect(i.followUp).toHaveBeenCalledTimes(1);
  });

  test("a simultaneous close conflict does not duplicate controls", async () => {
    closeTicket.mockImplementationOnce(async () => {
      order.push("db");
      return null;
    });
    const i = interaction("ticket_close:2", [
      { type: ComponentType.Container },
      { type: ComponentType.ActionRow },
    ]);

    await new ButtonInteractionEvent().execute(i as any);

    expect(order).toEqual(["defer", "db"]);
    expect(i.channel.send).not.toHaveBeenCalled();
    expect(i.followUp).toHaveBeenCalledTimes(1);
  });

  test("close waits for an in-progress reopen before locking", async () => {
    ticket.status = "closed";
    const lifecycleOrder: string[] = [];
    let markReopenStarted!: () => void;
    let finishReopen!: () => void;
    const reopenStarted = new Promise<void>((resolve) => {
      markReopenStarted = resolve;
    });
    const holdReopen = new Promise<void>((resolve) => {
      finishReopen = resolve;
    });
    reopenTicket.mockImplementationOnce(async () => {
      lifecycleOrder.push("reopen-db");
      ticket.status = "open";
      markReopenStarted();
      await holdReopen;
      return ticket;
    });
    closeTicket.mockImplementationOnce(async () => {
      lifecycleOrder.push("close-db");
      ticket.status = "closed";
      return ticket;
    });

    const reopenInteraction = interaction("ticket_reopen:2", [
      { type: ComponentType.Container },
      { type: ComponentType.ActionRow },
    ]);
    reopenInteraction.channel.permissionOverwrites.cache.values = () => [
      { id: "opener" },
    ];
    reopenInteraction.channel.permissionOverwrites.edit.mockImplementation(
      async (_id: string, permissions: { SendMessages: boolean }) => {
        lifecycleOrder.push(permissions.SendMessages ? "unlock" : "lock");
      },
    );
    const closeInteraction = interaction("ticket_close:2", [
      { type: ComponentType.Container },
      { type: ComponentType.ActionRow },
    ]);
    closeInteraction.channel = reopenInteraction.channel;

    const reopening = new ButtonInteractionEvent().execute(
      reopenInteraction as any,
    );
    await reopenStarted;
    const closing = new ButtonInteractionEvent().execute(closeInteraction as any);
    await Promise.resolve();

    expect(closeTicket).not.toHaveBeenCalled();
    finishReopen();
    await Promise.all([reopening, closing]);

    expect(lifecycleOrder).toEqual([
      "unlock",
      "reopen-db",
      "close-db",
      "lock",
    ]);
    expect(ticket.status).toBe("closed");
  });

  test("a stale Reopen disables its notice and returns a visible result", async () => {
    const i = interaction("ticket_reopen:2", [
      { type: ComponentType.Container },
      { type: ComponentType.ActionRow },
    ]);

    await new ButtonInteractionEvent().execute(i as any);

    expect(reopenTicket).not.toHaveBeenCalled();
    expect(i.message.edit).toHaveBeenCalledTimes(1);
    expect(i.followUp).toHaveBeenCalledTimes(1);
  });

  test("failed permission restore leaves the ticket closed", async () => {
    ticket.status = "closed";
    const i = interaction("ticket_reopen:2", [
      { type: ComponentType.Container },
      { type: ComponentType.ActionRow },
    ]);
    i.channel.permissionOverwrites.edit.mockRejectedValueOnce(
      new Error("missing permission"),
    );

    await new ButtonInteractionEvent().execute(i as any);

    expect(reopenTicket).not.toHaveBeenCalled();
    expect(i.message.edit).not.toHaveBeenCalled();
    expect(i.followUp).toHaveBeenCalledTimes(1);
  });

  test("failed DB reopen relocks the channel and leaves the ticket closed", async () => {
    ticket.status = "closed";
    const permissionChanges: boolean[] = [];
    const i = interaction("ticket_reopen:2", [
      { type: ComponentType.Container },
      { type: ComponentType.ActionRow },
    ]);
    i.channel.permissionOverwrites.cache.values = () => [{ id: "opener" }];
    i.channel.permissionOverwrites.edit.mockImplementation(
      async (_id: string, permissions: { SendMessages: boolean }) => {
        permissionChanges.push(permissions.SendMessages);
      },
    );
    reopenTicket.mockRejectedValueOnce(new Error("database unavailable"));

    await new ButtonInteractionEvent().execute(i as any);

    expect(permissionChanges).toEqual([true, false]);
    expect(ticket.status).toBe("closed");
    expect(i.message.edit).not.toHaveBeenCalled();
    expect(i.followUp).toHaveBeenCalledTimes(1);
  });

  test("reopen removes its separate notice when disabling it fails", async () => {
    ticket.status = "closed";
    const i = interaction("ticket_reopen:2", [
      { type: ComponentType.Container },
      { type: ComponentType.ActionRow },
    ]);
    i.message.edit.mockRejectedValueOnce(new Error("edit failed"));

    await new ButtonInteractionEvent().execute(i as any);

    expect(i.message.delete).toHaveBeenCalledTimes(1);
    expect(i.followUp).not.toHaveBeenCalled();
  });

  test("failed closed controls leave the ticket open for retry", async () => {
    const i = interaction("ticket_close:2", [
      { type: ComponentType.Container },
      { type: ComponentType.ActionRow },
    ]);
    i.channel.send.mockRejectedValueOnce(new Error("send failed"));

    await new ButtonInteractionEvent().execute(i as any);

    expect(reopenTicket).toHaveBeenCalledWith(2);
    expect(i.followUp).toHaveBeenCalledTimes(1);
  });

  test("a stale Delete control cannot delete a reopened ticket", async () => {
    const i = interaction("ticket_delete:2", [
      { type: ComponentType.ActionRow },
    ]);

    await new ButtonInteractionEvent().execute(i as any);

    expect(deleteTicketRow).not.toHaveBeenCalled();
    expect(i.followUp).toHaveBeenCalledTimes(1);
  });

  test("Delete removes a matching orphaned channel left by the old bug", async () => {
    getTicketById.mockResolvedValueOnce(null);
    const i = interaction("ticket_delete:2", [
      { type: ComponentType.ActionRow },
    ]);

    await new ButtonInteractionEvent().execute(i as any);

    expect(i.channel.delete).toHaveBeenCalledTimes(1);
    expect(deleteTicketRow).not.toHaveBeenCalled();
    expect(i.followUp).not.toHaveBeenCalled();
  });

  test("a missing row cannot delete an unrelated channel", async () => {
    getTicketById.mockResolvedValueOnce(null);
    const i = interaction("ticket_delete:2", [
      { type: ComponentType.ActionRow },
    ]);
    i.channel.name = "general";

    await new ButtonInteractionEvent().execute(i as any);

    expect(i.channel.delete).not.toHaveBeenCalled();
    expect(i.followUp).toHaveBeenCalledTimes(1);
  });

  test("failed channel deletion keeps the ticket row for retry", async () => {
    ticket.status = "closed";
    const i = interaction("ticket_delete:2", [
      { type: ComponentType.ActionRow },
    ]);
    i.channel.delete.mockRejectedValueOnce(new Error("missing permission"));

    await new ButtonInteractionEvent().execute(i as any);

    expect(i.deferUpdate).toHaveBeenCalledTimes(1);
    expect(i.channel.delete).toHaveBeenCalledTimes(1);
    expect(deleteTicketRow).not.toHaveBeenCalled();
    expect(i.followUp).toHaveBeenCalledTimes(1);
  });

  test("Close exposes a working Delete that removes channel before row", async () => {
    const deletionOrder: string[] = [];
    closeTicket.mockImplementationOnce(async () => {
      ticket.status = "closed";
      ticket.closed_by_discord_id = "closer";
      ticket.delete_after = 2_000_000_000;
      return ticket;
    });
    const closeInteraction = interaction("ticket_close:2", [
      { type: ComponentType.Container },
      { type: ComponentType.ActionRow },
    ]);
    closeInteraction.channel.delete.mockImplementationOnce(async () => {
      deletionOrder.push("channel");
    });
    deleteTicketRow.mockImplementationOnce(async () => {
      deletionOrder.push("row");
    });

    await new ButtonInteractionEvent().execute(closeInteraction as any);
    const deleteId = buttonIds(
      closeInteraction.channel.send.mock.calls[0]![0] as any,
    ).find((id) => id.startsWith("ticket_delete:"));
    expect(deleteId).toBe("ticket_delete:2");

    const deleteInteraction = interaction(deleteId!, [
      { type: ComponentType.Container },
      { type: ComponentType.ActionRow },
    ]);
    deleteInteraction.channel = closeInteraction.channel;
    deleteInteraction.guild = closeInteraction.guild;

    await new ButtonInteractionEvent().execute(deleteInteraction as any);

    expect(deletionOrder).toEqual(["channel", "row"]);
    expect(closeInteraction.channel.delete).toHaveBeenCalledTimes(1);
    expect(deleteTicketRow).toHaveBeenCalledWith(2);
  });

  test("Delete waits for an in-progress Reopen and rechecks state", async () => {
    ticket.status = "closed";
    let markReopenStarted!: () => void;
    let finishReopen!: () => void;
    const reopenStarted = new Promise<void>((resolve) => {
      markReopenStarted = resolve;
    });
    const holdReopen = new Promise<void>((resolve) => {
      finishReopen = resolve;
    });
    reopenTicket.mockImplementationOnce(async () => {
      ticket.status = "open";
      markReopenStarted();
      await holdReopen;
      return ticket;
    });
    const reopenInteraction = interaction("ticket_reopen:2", [
      { type: ComponentType.Container },
      { type: ComponentType.ActionRow },
    ]);
    const deleteInteraction = interaction("ticket_delete:2", [
      { type: ComponentType.ActionRow },
    ]);

    const reopening = new ButtonInteractionEvent().execute(
      reopenInteraction as any,
    );
    await reopenStarted;
    const deleting = new ButtonInteractionEvent().execute(
      deleteInteraction as any,
    );
    await Promise.resolve();

    expect(deleteTicketRow).not.toHaveBeenCalled();
    finishReopen();
    await Promise.all([reopening, deleting]);

    expect(deleteTicketRow).not.toHaveBeenCalled();
    expect(deleteInteraction.followUp).toHaveBeenCalledTimes(1);
  });

  test("the attached Close survives a full reopen, reclose, and delete lifecycle", async () => {
    closeTicket.mockImplementationOnce(async () => {
      ticket.status = "closed";
      ticket.closed_by_discord_id = "closer";
      ticket.delete_after = 2_000_000_000;
      return ticket;
    });
    closeTicket.mockImplementationOnce(async () => {
      ticket.status = "closed";
      ticket.closed_by_discord_id = "closer";
      ticket.delete_after = 2_000_000_001;
      return ticket;
    });
    reopenTicket.mockImplementationOnce(async () => {
      ticket.status = "open";
      ticket.closed_by_discord_id = null;
      ticket.delete_after = null;
      return ticket;
    });

    const firstClose = interaction("ticket_close:2", [
      { type: ComponentType.Container },
      { type: ComponentType.ActionRow },
    ]);
    await new ButtonInteractionEvent().execute(firstClose as any);

    const reopenId = buttonIds(
      firstClose.channel.send.mock.calls[0]![0] as any,
    ).find((id) => id.startsWith("ticket_reopen:"));
    const reopen = interaction(reopenId!, [
      { type: ComponentType.Container },
      { type: ComponentType.ActionRow },
    ]);
    reopen.channel = firstClose.channel;
    reopen.guild = firstClose.guild;
    await new ButtonInteractionEvent().execute(reopen as any);

    const secondClose = interaction("ticket_close:2", [
      { type: ComponentType.Container },
      { type: ComponentType.ActionRow },
    ]);
    secondClose.channel = firstClose.channel;
    secondClose.guild = firstClose.guild;
    await new ButtonInteractionEvent().execute(secondClose as any);

    const deleteId = firstClose.channel.send.mock.calls
      .map(([payload]) => buttonIds(payload as any))
      .flat()
      .filter((id) => id.startsWith("ticket_delete:"))
      .at(-1);
    const deleteInteraction = interaction(deleteId!, [
      { type: ComponentType.Container },
      { type: ComponentType.ActionRow },
    ]);
    deleteInteraction.channel = firstClose.channel;
    deleteInteraction.guild = firstClose.guild;
    await new ButtonInteractionEvent().execute(deleteInteraction as any);

    expect(reopenId).toBe("ticket_reopen:2");
    expect(deleteId).toBe("ticket_delete:2");
    expect(closeTicket).toHaveBeenCalledTimes(2);
    expect(reopenTicket).toHaveBeenCalledTimes(1);
    expect(firstClose.channel.delete).toHaveBeenCalledTimes(1);
    expect(deleteTicketRow).toHaveBeenCalledWith(2);
  });
});

describe("ticket opening controls", () => {
  test("uses the requested labels for the ticket button row", () => {
    expect(
      buttonData({ components: [buildTriggerButtons()] }).map(
        (button) => button.label,
      ),
    ).toEqual([
      "General",
      "Council",
      "Report Griefing / Stealing",
      "Punishment Appeal",
    ]);
  });

  test("ticket creation copy omits dash punctuation and appeal guidance", () => {
    const copy = JSON.stringify([
      buildTriggerEmbed().toJSON(),
      buildIntakeModal(TICKET_CATEGORIES.appeal).toJSON(),
    ]);

    expect(copy).not.toContain("–");
    expect(copy).not.toContain("—");
    expect(copy).not.toContain("Be honest");
  });

  function openingInteraction() {
    const headerMessage = { pin: mock(async () => {}) };
    const ticketChannel = {
      id: "chan-2",
      setName: mock(async () => {}),
      setTopic: mock(async () => {}),
      send: mock(async () => headerMessage),
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
    return { interaction, ticketChannel, headerMessage };
  }

  test("creates unique channel names with Close on the pinned header", async () => {
    const { interaction, ticketChannel, headerMessage } = openingInteraction();

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
    expect(ticketChannel.send).toHaveBeenCalledTimes(1);
    expect(buttonIds(ticketChannel.send.mock.calls[0]![0] as any)).toEqual([
      "ticket_close:2",
    ]);
    expect(headerMessage.pin).toHaveBeenCalledTimes(1);
  });

  test("grants the council role access to council inquiries", async () => {
    const { interaction } = openingInteraction();

    await openTicket({
      interaction: interaction as any,
      meta: TICKET_CATEGORIES.council,
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

    const createOptions = interaction.guild.channels.create.mock.calls[0]![0];
    expect(
      createOptions.permissionOverwrites.map(({ id }: { id: string }) => id),
    ).toEqual([
      interaction.guild.roles.everyone,
      "opener",
      "mod",
      "council",
    ]);
  });

  test("removes a ticket when its attached opening message cannot be sent", async () => {
    const { interaction, ticketChannel } = openingInteraction();
    ticketChannel.send.mockRejectedValue(new Error("send failed"));

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

    expect(ticketChannel.send).toHaveBeenCalledTimes(2);
    expect(deleteTicketRow).toHaveBeenCalledWith(2);
    expect(ticketChannel.delete).toHaveBeenCalledWith(
      "Ticket opening message failed",
    );
    expect(interaction.editReply).toHaveBeenCalledTimes(1);
  });

  test("keeps the row when an incomplete ticket channel cannot be deleted", async () => {
    const { interaction, ticketChannel } = openingInteraction();
    ticketChannel.send.mockRejectedValue(new Error("send failed"));
    ticketChannel.delete.mockRejectedValueOnce(new Error("missing permission"));

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

    expect(ticketChannel.delete).toHaveBeenCalledTimes(1);
    expect(deleteTicketRow).not.toHaveBeenCalled();
    expect(interaction.editReply).toHaveBeenCalledTimes(1);
  });
});

describe("ticket row and channel consistency", () => {
  test("an inconclusive null channel lookup retains and counts the ticket", async () => {
    const client = { channels: { fetch: mock(async () => null) } };

    const live = await getLiveOpenTicketsForCategory(
      client as any,
      "opener",
      "general",
    );

    expect(live).toEqual([ticket]);
    expect(client.channels.fetch).toHaveBeenCalledWith("chan-2", {
      force: true,
    });
    expect(deleteTicketRow).not.toHaveBeenCalled();
  });

  test("transient channel lookup errors retain and count the ticket", async () => {
    const client = {
      channels: {
        fetch: mock(async () => {
          throw new Error("Discord unavailable");
        }),
      },
    };

    const live = await getLiveOpenTicketsForCategory(
      client as any,
      "opener",
      "general",
    );

    expect(live).toEqual([ticket]);
    expect(deleteTicketRow).not.toHaveBeenCalled();
  });

  test("confirmed missing channels prune orphaned ticket rows", async () => {
    const client = {
      channels: {
        fetch: mock(async () => {
          throw Object.assign(new Error("Unknown Channel"), {
            code: RESTJSONErrorCodes.UnknownChannel,
          });
        }),
      },
    };

    const live = await getLiveOpenTicketsForCategory(
      client as any,
      "opener",
      "general",
    );

    expect(live).toEqual([]);
    expect(deleteTicketRow).toHaveBeenCalledWith(2);
  });

  test("expired cleanup retains the row when channel deletion fails", async () => {
    ticket.status = "closed";
    ticket.delete_after = 100;
    const channel = {
      delete: mock(async () => {
        throw new Error("missing permission");
      }),
    };
    const client = { channels: { fetch: mock(async () => channel) } };

    await cleanupExpiredTicket(client as any, ticket.id, 100);

    expect(channel.delete).toHaveBeenCalledTimes(1);
    expect(deleteTicketRow).not.toHaveBeenCalled();
  });

  test("expired cleanup deletes the channel before its row", async () => {
    ticket.status = "closed";
    ticket.delete_after = 100;
    const deletionOrder: string[] = [];
    const channel = {
      delete: mock(async () => {
        deletionOrder.push("channel");
      }),
    };
    const client = { channels: { fetch: mock(async () => channel) } };
    deleteTicketRow.mockImplementationOnce(async () => {
      deletionOrder.push("row");
    });

    await cleanupExpiredTicket(client as any, ticket.id, 100);

    expect(deletionOrder).toEqual(["channel", "row"]);
    expect(deleteTicketRow).toHaveBeenCalledWith(ticket.id);
  });

  test("expired cleanup retains the row after a transient lookup error", async () => {
    ticket.status = "closed";
    ticket.delete_after = 100;
    const client = {
      channels: {
        fetch: mock(async () => {
          throw new Error("Discord unavailable");
        }),
      },
    };

    await cleanupExpiredTicket(client as any, ticket.id, 100);

    expect(deleteTicketRow).not.toHaveBeenCalled();
  });

  test("expired cleanup retains the row after an inconclusive null lookup", async () => {
    ticket.status = "closed";
    ticket.delete_after = 100;
    const client = { channels: { fetch: mock(async () => null) } };

    await cleanupExpiredTicket(client as any, ticket.id, 100);

    expect(client.channels.fetch).toHaveBeenCalledWith("chan-2", {
      force: true,
    });
    expect(deleteTicketRow).not.toHaveBeenCalled();
  });

  test("expired cleanup removes the row when deletion finds an absent channel", async () => {
    ticket.status = "closed";
    ticket.delete_after = 100;
    const channel = {
      delete: mock(async () => {
        throw Object.assign(new Error("Unknown Channel"), {
          code: RESTJSONErrorCodes.UnknownChannel,
        });
      }),
    };
    const client = { channels: { fetch: mock(async () => channel) } };

    await cleanupExpiredTicket(client as any, ticket.id, 100);

    expect(deleteTicketRow).toHaveBeenCalledWith(ticket.id);
  });

  test("expired cleanup removes the row for a confirmed missing channel", async () => {
    ticket.status = "closed";
    ticket.delete_after = 100;
    const client = {
      channels: {
        fetch: mock(async () => {
          throw Object.assign(new Error("Unknown Channel"), {
            code: RESTJSONErrorCodes.UnknownChannel,
          });
        }),
      },
    };

    await cleanupExpiredTicket(client as any, ticket.id, 100);

    expect(deleteTicketRow).toHaveBeenCalledWith(ticket.id);
  });

  test("expired cleanup skips a ticket that was reopened", async () => {
    ticket.status = "open";
    ticket.delete_after = null;
    const client = { channels: { fetch: mock(async () => null) } };

    await cleanupExpiredTicket(client as any, ticket.id, 100);

    expect(client.channels.fetch).not.toHaveBeenCalled();
    expect(deleteTicketRow).not.toHaveBeenCalled();
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
