"use server";

import { auth } from "@/lib/auth";
import {
  createTag,
  updateTag,
  grantPlayerTag,
  revokePlayerTag,
  TagAdminError,
} from "@crabcraft/db/queries/tag-admin";
import { revalidatePath } from "next/cache";

export type TagActionState = {
  status: "idle" | "success" | "error";
  message: string;
};

function text(formData: FormData, key: string): string {
  const value = formData.get(key);
  if (typeof value !== "string") throw new TagAdminError("INVALID_INPUT", `${key} is required.`);
  return value;
}

function displayOrder(formData: FormData): number {
  const value = text(formData, "sort_order");
  if (!/^-?\d+$/.test(value)) throw new TagAdminError("INVALID_INPUT", "Display order must be a whole number.");
  return Number(value);
}

function failure(error: unknown): TagActionState {
  if (error instanceof TagAdminError) return { status: "error", message: error.message };
  console.error("[admin tags] Tag update failed:", error);
  return { status: "error", message: "The change could not be confirmed. Refresh to check the current state before trying again." };
}

export async function saveTagAction(_previous: TagActionState, formData: FormData): Promise<TagActionState> {
  try {
    const session = await auth();
    if (session?.user?.role !== "admin") return { status: "error", message: "Administrator access is required." };
    const input = {
      id: text(formData, "id"),
      name: text(formData, "name"),
      description: text(formData, "description"),
      glyph_id: text(formData, "glyph_id"),
      sort_order: displayOrder(formData),
      enabled: formData.get("enabled") === "on",
    };
    const operation = text(formData, "operation");
    if (operation === "create") await createTag(input);
    else if (operation === "update") await updateTag(input);
    else throw new TagAdminError("INVALID_INPUT", "Choose a valid tag operation.");
    revalidatePath("/admin");
    return { status: "success", message: operation === "create" ? "Tag created. It is ready to grant once its Nexo glyph is deployed." : "Tag saved. Online players receive the update within 30 seconds." };
  } catch (error) {
    return failure(error);
  }
}

export async function grantTagAction(_previous: TagActionState, formData: FormData): Promise<TagActionState> {
  try {
    const session = await auth();
    if (session?.user?.role !== "admin") return { status: "error", message: "Administrator access is required." };
    const changed = await grantPlayerTag({
      minecraftUuid: text(formData, "minecraft_uuid"),
      tagId: text(formData, "tag_id"),
      actor: `discord:${session.user.discordId}`,
      reason: text(formData, "reason"),
    });
    revalidatePath("/admin");
    return { status: "success", message: changed ? "Tag granted. The player can equip it in-game; their current selection is unchanged." : "This account already owns the tag. Nothing changed." };
  } catch (error) {
    return failure(error);
  }
}

export async function revokeTagAction(_previous: TagActionState, formData: FormData): Promise<TagActionState> {
  try {
    const session = await auth();
    if (session?.user?.role !== "admin") return { status: "error", message: "Administrator access is required." };
    const changed = await revokePlayerTag({
      minecraftUuid: text(formData, "minecraft_uuid"),
      tagId: text(formData, "tag_id"),
      actor: `discord:${session.user.discordId}`,
      reason: text(formData, "reason"),
    });
    revalidatePath("/admin");
    return { status: "success", message: changed ? "Tag revoked. If equipped, its selection was also cleared. Online players receive the update within 30 seconds." : "This account no longer owns the tag. Nothing changed." };
  } catch (error) {
    return failure(error);
  }
}
