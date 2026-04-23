"use client";

import { useState, useTransition } from "react";
import { Button } from "@/components/ui/button";
import { addChannelAction, removeChannelAction } from "@/app/settings/actions";
import { SiTwitch, SiTiktok, SiYoutube } from "react-icons/si";
import Squircle from "@/components/Squircle";

const PLATFORMS = [
  { id: "twitch", label: "Twitch", Icon: SiTwitch, color: "text-purple-400" },
  { id: "youtube", label: "YouTube", Icon: SiYoutube, color: "text-red-400" },
  { id: "tiktok", label: "TikTok", Icon: SiTiktok, color: "text-pink-400" },
] as const;

function parseChannelInput(platform: string, input: string): string {
  const trimmed = input.trim();
  try {
    const url = new URL(trimmed.startsWith("http") ? trimmed : `https://${trimmed}`);
    const path = url.pathname.replace(/^\/+|\/+$/g, "");
    if (platform === "twitch" && url.hostname.includes("twitch.tv")) {
      return path.split("/")[0];
    }
    if (platform === "youtube" && url.hostname.includes("youtube.com")) {
      if (path.startsWith("@")) return path.split("/")[0];
      if (path.startsWith("channel/")) return path.split("/")[1];
      return path.split("/")[0];
    }
    if (platform === "tiktok" && url.hostname.includes("tiktok.com")) {
      const segment = path.split("/")[0];
      return segment.startsWith("@") ? segment : `@${segment}`;
    }
  } catch {
    // Not a URL — treat as raw channel ID
  }
  return trimmed;
}

interface Channel {
  id: number;
  platform: string;
  channel_id: string;
  display_name: string | null;
}

export default function ChannelsTab({ channels }: { channels: Channel[] }) {
  const [error, setError] = useState<string | null>(null);
  const [isPending, startTransition] = useTransition();
  const [linkingPlatform, setLinkingPlatform] = useState<string | null>(null);

  const channelByPlatform = new Map(channels.map((c) => [c.platform, c]));

  const handleAdd = (formData: FormData) => {
    setError(null);
    const platform = formData.get("platform") as string;
    const rawInput = formData.get("channelInput") as string;
    const displayName = formData.get("displayName") as string;

    const channelId = parseChannelInput(platform, rawInput);
    const submitData = new FormData();
    submitData.set("platform", platform);
    submitData.set("channelId", channelId);
    if (displayName?.trim()) submitData.set("displayName", displayName.trim());

    startTransition(async () => {
      const result = await addChannelAction(submitData);
      if (!result.success) {
        setError(result.error);
      } else {
        setLinkingPlatform(null);
      }
    });
  };

  const handleRemove = (platform: string, channelId: string) => {
    setError(null);
    const formData = new FormData();
    formData.set("platform", platform);
    formData.set("channelId", channelId);
    startTransition(async () => {
      const result = await removeChannelAction(formData);
      if (!result.success) setError(result.error);
    });
  };

  return (
    <div className="space-y-6">
      <div>
        <h2 className="text-xl font-bold">Linked Channels</h2>
        <p className="text-sm opacity-50 mt-1">Link your channels to receive the live tag automatically in-game</p>
      </div>

      {error && (
        <div className="rounded-lg bg-red-500/10 border border-red-500/20 px-4 py-3 text-sm text-red-500">
          {error}
        </div>
      )}

      <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
        {PLATFORMS.map(({ id, label, Icon, color }) => {
          const linked = channelByPlatform.get(id);
          const isLinking = linkingPlatform === id;

          return (
            <Squircle
              key={id}
              cornerRadius={20}
              className="bg-[var(--paper-2)] p-5 flex flex-col items-center text-center"
            >
              <Icon className={`w-8 h-8 mb-3 ${color}`} />
              <p className="text-sm font-semibold mb-1">{label}</p>

              {linked && !isLinking ? (
                <>
                  <p className="text-xs opacity-60 truncate max-w-full mb-3">
                    {linked.display_name || linked.channel_id}
                  </p>
                  <Button
                    size="sm"
                    variant="outline"
                    disabled={isPending}
                    onClick={() => handleRemove(linked.platform, linked.channel_id)}
                  >
                    Disconnect
                  </Button>
                </>
              ) : isLinking ? (
                <form action={handleAdd} className="w-full space-y-3 mt-2">
                  <input type="hidden" name="platform" value={id} />
                  <input
                    name="channelInput"
                    placeholder={`${label} URL or username`}
                    required
                    autoFocus
                    className="w-full rounded-lg border border-[var(--line)] bg-[var(--paper)] px-3 py-2 text-sm text-center"
                  />
                  <input
                    name="displayName"
                    placeholder="Display name (optional)"
                    className="w-full rounded-lg border border-[var(--line)] bg-[var(--paper)] px-3 py-2 text-sm text-center"
                  />
                  <div className="flex gap-2 justify-center">
                    <Button type="submit" size="sm" disabled={isPending}>
                      {isPending ? "Linking..." : "Link"}
                    </Button>
                    <Button type="button" size="sm" variant="ghost" onClick={() => setLinkingPlatform(null)}>
                      Cancel
                    </Button>
                  </div>
                </form>
              ) : (
                <Button
                  size="sm"
                  className="mt-2"
                  onClick={() => setLinkingPlatform(id)}
                >
                  Connect
                </Button>
              )}
            </Squircle>
          );
        })}
      </div>
    </div>
  );
}
