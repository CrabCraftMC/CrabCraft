"use client";

import { useActionState, useState } from "react";
import { grantTagAction, revokeTagAction, type TagActionState } from "@/app/admin/tag-actions";
import { Button } from "@/components/ui/button";

const INITIAL_STATE: TagActionState = { status: "idle", message: "" };
const INPUT_CLASS = "w-full rounded-lg border border-[var(--line)] bg-[var(--paper)] px-3 py-2 text-sm";

type Props = {
  minecraftUuid: string;
} & ({
  mode: "grant";
  tags: { id: string; name: string }[];
} | {
  mode: "revoke";
  tag: { id: string; name: string };
});

export default function TagOwnershipForm(props: Props) {
  const [reason, setReason] = useState("");
  const [tagId, setTagId] = useState(props.mode === "revoke" ? props.tag.id : "");
  const [state, action, pending] = useActionState(props.mode === "grant" ? grantTagAction : revokeTagAction, INITIAL_STATE);
  const isGrant = props.mode === "grant";

  return (
    <form action={action} className="space-y-3">
      <input type="hidden" name="minecraft_uuid" value={props.minecraftUuid} />
      <fieldset disabled={pending} className="space-y-3 disabled:opacity-60">
        {props.mode === "grant" ? (
          <label className="block space-y-1">
            <span className="block text-xs font-medium opacity-70">Tag to grant</span>
            <select name="tag_id" required value={tagId} onChange={(event) => setTagId(event.target.value)} className={INPUT_CLASS}>
              <option value="">Choose a tag</option>
              {props.tags.map((tag) => <option key={tag.id} value={tag.id}>{tag.name} ({tag.id})</option>)}
            </select>
          </label>
        ) : (
          <>
            <input type="hidden" name="tag_id" value={props.tag.id} />
            <p className="text-xs opacity-70">Remove {props.tag.name} from this account. If equipped, its selection will be cleared.</p>
          </>
        )}
        <label className="block space-y-1">
          <span className="block text-xs font-medium opacity-70">Reason for {isGrant ? "granting" : "revoking"}</span>
          <input name="reason" required maxLength={512} value={reason} onChange={(event) => setReason(event.target.value)} className={INPUT_CLASS} />
        </label>
        <Button type="submit" size="sm" variant={isGrant ? "default" : "destructive"} disabled={pending}>
          {pending ? "Saving…" : isGrant ? "Grant tag" : "Revoke tag"}
        </Button>
      </fieldset>
      <p aria-live="polite" role={state.status === "error" ? "alert" : "status"} className={`text-sm ${state.status === "error" ? "text-red-500" : "text-green-600 dark:text-green-400"}`}>
        {state.message}
      </p>
    </form>
  );
}
