"use server";

import { auth } from "@/lib/auth";
import {
  addPlayerAlt,
  removePlayerAlt,
  getAltCountForUser,
  getPlayerPrimaryUuid,
  isAltUuidTaken,
  MAX_ALTS,
} from "@crabcraft/db/queries/bot";
import {
  addStreamChannelForUser,
  removeStreamChannelForUser,
  getStreamChannelsForUser,
  isUuidPrimaryAccount,
} from "@crabcraft/db/queries/web";
import type { Platform } from "@crabcraft/db/queries/web";
import { resolveUsername, isValidUsername } from "@crabcraft/shared/mojang";
import { revalidatePath } from "next/cache";

type ActionResult = { success: true } | { success: false; error: string };

const ALLOWED_PLAYER_ROLES = new Set(["verified", "moderator", "admin"]);

async function requireAuth() {
  const session = await auth();
  if (!session?.user) throw new Error("Unauthorized");
  return session.user;
}

export async function addAltAction(formData: FormData): Promise<ActionResult> {
  const user = await requireAuth();
  const username = (formData.get("username") as string)?.trim();

  if (!username || !isValidUsername(username)) {
    return { success: false, error: "Invalid username. Must be 3-16 characters (letters, numbers, underscores)." };
  }

  const altCount = await getAltCountForUser(user.discordId);
  if (altCount >= MAX_ALTS) {
    return { success: false, error: `You can only have ${MAX_ALTS} alt accounts.` };
  }

  if (!ALLOWED_PLAYER_ROLES.has(user.role)) {
    return { success: false, error: "You must be a whitelisted member to manage alt accounts." };
  }

  const primaryUuid = await getPlayerPrimaryUuid(user.discordId);
  if (!primaryUuid) {
    return { success: false, error: "You must link a primary Minecraft account before adding alts." };
  }

  const resolved = await resolveUsername(username);
  if (!resolved) {
    return { success: false, error: "Player not found. Check the username and try again." };
  }

  if (primaryUuid === resolved.uuid) {
    return { success: false, error: "You cannot add your own main account as an alt." };
  }

  const isPrimary = await isUuidPrimaryAccount(resolved.uuid);
  if (isPrimary) {
    return { success: false, error: "This account is already a primary account for another player." };
  }

  const taken = await isAltUuidTaken(resolved.uuid);
  if (taken) {
    return { success: false, error: "This account is already linked as an alt." };
  }

  await addPlayerAlt(user.discordId, resolved.uuid, resolved.name);
  revalidatePath("/settings");
  return { success: true };
}

export async function removeAltAction(formData: FormData): Promise<ActionResult> {
  const user = await requireAuth();
  const uuid = formData.get("uuid") as string;
  if (!uuid) return { success: false, error: "Missing UUID." };

  await removePlayerAlt(user.discordId, uuid);
  revalidatePath("/settings");
  return { success: true };
}

const VALID_PLATFORMS: Platform[] = ["twitch", "tiktok", "youtube"];

export async function addChannelAction(formData: FormData): Promise<ActionResult> {
  const user = await requireAuth();
  const platform = formData.get("platform") as string;
  const channelId = (formData.get("channelId") as string)?.trim();
  const displayName = (formData.get("displayName") as string)?.trim() || undefined;

  if (!VALID_PLATFORMS.includes(platform as Platform)) {
    return { success: false, error: "Invalid platform." };
  }
  if (!channelId) {
    return { success: false, error: "Channel ID is required." };
  }

  const existing = await getStreamChannelsForUser(user.discordId);
  if (existing.some((c) => c.platform === platform)) {
    return { success: false, error: `You already have a ${platform} channel linked.` };
  }

  const added = await addStreamChannelForUser(platform as Platform, channelId, user.discordId, displayName);
  if (!added) {
    return { success: false, error: "This channel is already linked." };
  }

  revalidatePath("/settings");
  return { success: true };
}

export async function removeChannelAction(formData: FormData): Promise<ActionResult> {
  const user = await requireAuth();
  const platform = formData.get("platform") as string;
  const channelId = formData.get("channelId") as string;

  if (!platform || !channelId) return { success: false, error: "Missing data." };

  await removeStreamChannelForUser(platform, channelId, user.discordId);
  revalidatePath("/settings");
  return { success: true };
}
