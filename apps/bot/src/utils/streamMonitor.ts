import type { Client, Guild } from "discord.js";
import logger from "./logger.js";
import config from "./config.js";
import { getStreamChannelsByPlatform, type StreamChannel } from "./streamDb.js";
import {
  YOUTUBE_RSS_POLL_MS,
  YOUTUBE_LIVE_CHECK_MS,
  TWITCH_POLL_MS,
  TIKTOK_POLL_MS,
} from "./constants.js";

// ── In-memory state ─────────────────────────────────────────────────

/** Maps "platform:channelId" → Set of active stream keys per Discord user */
const activeStreams = new Map<string, Set<string>>();

/** Known YouTube video IDs per channel (to detect new uploads) */
const knownYouTubeVideos = new Map<string, Set<string>>();

/** Currently live YouTube video IDs that need periodic rechecking */
const liveYouTubeVideos = new Set<string>();

/** Cached Twitch app access token */
let twitchToken: string | null = null;
let twitchTokenExpiresAt = 0;

// ── Role helpers ────────────────────────────────────────────────────

function streamKey(platform: string, channelId: string): string {
  return `${platform}:${channelId}`;
}

async function addLiveRole(guild: Guild, discordUserId: string, key: string): Promise<void> {
  if (!config.LIVE_ROLE_ID) return;

  let userStreams = activeStreams.get(discordUserId);
  if (!userStreams) {
    userStreams = new Set();
    activeStreams.set(discordUserId, userStreams);
  }

  const wasEmpty = userStreams.size === 0;
  userStreams.add(key);

  if (wasEmpty) {
    const member = await guild.members.fetch(discordUserId).catch(() => null);
    if (member && !member.roles.cache.has(config.LIVE_ROLE_ID)) {
      await member.roles.add(config.LIVE_ROLE_ID, "Stream went live").catch(() => null);
      logger.info(`Added live role to ${member.user.tag} (${key})`);
    }
  }
}

async function removeLiveRole(guild: Guild, discordUserId: string, key: string): Promise<void> {
  if (!config.LIVE_ROLE_ID) return;

  const userStreams = activeStreams.get(discordUserId);
  if (!userStreams) return;

  userStreams.delete(key);

  if (userStreams.size === 0) {
    activeStreams.delete(discordUserId);
    const member = await guild.members.fetch(discordUserId).catch(() => null);
    if (member && member.roles.cache.has(config.LIVE_ROLE_ID)) {
      await member.roles.remove(config.LIVE_ROLE_ID, "Stream went offline").catch(() => null);
      logger.info(`Removed live role from ${member.user.tag} (${key})`);
    }
  }
}

// ── YouTube ─────────────────────────────────────────────────────────

const youtubeChannelIdCache = new Map<string, string>();

async function resolveYouTubeChannelId(input: string): Promise<string> {
  // Already a channel ID
  if (input.startsWith("UC") && !input.startsWith("@")) return input;

  const cached = youtubeChannelIdCache.get(input);
  if (cached) return cached;

  // Resolve @handle to channel ID by fetching the channel page
  const handle = input.startsWith("@") ? input : `@${input}`;
  const res = await fetch(`https://www.youtube.com/${handle}`, {
    headers: { "Accept-Language": "en" },
  });
  if (!res.ok) throw new Error(`YouTube returned ${res.status} for ${handle}`);
  const html = await res.text();

  const match = html.match(/"externalId"\s*:\s*"(UC[^"]+)"/);
  if (!match) throw new Error(`Could not resolve YouTube channel ID for ${handle}`);

  youtubeChannelIdCache.set(input, match[1]);
  return match[1];
}

async function fetchYouTubeRSS(channelInput: string): Promise<string[]> {
  const channelId = await resolveYouTubeChannelId(channelInput);
  const url = `https://www.youtube.com/feeds/videos.xml?channel_id=${channelId}`;
  const res = await fetch(url);
  if (!res.ok) throw new Error(`YouTube RSS returned ${res.status}`);
  const xml = await res.text();

  const videoIds: string[] = [];
  const regex = /<yt:videoId>([^<]+)<\/yt:videoId>/g;
  let match: RegExpExecArray | null;
  while ((match = regex.exec(xml)) !== null) {
    videoIds.push(match[1]);
  }
  return videoIds;
}

interface YouTubeLiveDetails {
  videoId: string;
  isLive: boolean;
}

