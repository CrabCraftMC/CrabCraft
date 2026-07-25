"use server";

import { auth } from "@/lib/auth";
import {
  removeStreamChannelForUser,
} from "@crabcraft/db/queries/web";
import { revalidatePath } from "next/cache";

type ActionResult = { success: true } | { success: false; error: string };

async function requireAuth() {
  const session = await auth();
  if (!session?.user) throw new Error("Unauthorized");
  return session.user;
}

export async function addChannelAction(_formData: FormData): Promise<ActionResult> {
  await requireAuth();
  return {
    success: false,
    error: "Self-service channel linking is unavailable until platform ownership can be verified.",
  };
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
