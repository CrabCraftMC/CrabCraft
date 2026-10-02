import { describe, expect, test } from "bun:test";
import { MessageType } from "discord.js";
import {
  relayStaffChatMessage,
  STAFF_CHAT_REDIS_OPTIONS,
  type StaffChatIdentity,
  type StaffChatMessage,
  type StaffChatRelayDependencies,
} from "../src/utils/staffChatRelay.js";

const config = {
  guildId: "guild",
  channelId: "staff",
  modRoleId: "mod",
  councilRoleId: "council",
  redisChannel: "crabutilities:staffchat",
};

function message(roles = ["mod"]): StaffChatMessage {
  return {
    id: "message-id",
    guildId: "guild",
    channelId: "staff",
    system: false,
    type: MessageType.Default,
    webhookId: null,
    content: "Hello",
    author: { id: "discord-id", username: "discord_name", bot: false },
    member: { roles: { cache: { has: (id) => roles.includes(id) } } },
  };
}

const linkedIdentity: StaffChatIdentity = {
  minecraft_uuid: "11111111-2222-3333-4444-555555555555",
  minecraft_username: "MinecraftName",
  nickname: "Nickname",
  nickname_raw: "<red>Nickname</red>",
};

function dependencies(identity: StaffChatIdentity | null = null, cached: string | null = null) {
  const published: Array<{ channel: string; payload: Record<string, unknown> }> = [];
  const nicknameReads: Array<{ key: string; uuid: string }> = [];
  const lookedUp: string[] = [];
  const failures: unknown[] = [];
  const value: StaffChatRelayDependencies = {
    redis: {
      status: "ready",
      hget: async (key, uuid) => {
        nicknameReads.push({ key, uuid });
        return cached;
      },
      publish: async (channel, payload) => {
        published.push({ channel, payload: JSON.parse(payload) });
        return 1;
      },
    },
    lookupIdentity: async (id) => {
      lookedUp.push(id);
      return identity;
    },
    logFailure: (_message, error) => failures.push(error),
  };
  return { value, published, nicknameReads, lookedUp, failures };
}