async function checkYouTubeLiveStatus(videoIds: string[]): Promise<YouTubeLiveDetails[]> {
  if (!config.YOUTUBE_API_KEY || videoIds.length === 0) return [];

  const results: YouTubeLiveDetails[] = [];

  for (let i = 0; i < videoIds.length; i += 50) {
    const batch = videoIds.slice(i, i + 50);
    const params = new URLSearchParams({
      part: "snippet,liveStreamingDetails",
      id: batch.join(","),
      key: config.YOUTUBE_API_KEY,
    });

    const res = await fetch(`https://www.googleapis.com/youtube/v3/videos?${params}`);
    if (!res.ok) {
      logger.error(`YouTube API error: ${res.status} ${res.statusText}`);
      continue;
    }

    const data = await res.json() as {
      items?: Array<{
        id: string;
        snippet?: { liveBroadcastContent?: string };
        liveStreamingDetails?: { actualEndTime?: string };
      }>;
    };

    for (const item of data.items ?? []) {
      const isLive =
        item.snippet?.liveBroadcastContent === "live" &&
        !item.liveStreamingDetails?.actualEndTime;
      results.push({ videoId: item.id, isLive });
    }
  }

  return results;
}

async function pollYouTube(guild: Guild): Promise<void> {
  const channels = await getStreamChannelsByPlatform("youtube");
  if (channels.length === 0) return;
  if (!config.YOUTUBE_API_KEY) return;

  const newVideoIds: string[] = [];

  for (const channel of channels) {
    try {
      const videoIds = await fetchYouTubeRSS(channel.channel_id);
      let known = knownYouTubeVideos.get(channel.channel_id);

      if (!known) {
        known = new Set(videoIds);
        knownYouTubeVideos.set(channel.channel_id, known);
        continue;
      }

      for (const vid of videoIds) {
        if (!known.has(vid)) {
          known.add(vid);
          newVideoIds.push(vid);
        }
      }
    } catch (error) {
      logger.error(`Failed to fetch YouTube RSS for ${channel.channel_id}:`, error);
    }
  }

  if (newVideoIds.length > 0) {
    const results = await checkYouTubeLiveStatus(newVideoIds);
    for (const result of results) {
      if (result.isLive) {
        liveYouTubeVideos.add(result.videoId);
        for (const channel of channels) {
          const known = knownYouTubeVideos.get(channel.channel_id);
          if (known?.has(result.videoId)) {
            const key = streamKey("youtube", channel.channel_id);
            await addLiveRole(guild, channel.discord_user_id, key);
            break;
          }
        }
      }
    }
  }
}

async function checkYouTubeLiveStreams(guild: Guild): Promise<void> {
  if (liveYouTubeVideos.size === 0) return;
  if (!config.YOUTUBE_API_KEY) return;

  const channels = await getStreamChannelsByPlatform("youtube");
  const videoIds = Array.from(liveYouTubeVideos);
  const results = await checkYouTubeLiveStatus(videoIds);

  for (const result of results) {
    if (!result.isLive) {
      liveYouTubeVideos.delete(result.videoId);
      for (const channel of channels) {
        const known = knownYouTubeVideos.get(channel.channel_id);
        if (known?.has(result.videoId)) {
          const key = streamKey("youtube", channel.channel_id);
          await removeLiveRole(guild, channel.discord_user_id, key);
          break;
        }
      }
    }
  }
}

// ── Twitch ──────────────────────────────────────────────────────────

async function getTwitchToken(): Promise<string | null> {
  if (!config.TWITCH_CLIENT_ID || !config.TWITCH_CLIENT_SECRET) return null;

  if (twitchToken && Date.now() < twitchTokenExpiresAt) return twitchToken;

  const res = await fetch("https://id.twitch.tv/oauth2/token", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      client_id: config.TWITCH_CLIENT_ID,
      client_secret: config.TWITCH_CLIENT_SECRET,
      grant_type: "client_credentials",
    }),
  });

  if (!res.ok) {
    logger.error(`Twitch auth error: ${res.status} ${res.statusText}`);
    return null;
  }

  const data = await res.json() as { access_token: string; expires_in: number };
  twitchToken = data.access_token;
  twitchTokenExpiresAt = Date.now() + (data.expires_in - 300) * 1000;
  logger.info("Twitch app access token obtained");
  return twitchToken;
}

