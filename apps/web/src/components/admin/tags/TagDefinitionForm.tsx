"use client";

import { useActionState, useState } from "react";
import type { AdminTag } from "@crabcraft/db/queries/tag-admin";
import { saveTagAction, type TagActionState } from "@/app/admin/tag-actions";
import { Button } from "@/components/ui/button";

const INITIAL_STATE: TagActionState = { status: "idle", message: "" };
const INPUT_CLASS = "w-full rounded-lg border border-[var(--line)] bg-[var(--paper)] px-3 py-2 text-sm";

export default function TagDefinitionForm({ tag }: { tag?: AdminTag }) {
  const [values, setValues] = useState({
    id: tag?.id ?? "",
    name: tag?.name ?? "",
    description: tag?.description ?? "",
    glyph_id: tag?.glyph_id ?? "",
    sort_order: String(tag?.sort_order ?? 0),
    enabled: tag?.enabled ?? true,
  });
  const [state, action, pending] = useActionState(async (previous: TagActionState, formData: FormData) => {
    const result = await saveTagAction(previous, formData);
    if (result.status === "success" && !tag) {
      setValues({ id: "", name: "", description: "", glyph_id: "", sort_order: "0", enabled: true });
    }
    return result;
  }, INITIAL_STATE);

  return (
    <form action={action} className="space-y-4">
      <input type="hidden" name="operation" value={tag ? "update" : "create"} />
      <fieldset disabled={pending} className="space-y-4 disabled:opacity-60">
        <div className="grid gap-4 sm:grid-cols-2">
          <label className="space-y-1">
            <span className="block text-xs font-medium opacity-70">Tag ID {tag ? "(permanent)" : "(lowercase, e.g. halloween-2026-pumpkin)"}</span>
            <input name="id" required readOnly={!!tag} maxLength={96} pattern="[a-z0-9][a-z0-9_\-]*" value={values.id} onChange={(event) => setValues({ ...values, id: event.target.value })} className={`${INPUT_CLASS} read-only:opacity-60`} />
          </label>
          <label className="space-y-1">
            <span className="block text-xs font-medium opacity-70">Display name</span>
            <input name="name" required maxLength={160} value={values.name} onChange={(event) => setValues({ ...values, name: event.target.value })} className={INPUT_CLASS} />
          </label>
          <label className="space-y-1">
            <span className="block text-xs font-medium opacity-70">Nexo glyph ID</span>
            <input name="glyph_id" required maxLength={128} pattern="[a-z0-9][a-z0-9_\-]*" placeholder="pumpkin" value={values.glyph_id} onChange={(event) => setValues({ ...values, glyph_id: event.target.value })} className={INPUT_CLASS} />
          </label>
          <label className="space-y-1">
            <span className="block text-xs font-medium opacity-70">Display order (lowest first)</span>
            <input name="sort_order" type="number" required step="1" min="-2147483648" max="2147483647" value={values.sort_order} onChange={(event) => setValues({ ...values, sort_order: event.target.value })} className={INPUT_CLASS} />
          </label>
        </div>
        <label className="block space-y-1">
          <span className="block text-xs font-medium opacity-70">Description shown in-game (optional)</span>
          <input name="description" maxLength={1024} value={values.description} onChange={(event) => setValues({ ...values, description: event.target.value })} className={INPUT_CLASS} />
        </label>
        <label className="flex items-start gap-2 text-sm">
          <input type="checkbox" name="enabled" checked={values.enabled} onChange={(event) => setValues({ ...values, enabled: event.target.checked })} className="mt-1 accent-orange-500" />
          <span>Enabled <span className="block text-xs opacity-60">Disabled tags are hidden in-game. Existing unlocks are kept.</span></span>
        </label>
        <Button type="submit" size="sm" disabled={pending}>{pending ? "Saving…" : tag ? "Save tag" : "Create tag"}</Button>
      </fieldset>
      <p aria-live="polite" role={state.status === "error" ? "alert" : "status"} className={`text-sm ${state.status === "error" ? "text-red-500" : "text-green-600 dark:text-green-400"}`}>
        {state.message}
      </p>
    </form>
  );
}
