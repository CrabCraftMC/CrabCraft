"use client";

import type { Season } from "@crabcraft/shared/types";
import { createSeasonAction, updateSeasonAction, setCurrentSeasonAction } from "@/app/admin/actions";
import { Button } from "@/components/ui/button";
import { useState } from "react";

export default function SeasonsTab({ seasons, isAdmin }: { seasons: Season[]; isAdmin: boolean }) {
  const [showCreate, setShowCreate] = useState(false);
  const [editingId, setEditingId] = useState<string | null>(null);

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <h2 className="text-xl font-bold">Seasons</h2>
        {isAdmin && (
          <Button size="sm" onClick={() => setShowCreate(!showCreate)}>
            {showCreate ? "Cancel" : "New Season"}
          </Button>
        )}
      </div>

      {showCreate && (
        <form
          action={async (formData) => {
            await createSeasonAction(formData);
            setShowCreate(false);
          }}
          className="rounded-xl bg-[var(--paper-2)] p-5 space-y-4"
        >
          <h3 className="font-semibold">Create Season</h3>
          <div className="grid gap-4 sm:grid-cols-2">
            <div>
              <label className="mb-1 block text-xs font-medium opacity-60">ID (e.g. s7)</label>
              <input name="id" required className="w-full rounded-lg border border-[var(--line)] bg-[var(--paper)] px-3 py-2 text-sm" />
            </div>
            <div>
              <label className="mb-1 block text-xs font-medium opacity-60">Name</label>
              <input name="name" required className="w-full rounded-lg border border-[var(--line)] bg-[var(--paper)] px-3 py-2 text-sm" />
            </div>
            <div>
              <label className="mb-1 block text-xs font-medium opacity-60">Start Date</label>
              <input name="start_date" type="date" className="w-full rounded-lg border border-[var(--line)] bg-[var(--paper)] px-3 py-2 text-sm" />
            </div>
            <div>
              <label className="mb-1 block text-xs font-medium opacity-60">End Date</label>
              <input name="end_date" type="date" className="w-full rounded-lg border border-[var(--line)] bg-[var(--paper)] px-3 py-2 text-sm" />
            </div>
          </div>
          <Button type="submit" size="sm">Create</Button>
        </form>
      )}

      <div className="space-y-3">
        {seasons.map((season) => (
          <div
            key={season.id}
            className="flex flex-col gap-3 rounded-xl bg-[var(--paper-2)] p-4 sm:flex-row sm:items-center sm:justify-between"
          >
            {editingId === season.id ? (
              <form
                action={async (formData) => {
                  await updateSeasonAction(formData);
                  setEditingId(null);
                }}
                className="flex-1 space-y-3"
              >
                <input type="hidden" name="id" value={season.id} />
                <div className="grid grid-cols-1 gap-2 sm:grid-cols-3">
                  <input
                    name="name"
                    defaultValue={season.name}
                    className="rounded-lg border border-[var(--line)] bg-[var(--paper)] px-2 py-1 text-sm"
                  />
                  <input
                    name="start_date"
                    type="date"
                    defaultValue={season.start_date ?? ""}
                    className="rounded-lg border border-[var(--line)] bg-[var(--paper)] px-2 py-1 text-sm"
                  />
                  <input
                    name="end_date"
                    type="date"
                    defaultValue={season.end_date ?? ""}
                    className="rounded-lg border border-[var(--line)] bg-[var(--paper)] px-2 py-1 text-sm"
                  />
                </div>
                <div className="flex gap-2">
                  <Button type="submit" size="sm" variant="outline">Save</Button>
                  <Button type="button" size="sm" variant="ghost" onClick={() => setEditingId(null)}>Cancel</Button>
                </div>
              </form>
            ) : (
              <>
                <div className="flex items-center gap-3">
                  <div>
                    <div className="flex items-center gap-2">
                      <span className="font-semibold">{season.name}</span>
                      <span className="text-xs opacity-40">{season.id}</span>
                      {season.is_current && (
                        <span className="rounded-full bg-green-500/20 px-2 py-0.5 text-xs font-medium text-green-400">
                          Current
                        </span>
                      )}
                    </div>
                    <p className="mt-0.5 text-xs opacity-50">
                      {season.start_date ?? "No start"} — {season.end_date ?? "No end"}
                    </p>
                  </div>
                </div>
                {isAdmin && (
                  <div className="flex gap-2">
                    {!season.is_current && (
                      <form action={setCurrentSeasonAction}>
                        <input type="hidden" name="seasonId" value={season.id} />
                        <Button type="submit" size="sm" variant="outline">
                          Set Current
                        </Button>
                      </form>
                    )}
                    <Button size="sm" variant="ghost" onClick={() => setEditingId(season.id)}>
                      Edit
                    </Button>
                  </div>
                )}
              </>
            )}
          </div>
        ))}
        {seasons.length === 0 && (
          <p className="py-8 text-center opacity-40">No seasons created yet</p>
        )}
      </div>
    </div>
  );
}
