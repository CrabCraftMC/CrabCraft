import { beforeEach, describe, expect, mock, test } from "bun:test";
import {
  ChannelType,
  Collection,
  ComponentType,
  GuildChannel,
  MessageFlags,
  OverwriteType,
  PermissionFlagsBits,
  PermissionOverwrites,
  PermissionsBitField,
  RESTJSONErrorCodes,
  type OverwriteResolvable,
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
const { default: NewTicketCommand } = await import(
  "../src/commands/Misc/newTicket.js"
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

const ticketAccess = [
  PermissionFlagsBits.ViewChannel,
  PermissionFlagsBits.SendMessages,
  PermissionFlagsBits.ReadMessageHistory,
];

function interaction(customId: string, components: Array<{ type: number }> = []) {
  const sentMessage = { pin: mock(async () => {}) };
  const client = { user: { id: "bot" } };
  const guild = {
    id: "everyone",
    client,
    roles: { everyone: { id: "everyone" } },
    members: { me: { id: "bot" } },
    channels: { fetch: mock(async () => null) },
  };
  const cache = new Collection<string, PermissionOverwrites>();
  const replaceOverwrites = (overwrites: OverwriteResolvable[]) => {
    cache.clear();
    for (const overwrite of overwrites) {
      const resolved = PermissionOverwrites.resolve(overwrite);
      cache.set(resolved.id, new PermissionOverwrites(client as any, resolved, {} as any));
    }
  };
  replaceOverwrites([
    { id: "everyone", type: OverwriteType.Role, deny: [PermissionFlagsBits.ViewChannel] },
    { id: "opener", type: OverwriteType.Member, allow: ticketAccess },
    { id: "mod", type: OverwriteType.Role, allow: ticketAccess },
  ]);
  return {
    isButton: () => true,
    customId,
    channelId: "chan-2",
    user: { bot: false, id: "closer", tag: "closer" },
    member: { roles: { cache: { has: () => true } } },
    guild,
    deferUpdate: mock(async () => {
      order.push("defer");
    }),
    deferReply: mock(async (_options: unknown) => {
      order.push("defer");
    }),
    reply: mock(async () => {}),
    followUp: mock(async () => {}),
    editReply: mock(async (_options: unknown) => {}),
    message: {
      components,
      edit: mock(async () => {}),
      pin: mock(async () => {}),
      delete: mock(async () => {}),
    },
    channel: {
      client,
      guild,
      messages: {
        fetch: mock(async () => []),
        fetchPins: mock(async () => ({ items: [], hasMore: false })),
      },
      name: "steve-general-0002",
      setName: mock(async () => {}),
      permissionOverwrites: {
        cache,
        set: mock(async (overwrites: OverwriteResolvable[]) => {
          replaceOverwrites(overwrites);
        }),
      },
      send: mock(async () => sentMessage),
      delete: mock(async () => {}),
    },
    sentMessage,
  };
}

function permissionsFor(
  channel: ReturnType<typeof interaction>["channel"],
  memberId: string,
  roleIds: string[] = [],
) {
  const roles = new Collection(
    ["everyone", ...roleIds].map((id) => [id, { permissions: new PermissionsBitField(ticketAccess) }]),
  );
  return GuildChannel.prototype.memberPermissions.call(
    { ...channel, overwritesFor: GuildChannel.prototype.overwritesFor },
    { id: memberId, roles: { cache: roles } },
    false,
  );
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
  test("an invited participant cannot close the opener's ticket", async () => {
    const i = interaction("ticket_close:2");
    i.member.roles.cache.has = () => false;

    await new ButtonInteractionEvent().execute(i as any);

    expect(closeTicket).not.toHaveBeenCalled();
    expect(i.followUp).toHaveBeenCalledTimes(1);
    expect(JSON.stringify(i.followUp.mock.calls[0]?.[0])).toContain(
      "Only the ticket opener or staff",
    );
  });

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

  test.each(["general", "council"] as const)("closed %s tickets are visible only to moderators and the bot", async (category) => {
    ticket.category = category;
    const i = interaction("ticket_close:2");
    await i.channel.permissionOverwrites.set([
      ...i.channel.permissionOverwrites.cache.values(),
      { id: "council", type: OverwriteType.Role, allow: ticketAccess },
      { id: "invited", type: OverwriteType.Member, allow: ticketAccess },
      { id: "other-role", type: OverwriteType.Role, allow: ticketAccess },
      {
        id: "bot",
        type: OverwriteType.Member,
        allow: [PermissionFlagsBits.ManageChannels],
        deny: [PermissionFlagsBits.AttachFiles, PermissionFlagsBits.SendMessages],
      },
    ]);
    i.channel.permissionOverwrites.set.mockClear();

    await new ButtonInteractionEvent().execute(i as any);

    expect(i.channel.permissionOverwrites.set).toHaveBeenCalledTimes(1);
    for (const [memberId, roles] of [
      ["opener", []],
      ["invited", []],
      ["councillor", ["council"]],
      ["other-member", ["other-role"]],
      ["ordinary-member", []],
    ] as Array<[string, string[]]>) {
      expect(permissionsFor(i.channel, memberId, roles).has(PermissionFlagsBits.ViewChannel)).toBe(false);
    }
    const closedAccess = [PermissionFlagsBits.ViewChannel, PermissionFlagsBits.ReadMessageHistory];
    expect(permissionsFor(i.channel, "moderator", ["mod"]).has(closedAccess)).toBe(true);
    expect(permissionsFor(i.channel, "opener", ["mod"]).has(closedAccess)).toBe(true);
    expect(permissionsFor(i.channel, "bot").has(ticketAccess)).toBe(true);
    expect(permissionsFor(i.channel, "bot").has(PermissionFlagsBits.ManageChannels)).toBe(true);
    expect(permissionsFor(i.channel, "bot").has(PermissionFlagsBits.AttachFiles)).toBe(false);
  });

  test.each(["general", "council"] as const)("reopening a %s ticket restores its normal audience", async (category) => {
    ticket.category = category;
    const close = interaction("ticket_close:2");
    await new ButtonInteractionEvent().execute(close as any);
    ticket.status = "closed";
    const reopen = interaction("ticket_reopen:2");
    reopen.channel = close.channel;

    await new ButtonInteractionEvent().execute(reopen as any);

    expect(permissionsFor(reopen.channel, "opener").has(ticketAccess)).toBe(true);
    expect(permissionsFor(reopen.channel, "moderator", ["mod"]).has(ticketAccess)).toBe(true);
    expect(permissionsFor(reopen.channel, "councillor", ["council"]).has(PermissionFlagsBits.ViewChannel)).toBe(category === "council");
    expect(permissionsFor(reopen.channel, "ordinary-member").has(PermissionFlagsBits.ViewChannel)).toBe(false);
  });

  test("Close finishes restricting access before exposing an immediately usable Delete", async () => {
    closeTicket.mockImplementationOnce(async () => {
      ticket.status = "closed";
      return ticket;
    });
    const close = interaction("ticket_close:2");
    const { promise: started, resolve: markStarted } = Promise.withResolvers<void>();
    const { promise: hold, resolve: finish } = Promise.withResolvers<void>();
    const applyPermissions = close.channel.permissionOverwrites.set.getMockImplementation()!;
    close.channel.permissionOverwrites.set.mockImplementationOnce(async (overwrites) => {
      markStarted();
      await hold;
      await applyPermissions(overwrites);
    });

    const closing = new ButtonInteractionEvent().execute(close as any);
    try {
      await Promise.race([
        started,
        closing.then(() => { throw new Error("Close finished without applying permissions"); }),
      ]);
      expect(close.channel.send).not.toHaveBeenCalled();
    } finally {
      finish();
      await closing;
    }

    const deleteId = buttonIds(close.channel.send.mock.calls[0]![0] as any)
      .find((id) => id.startsWith("ticket_delete:"));
    expect(deleteId).toBe("ticket_delete:2");
    const deleting = interaction(deleteId!);
    deleting.channel = close.channel;
    await new ButtonInteractionEvent().execute(deleting as any);
    expect(close.channel.delete).toHaveBeenCalledTimes(1);
    expect(deleteTicketRow).toHaveBeenCalledWith(2);
  });

  test("Delete shows progress while saving the transcript, then removes the channel", async () => {
    ticket.status = "closed";
    const i = interaction("ticket_delete:2");
    const { promise: started, resolve: markStarted } = Promise.withResolvers<void>();
    const { promise: hold, resolve: finish } = Promise.withResolvers<void>();
    i.channel.messages.fetch.mockResolvedValueOnce(new Collection() as any);
    i.guild.channels.fetch.mockResolvedValueOnce({
      send: mock(async () => {
        markStarted();
        await hold;
      }),
    } as any);

    const deleting = new ButtonInteractionEvent().execute(i as any);
    try {
      await Promise.race([
        started,
        deleting.then(() => { throw new Error("Delete finished without saving the transcript"); }),
      ]);
      expect(i.deferReply).toHaveBeenCalledWith({ flags: MessageFlags.Ephemeral });
      expect(JSON.stringify(i.editReply.mock.calls)).toContain("Deleting ticket");
      expect(i.channel.delete).not.toHaveBeenCalled();
    } finally {
      finish();
      await deleting;
    }
    expect(i.channel.delete).toHaveBeenCalledTimes(1);
    expect(deleteTicketRow).toHaveBeenCalledWith(2);
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

  test("failed close permissions roll back without exposing closed controls", async () => {
    const i = interaction("ticket_close:2", [
      { type: ComponentType.Container },
      { type: ComponentType.ActionRow },
    ]);
    i.channel.permissionOverwrites.set.mockRejectedValueOnce(new Error("missing permission"));

    await new ButtonInteractionEvent().execute(i as any);

    expect(i.channel.send).not.toHaveBeenCalled();
    expect(reopenTicket).toHaveBeenCalledWith(2);
    expect(permissionsFor(i.channel, "opener").has(PermissionFlagsBits.ViewChannel)).toBe(true);
    expect(i.followUp).toHaveBeenCalledTimes(1);
  });

  test("failed close and rollback permissions report incomplete access restoration", async () => {
    const i = interaction("ticket_close:2");
    i.channel.permissionOverwrites.set
      .mockRejectedValueOnce(new Error("close permissions failed"))
      .mockRejectedValueOnce(new Error("rollback permissions failed"));

    await new ButtonInteractionEvent().execute(i as any);

    expect(reopenTicket).toHaveBeenCalledWith(2);
    expect(i.channel.send).not.toHaveBeenCalled();
    expect(JSON.stringify(i.followUp.mock.calls)).toContain("permissions could not be restored");
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
    reopenInteraction.channel.permissionOverwrites.set.mockImplementation(
      async (overwrites: OverwriteResolvable[]) => {
        lifecycleOrder.push(overwrites.some((overwrite) => overwrite.id === "opener") ? "unlock" : "lock");
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

  test("a stale Reopen preserves invited members, roles and permission denies on an open ticket", async () => {
    const i = interaction("ticket_reopen:2");
    await i.channel.permissionOverwrites.set([
      ...i.channel.permissionOverwrites.cache.values(),
      {
        id: "invited",
        type: OverwriteType.Member,
        allow: [...ticketAccess, PermissionFlagsBits.ManageMessages],
        deny: [PermissionFlagsBits.AttachFiles],
      },
      {
        id: "invited-role",
        type: OverwriteType.Role,
        allow: ticketAccess,
        deny: [PermissionFlagsBits.MentionEveryone],
      },
      {
        id: "restricted",
        type: OverwriteType.Member,
        deny: [PermissionFlagsBits.ViewChannel],
      },
    ]);
    const originals = ["invited", "invited-role", "restricted"].map((id) =>
      i.channel.permissionOverwrites.cache.get(id)!.toJSON(),
    );

    await new ButtonInteractionEvent().execute(i as any);

    expect(reopenTicket).not.toHaveBeenCalled();
    for (const original of originals) {
      expect(i.channel.permissionOverwrites.cache.get(original.id)?.toJSON()).toEqual(original);
    }
    expect(permissionsFor(i.channel, "invited").has(PermissionFlagsBits.ManageMessages)).toBe(true);
    expect(permissionsFor(i.channel, "role-member", ["invited-role"]).has(PermissionFlagsBits.ViewChannel)).toBe(true);
    expect(permissionsFor(i.channel, "restricted").has(PermissionFlagsBits.ViewChannel)).toBe(false);
  });

  test("failed permission restore leaves the ticket closed", async () => {
    ticket.status = "closed";
    const i = interaction("ticket_reopen:2", [
      { type: ComponentType.Container },
      { type: ComponentType.ActionRow },
    ]);
    i.channel.permissionOverwrites.set.mockRejectedValueOnce(
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
    i.channel.permissionOverwrites.set.mockImplementation(
      async (overwrites: OverwriteResolvable[]) => {
        permissionChanges.push(overwrites.some((overwrite) => overwrite.id === "opener"));
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
    expect(permissionsFor(i.channel, "opener").has(PermissionFlagsBits.ViewChannel)).toBe(true);
  });

  test("failed closed notice restores a manually invited member's original permissions", async () => {
    const i = interaction("ticket_close:2");
    await i.channel.permissionOverwrites.set([
      ...i.channel.permissionOverwrites.cache.values(),
      {
        id: "invited",
        type: OverwriteType.Member,
        allow: [...ticketAccess, PermissionFlagsBits.ManageMessages],
        deny: [PermissionFlagsBits.AttachFiles],
      },
    ]);
    const original = i.channel.permissionOverwrites.cache.get("invited")!.toJSON();
    i.channel.permissionOverwrites.set.mockClear();
    i.channel.send.mockImplementationOnce(async () => {
      expect(permissionsFor(i.channel, "invited").has(PermissionFlagsBits.ViewChannel)).toBe(false);
      throw new Error("closed notice failed");
    });

    await new ButtonInteractionEvent().execute(i as any);

    expect(i.channel.permissionOverwrites.set).toHaveBeenCalledTimes(2);
    expect(reopenTicket).toHaveBeenCalledWith(2);
    expect(i.channel.permissionOverwrites.cache.get("invited")?.toJSON()).toEqual(original);
    expect(permissionsFor(i.channel, "invited").has([
      PermissionFlagsBits.ViewChannel,
      PermissionFlagsBits.ManageMessages,
    ])).toBe(true);
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

    expect(i.deferReply).toHaveBeenCalledWith({ flags: MessageFlags.Ephemeral });
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
  test.each([
    ["moderator", ["mod"], "general"],
    ["moderator", ["mod"], "council"],
    ["council member", ["council"], "council"],
    ["moderator with council role", ["mod", "council"], "general"],
  ])("allows %s (%j) to open %s tickets for another user", async (_, roles, category) => {
    const { interaction, ticketChannel } = openingInteraction();
    interaction.user = { id: "creator", username: "Creator" };
    interaction.options = {
      getString: (name: string) => (name === "category" ? category : null),
      getUser: () => ({ id: "target" }),
    };
    interaction.client = {
      channels: { fetch: mock(async () => null) },
    };
    interaction.deferReply = mock(async () => {});
    interaction.guild.members = {
      fetch: mock(async (id: string) =>
        id === "creator"
          ? { roles: { cache: { has: (role: string) => roles.includes(role) } } }
          : { user: { tag: "Target", username: "Target" } },
      ),
    };
    listOpenTicketsForUser.mockResolvedValueOnce([]);

    await new NewTicketCommand().execute(interaction as any);

    expect(interaction.deferReply).toHaveBeenCalledTimes(1);
    expect(interaction.guild.channels.create).toHaveBeenCalledTimes(1);
    expect(ticketChannel.setName).toHaveBeenCalledWith(`target-${category}-0002`);
    expect(listOpenTicketsForUser).toHaveBeenCalledWith("target");
    expect(createTicket).toHaveBeenCalledWith(expect.objectContaining({
      openerDiscordId: "target",
      category,
    }));
  });

  test.each([
    ["council member", ["council"], "general"],
    ["council member", ["council"], "grief"],
    ["council member", ["council"], "appeal"],
    ["ordinary member", [], "council"],
    ["ordinary member", [], "general"],
  ])("denies %s (%j) opening %s tickets for another user", async (_, roles, category) => {
    const { interaction } = openingInteraction();
    interaction.options = {
      getString: (name: string) => (name === "category" ? category : null),
      getUser: () => ({ id: "target" }),
    };
    interaction.reply = mock(async () => {});
    interaction.guild.members = {
      fetch: mock(async () => ({
        roles: { cache: { has: (role: string) => roles.includes(role) } },
      })),
    };

    await new NewTicketCommand().execute(interaction as any);

    expect(interaction.guild.channels.create).not.toHaveBeenCalled();
    expect(createTicket).not.toHaveBeenCalled();
    expect(JSON.stringify(interaction.reply.mock.calls[0]?.[0])).toContain(
      "Missing permissions",
    );
  });

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