async function pollTwitch(guild: Guild): Promise<void> {
  const channels = await getStreamChannelsByPlatform("twitch");
  if (channels.length === 0) return;

  const token = await getTwitchToken();
  if (!token) return;

  const logins = channels.map((ch) => ch.channel_id);
  const liveLogins = new Set<string>();

  for (let i = 0; i < logins.length; i += 100) {
    const batch = logins.slice(i, i + 100);
    const params = new URLSearchParams();
    for (const login of batch) params.append("user_login", login);

    const res = await fetch(`https://api.twitch.tv/helix/streams?${params}`, {
      headers: {
        Authorization: `Bearer ${token}`,
        "Client-Id": config.TWITCH_CLIENT_ID,
      },
    });

    if (res.status === 401) {
      twitchToken = null;
      twitchTokenExpiresAt = 0;
      return;
    }

    if (!res.ok) {
      logger.error(`Twitch API error: ${res.status} ${res.statusText}`);
      continue;
    }

    const data = await res.json() as {
      data: Array<{ user_login: string; type: string }>;
    };

    for (const stream of data.data) {
      if (stream.type === "live") {
        liveLogins.add(stream.user_login.toLowerCase());
      }
    }
  }

  for (const channel of channels) {
    const key = streamKey("twitch", channel.channel_id);
    const isLive = liveLogins.has(channel.channel_id.toLowerCase());
    const wasLive = activeStreams.get(channel.discord_user_id)?.has(key) ?? false;

    if (isLive && !wasLive) {
      await addLiveRole(guild, channel.discord_user_id, key);
    } else if (!isLive && wasLive) {
      await removeLiveRole(guild, channel.discord_user_id, key);
    }
  }
}

// ── TikTok ──────────────────────────────────────────────────────────

async function checkTikTokLive(username: string): Promise<boolean> {
  try {
    const { TikTokLiveConnection } = await import("tiktok-live-connector");
    const connection = new TikTokLiveConnection(username, {});
    return await connection.fetchIsLive();
  } catch {
    return false;
  }
}

async function pollTikTok(guild: Guild): Promise<void> {
  const channels = await getStreamChannelsByPlatform("tiktok");
  if (channels.length === 0) return;

  for (const channel of channels) {
    try {
      const isLive = await checkTikTokLive(channel.channel_id);
      const key = streamKey("tiktok", channel.channel_id);
      const wasLive = activeStreams.get(channel.discord_user_id)?.has(key) ?? false;

      if (isLive && !wasLive) {
        await addLiveRole(guild, channel.discord_user_id, key);
      } else if (!isLive && wasLive) {
        await removeLiveRole(guild, channel.discord_user_id, key);
      }
    } catch (error) {
      logger.error(`TikTok check failed for ${channel.channel_id}:`, error);
    }
  }
}

// ── Main initializer ────────────────────────────────────────────────

export async function initStreamMonitor(client: Client): Promise<void> {
  if (!config.LIVE_ROLE_ID) {
    logger.info("LIVE_ROLE_ID not set, stream monitor disabled");
    return;
  }

  const guild = client.guilds.cache.first();
  if (!guild) {
    logger.error("Stream monitor: no guild found");
    return;
  }

  logger.info("Initializing stream monitor...");

  if (config.YOUTUBE_API_KEY) {
    await pollYouTube(guild).catch((e) =>
      logger.error("YouTube initial poll failed:", e),
    );
    setInterval(async () => {
      try { await pollYouTube(guild); }
      catch (error) { logger.error("YouTube RSS poll error:", error); }
    }, YOUTUBE_RSS_POLL_MS);

    setInterval(async () => {
      try { await checkYouTubeLiveStreams(guild); }
      catch (error) { logger.error("YouTube live recheck error:", error); }
    }, YOUTUBE_LIVE_CHECK_MS);

    logger.info("YouTube stream monitor started");
  } else {
    logger.info("YOUTUBE_API_KEY not set, YouTube monitoring disabled");
  }

  if (config.TWITCH_CLIENT_ID && config.TWITCH_CLIENT_SECRET) {
    setInterval(async () => {
      try { await pollTwitch(guild); }
      catch (error) { logger.error("Twitch poll error:", error); }
    }, TWITCH_POLL_MS);

    logger.info("Twitch stream monitor started");
  } else {
    logger.info("Twitch credentials not set, Twitch monitoring disabled");
  }

  setInterval(async () => {
    try { await pollTikTok(guild); }
    catch (error) { logger.error("TikTok poll error:", error); }
  }, TIKTOK_POLL_MS);

  logger.info("TikTok stream monitor started");
  logger.info("Stream monitor initialized");
}