describe("Discord staff chat relay", () => {
  test("requires configured guild/channel and either staff role", async () => {
    for (const candidate of [
      { ...message(), guildId: "other" },
      { ...message(), guildId: null },
      { ...message(), channelId: "other" },
      message([]),
      { ...message(), member: null },
    ]) {
      const state = dependencies();
      expect(await relayStaffChatMessage(candidate, config, state.value)).toBe(false);
      expect(state.lookedUp).toEqual([]);
      expect(state.published).toEqual([]);
    }
    const disabled = dependencies();
    expect(await relayStaffChatMessage(message(), { ...config, channelId: "" }, disabled.value)).toBe(false);
    for (const role of ["mod", "council"]) {
      const state = dependencies();
      expect(await relayStaffChatMessage(message([role]), config, state.value)).toBe(true);
    }
  });

  test("ignores bots, webhook echoes, system events and attachment-only messages", async () => {
    for (const candidate of [
      { ...message(), author: { ...message().author, bot: true } },
      { ...message(), webhookId: "webhook" },
      { ...message(), system: true },
      { ...message(), type: MessageType.ChannelPinnedMessage },
      { ...message(), content: "" },
      { ...message(), content: " \r\n\t " },
    ]) {
      const state = dependencies();
      expect(await relayStaffChatMessage(candidate, config, state.value)).toBe(false);
      expect(state.lookedUp).toEqual([]);
      expect(state.published).toEqual([]);
    }
  });

  test("publishes current Discord username for unlinked users and preserves literal text", async () => {
    const state = dependencies();
    const candidate = { ...message(), type: MessageType.Reply, content: "  <red>Hello</red>\n\t@everyone\u0000 world  " };
    expect(await relayStaffChatMessage(candidate, config, state.value)).toBe(true);
    expect(state.published).toEqual([{
      channel: config.redisChannel,
      payload: {
        source: "discord", sender: "discord_name", nicknameRaw: null,
        message: "<red>Hello</red> @everyone world", messageId: "message-id",
      },
    }]);
    expect(state.nicknameReads).toEqual([]);
  });

  test("uses Redis nickname for linked users, including offline players", async () => {
    const state = dependencies(linkedIdentity, "<#abc123>CurrentNick</#abc123>");
    expect(await relayStaffChatMessage(message(), config, state.value)).toBe(true);
    expect(state.nicknameReads).toEqual([{
      key: "crabutilities:nicknames", uuid: linkedIdentity.minecraft_uuid!,
    }]);
    expect(state.published[0]?.payload).toMatchObject({
      sender: "MinecraftName", nicknameRaw: "<#abc123>CurrentNick</#abc123>",
    });
  });

  test("empty Redis tombstone overrides stale raw and plain database nicknames", async () => {
    const state = dependencies(linkedIdentity, "");
    expect(await relayStaffChatMessage(message(), config, state.value)).toBe(true);
    expect(state.published[0]?.payload).toMatchObject({ sender: "MinecraftName", nicknameRaw: null });
  });

  test("falls back to raw database nickname only when the Redis field is absent", async () => {
    const state = dependencies(linkedIdentity);
    expect(await relayStaffChatMessage(message(), config, state.value)).toBe(true);
    expect(state.published[0]?.payload).toMatchObject({ sender: "MinecraftName", nicknameRaw: linkedIdentity.nickname_raw });
  });

  test("plain-only database nickname is sent as literal sender", async () => {
    const state = dependencies({ ...linkedIdentity, nickname_raw: null, nickname: "<red>literal name" });
    expect(await relayStaffChatMessage(message(), config, state.value)).toBe(true);
    expect(state.published[0]?.payload).toMatchObject({ sender: "<red>literal name", nicknameRaw: null });
  });

  test("Minecraft username is used when no nickname exists", async () => {
    const state = dependencies({ ...linkedIdentity, nickname_raw: null, nickname: null });
    expect(await relayStaffChatMessage(message(), config, state.value)).toBe(true);
    expect(state.published[0]?.payload).toMatchObject({ sender: "MinecraftName", nicknameRaw: null });
  });

  test("unlinked rows cannot retain an old Minecraft nickname", async () => {
    const state = dependencies({ ...linkedIdentity, minecraft_uuid: null });
    expect(await relayStaffChatMessage(message(), config, state.value)).toBe(true);
    expect(state.published[0]?.payload).toMatchObject({ sender: "discord_name", nicknameRaw: null });
    expect(state.nicknameReads).toEqual([]);
  });

  test("database failure is logged and cannot masquerade as an unlinked account", async () => {
    const state = dependencies();
    state.value.lookupIdentity = async () => { throw new Error("database unavailable"); };
    expect(await relayStaffChatMessage(message(), config, state.value)).toBe(false);
    expect(state.published).toEqual([]);
    expect(state.failures).toHaveLength(1);
  });

  test("Redis failure while reading current nickname drops the message", async () => {
    const state = dependencies(linkedIdentity);
    state.value.redis.hget = async () => { throw new Error("connection lost"); };
    expect(await relayStaffChatMessage(message(), config, state.value)).toBe(false);
    expect(state.published).toEqual([]);
    expect(state.failures).toHaveLength(1);
  });

  test("unavailable messages are dropped and only new messages relay after recovery", async () => {
    const state = dependencies();
    state.value.redis.status = "reconnecting";
    expect(await relayStaffChatMessage(message(), config, state.value)).toBe(false);
    expect(state.lookedUp).toEqual([]);
    state.value.redis.status = "ready";
    expect(state.published).toEqual([]);
    expect(await relayStaffChatMessage({ ...message(), id: "new-message" }, config, state.value)).toBe(true);
    expect(state.published.map((entry) => entry.payload.messageId)).toEqual(["new-message"]);
  });

  test("publish failure is logged once without retrying the command", async () => {
    const state = dependencies();
    let attempts = 0;
    state.value.redis.publish = async () => { attempts += 1; throw new Error("disconnected"); };
    expect(await relayStaffChatMessage(message(), config, state.value)).toBe(false);
    expect(attempts).toBe(1);
    expect(state.failures).toHaveLength(1);
  });

  test("Redis transport drops failed commands and reconnects with a capped delay", () => {
    expect(STAFF_CHAT_REDIS_OPTIONS.enableOfflineQueue).toBe(false);
    expect(STAFF_CHAT_REDIS_OPTIONS.autoResendUnfulfilledCommands).toBe(false);
    expect(STAFF_CHAT_REDIS_OPTIONS.maxRetriesPerRequest).toBe(0);
    expect(STAFF_CHAT_REDIS_OPTIONS.commandTimeout).toBe(5000);
    expect(STAFF_CHAT_REDIS_OPTIONS.retryStrategy(1)).toBe(1000);
    expect(STAFF_CHAT_REDIS_OPTIONS.retryStrategy(3)).toBe(3000);
    expect(STAFF_CHAT_REDIS_OPTIONS.retryStrategy(4)).toBe(3000);
    expect(STAFF_CHAT_REDIS_OPTIONS.retryStrategy(100)).toBe(3000);
  });
});
