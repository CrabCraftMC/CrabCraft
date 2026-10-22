import Link from "next/link";
import type { AdminTag, TagPlayerDetails, TagPlayerSummary } from "@crabcraft/db/queries/tag-admin";
import { Button } from "@/components/ui/button";
import { formatUnixDate } from "@/lib/formatUnixDate";
import TagDefinitionForm from "./tags/TagDefinitionForm";
import TagOwnershipForm from "./tags/TagOwnershipForm";

export default function TagsTab({ tags, players, player, search, error }: {
  tags: AdminTag[];
  players: TagPlayerSummary[];
  player: TagPlayerDetails | null;
  search?: string;
  error?: string;
}) {
  const ownedIds = new Set(player?.tags.map((tag) => tag.id));
  const availableTags = tags.filter((tag) => tag.enabled && !ownedIds.has(tag.id));
  const selected = player?.tags.find((tag) => tag.id === player.selected_tag_id);

  return (
    <div className="space-y-8">
      <section aria-labelledby="tag-catalogue-title" className="space-y-4">
        <div>
          <h2 id="tag-catalogue-title" className="text-xl font-bold">Tag catalogue ({tags.length})</h2>
          <p className="mt-1 text-sm opacity-70">Create and maintain the tags players can earn. Unlocks belong to individual Minecraft accounts.</p>
          <p className="mt-2 text-xs opacity-60">Nexo glyphs must already be deployed to the server resource pack. Icons are previewed in-game; this page stores their glyph IDs. Changes reach online players within 30 seconds.</p>
        </div>
        <details className="rounded-xl bg-[var(--paper-2)] p-4">
          <summary className="cursor-pointer text-sm font-semibold text-orange-500">Create tag</summary>
          <div className="mt-4"><TagDefinitionForm /></div>
        </details>
        <div className="space-y-3">
          {tags.map((tag) => (
            <article key={tag.id} className="rounded-xl bg-[var(--paper-2)] p-4">
              <div className="flex flex-wrap items-center justify-between gap-3">
                <div className="min-w-0">
                  <div className="flex flex-wrap items-center gap-2">
                    <h3 className="font-semibold">{tag.name}</h3>
                    <span className={`rounded-full px-2 py-0.5 text-xs ${tag.enabled ? "bg-green-500/15 text-green-700 dark:text-green-400" : "bg-[var(--paper)] opacity-70"}`}>{tag.enabled ? "Enabled" : "Disabled"}</span>
                  </div>
                  <p className="mt-1 break-all font-mono text-xs opacity-60">{tag.id} · glyph: {tag.glyph_id}</p>
                </div>
                <p className="text-xs opacity-60">{tag.owner_count} owners · {tag.selected_count} selected · order {tag.sort_order}</p>
              </div>
              <p className="mt-2 whitespace-pre-wrap text-sm opacity-70">{tag.description}</p>
              <details className="mt-3 border-t border-[var(--line)] pt-3">
                <summary className="cursor-pointer text-xs font-medium text-orange-500">Edit {tag.name}</summary>
                <div className="mt-4"><TagDefinitionForm tag={tag} /></div>
              </details>
            </article>
          ))}
          {tags.length === 0 && <p className="py-4 text-sm opacity-60">No tags yet. Create a tag to start granting it.</p>}
        </div>
      </section>

      <section aria-labelledby="tag-player-title" className="space-y-4">
        <div>
          <h2 id="tag-player-title" className="text-xl font-bold">Player tags</h2>
          <p className="mt-1 text-sm opacity-70">Find a main or alt account by Minecraft name, or enter an exact UUID for any offline or unlinked account.</p>
        </div>
        <form action="/admin" method="GET" className="flex flex-col gap-2 sm:flex-row sm:items-end">
          <input type="hidden" name="tab" value="tags" />
          <label className="min-w-0 flex-1 space-y-1">
            <span className="block text-xs font-medium opacity-70">Minecraft name or UUID</span>
            <input name="q" required maxLength={64} defaultValue={search} className="w-full rounded-lg border border-[var(--line)] bg-[var(--paper)] px-3 py-2 text-sm" />
          </label>
          <Button type="submit" variant="outline">Search accounts</Button>
        </form>
        {error && <p role="alert" className="text-sm text-red-500">{error}</p>}
        {search && !error && players.length === 0 && <p className="text-sm opacity-60">No matching accounts. For an unlinked player, enter their full Minecraft UUID.</p>}
        {players.length > 0 && (
          <ul className="grid gap-2 sm:grid-cols-2">
            {players.map((result) => (
              <li key={result.minecraft_uuid}>
                <Link href={`/admin?tab=tags&q=${encodeURIComponent(search ?? "")}&player=${encodeURIComponent(result.minecraft_uuid)}`} aria-current={player?.minecraft_uuid === result.minecraft_uuid ? "true" : undefined} className={`block rounded-xl border p-3 text-sm hover:border-orange-400 ${player?.minecraft_uuid === result.minecraft_uuid ? "border-orange-500 bg-orange-500/10" : "border-[var(--line)] bg-[var(--paper-2)]"}`}>
                  <span className="font-medium">{result.minecraft_username ?? "Unlinked account"}</span>{" "}
                  <span className="ml-2 text-xs opacity-60">{result.account_type === "main" ? "Main account" : result.account_type === "alt" ? "Alt account" : "Unlinked"}</span>
                  <span className="mt-1 block break-all font-mono text-xs opacity-60">{result.minecraft_uuid}</span>
                </Link>
              </li>
            ))}
          </ul>
        )}

        {player && (
          <div key={player.minecraft_uuid} className="space-y-5 rounded-xl bg-[var(--paper-2)] p-4 sm:p-5">
            <div>
              <h3 className="text-lg font-semibold">{player.minecraft_username ?? "Unlinked account"}</h3>
              <p className="mt-1 break-all font-mono text-xs opacity-60">{player.minecraft_uuid}</p>
              <p className="mt-2 text-sm">Selected: <strong>{selected ? `${selected.name}${selected.enabled ? "" : " (disabled)"}` : "No tag"}</strong></p>
              <p className="mt-1 text-xs opacity-60">Players choose their own tag with /tags or /settings. Granting a tag keeps their current selection.</p>
            </div>
            <div className="space-y-3">
              <h4 className="font-semibold">Unlocked tags ({player.tags.length})</h4>
              {player.tags.map((tag) => (
                <article key={tag.id} className="rounded-lg border border-[var(--line)] p-3">
                  <div className="flex flex-wrap items-center gap-2 text-sm">
                    <span className="font-medium">{tag.name}</span>
                    {tag.id === player.selected_tag_id && <span className="rounded-full bg-orange-500/15 px-2 py-0.5 text-xs text-orange-600 dark:text-orange-400">Selected</span>}
                    {!tag.enabled && <span className="text-xs opacity-60">Disabled</span>}
                  </div>
                  <p className="mt-1 break-words text-xs opacity-60">Earned {formatUnixDate(tag.granted_at)} · {tag.source} · {tag.granted_by}</p>
                  <details className="mt-2">
                    <summary className="cursor-pointer text-xs font-medium text-red-500">Revoke {tag.name}</summary>
                    <div className="mt-3"><TagOwnershipForm mode="revoke" minecraftUuid={player.minecraft_uuid} tag={{ id: tag.id, name: tag.name }} /></div>
                  </details>
                </article>
              ))}
              {player.tags.length === 0 && <p className="text-sm opacity-60">This account has no unlocked tags.</p>}
            </div>
            <div className="space-y-3 border-t border-[var(--line)] pt-4">
              <h4 className="font-semibold">Grant a tag</h4>
              {availableTags.length > 0 ? <TagOwnershipForm mode="grant" minecraftUuid={player.minecraft_uuid} tags={availableTags.map(({ id, name }) => ({ id, name }))} /> : <p className="text-sm opacity-60">No enabled, unowned tags are available to grant.</p>}
            </div>
            <div className="space-y-3 border-t border-[var(--line)] pt-4">
              <h4 className="font-semibold">Recent award history</h4>
              <ol className="space-y-3">
                {player.history.map((entry) => (
                  <li key={entry.id} className="break-words rounded-lg border border-[var(--line)] p-3 text-xs">
                    <p className="font-medium">{entry.action === "grant" ? "Granted" : "Revoked"}: {entry.tag_name} {!entry.ownership_changed && "(ownership unchanged)"}</p>
                    <p className="mt-1 opacity-60">{formatUnixDate(entry.created_at)} · {entry.actor} · {entry.source}</p>
                    <p className="mt-1 opacity-80">{entry.reason}</p>
                  </li>
                ))}
              </ol>
              {player.history.length === 0 && <p className="text-sm opacity-60">No award history for this account.</p>}
            </div>
          </div>
        )}
      </section>
    </div>
  );
}
