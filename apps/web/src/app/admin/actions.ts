"use server";

import { auth } from "@/lib/auth";
import {
  createSeason,
  updateSeason,
  setCurrentSeason,
  setPlayerRole,
} from "@crabcraft/db/queries/web";
import type { PlayerRole } from "@crabcraft/db/schema";
import { revalidatePath } from "next/cache";

const VALID_ROLES: PlayerRole[] = ["unverified", "verified", "moderator", "admin"];

async function requireAdmin() {
  const session = await auth();
  if (session?.user?.role !== "admin") throw new Error("Unauthorized");
  return session;
}

export async function createSeasonAction(formData: FormData) {
  await requireAdmin();
  const id = formData.get("id") as string;
  const name = formData.get("name") as string;
  const start_date = (formData.get("start_date") as string) || undefined;
  const end_date = (formData.get("end_date") as string) || undefined;
  if (!id || !name) throw new Error("ID and name are required");
  await createSeason({ id, name, start_date, end_date });
  revalidatePath("/admin");
}

export async function updateSeasonAction(formData: FormData) {
  await requireAdmin();
  const id = formData.get("id") as string;
  const name = (formData.get("name") as string) || undefined;
  const start_date = (formData.get("start_date") as string) || undefined;
  const end_date = (formData.get("end_date") as string) || undefined;
  if (!id) throw new Error("Season ID is required");
  await updateSeason(id, { name, start_date, end_date });
  revalidatePath("/admin");
}

export async function setCurrentSeasonAction(formData: FormData) {
  await requireAdmin();
  const seasonId = formData.get("seasonId") as string;
  if (!seasonId) throw new Error("Season ID is required");
  await setCurrentSeason(seasonId);
  revalidatePath("/admin");
}

export async function setRoleAction(formData: FormData) {
  await requireAdmin();
  const discordId = formData.get("discordId") as string;
  const role = formData.get("role") as string;
  if (!discordId) throw new Error("Discord ID is required");
  if (!VALID_ROLES.includes(role as PlayerRole)) throw new Error("Invalid role");
  await setPlayerRole(discordId, role as PlayerRole);
  revalidatePath("/admin");
}
