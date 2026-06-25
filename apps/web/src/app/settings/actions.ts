"use server";

import { auth } from "@/lib/auth";
import {
  addStreamChannelForUser,
  removeStreamChannelForUser,
  getStreamChannelsForUser,
} from "@crabcraft/db/queries/web";
import type { Platform } from "@crabcraft/db/queries/web";
import { revalidatePath } from "next/cache";

type ActionResult = { success: true } | { success: false; error: string };

async function requireAuth() {
  const session = await auth();
  if (!session?.user) throw new Error("Unauthorized");
  return session.user;
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
