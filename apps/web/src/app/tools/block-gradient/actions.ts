"use server";

import { createHash } from "node:crypto";
import { createBlockGradientShare } from "@crabcraft/db/queries/web";
import {
  BLOCK_GRADIENT_SHARE_VERSION,
  normaliseBlockGradientShareState,
} from "@/lib/blockGradientShare";

type ShareResult =
  | { success: true; id: string }
  | { success: false; error: string };

export async function createBlockGradientShareAction(
  input: unknown,
): Promise<ShareResult> {
  const state = normaliseBlockGradientShareState(input);
  if (!state) {
    return { success: false, error: "This gradient could not be shared." };
  }

  const id = createHash("sha256")
    .update(JSON.stringify({ version: BLOCK_GRADIENT_SHARE_VERSION, state }))
    .digest("base64url")
    .slice(0, 16);

  try {
    await createBlockGradientShare(
      id,
      BLOCK_GRADIENT_SHARE_VERSION,
      state,
    );
    return { success: true, id };
  } catch (error) {
    console.error("Failed to create Block Gradient share:", error);
    return { success: false, error: "The share link could not be created." };
  }
}
