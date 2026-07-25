"use client";

import { useState, useTransition } from "react";
import { Button } from "@/components/ui/button";
import { removeChannelAction } from "@/app/settings/actions";
import { SiTwitch, SiTiktok, SiYoutube } from "react-icons/si";
import Squircle from "@/components/Squircle";

const PLATFORMS = [
  { id: "twitch", label: "Twitch", Icon: SiTwitch, color: "text-purple-400" },
  { id: "youtube", label: "YouTube", Icon: SiYoutube, color: "text-red-400" },
  { id: "tiktok", label: "TikTok", Icon: SiTiktok, color: "text-pink-400" },
] as const;

interface Channel {
  id: number;
  platform: string;
  channel_id: string;
  display_name: string | null;
}

export default function ChannelsTab({ channels }: { channels: Channel[] }) {
  const [error, setError] = useState<string | null>(null);
  const [isPending, startTransition] = useTransition();

  const channelByPlatform = new Map(channels.map((c) => [c.platform, c]));

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
        <p className="text-sm opacity-50 mt-1">
          Existing links can be disconnected here. Ask a moderator to verify and add a new channel.
        </p>
      </div>

      {error && (
        <div className="rounded-lg bg-red-500/10 border border-red-500/20 px-4 py-3 text-sm text-red-500">
          {error}
        </div>
      )}

      <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
        {PLATFORMS.map(({ id, label, Icon, color }) => {
          const linked = channelByPlatform.get(id);

          return (
            <Squircle
              key={id}
              cornerRadius={20}
              className="bg-[var(--paper-2)] p-5 flex flex-col items-center text-center"
            >
              <Icon className={`w-8 h-8 mb-3 ${color}`} />
              <p className="text-sm font-semibold mb-1">{label}</p>

              {linked ? (
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
              ) : (
                <Button
                  size="sm"
                  className="mt-2"
                  disabled
                  title="A moderator must verify platform ownership"
                >
                  Verification required
                </Button>
              )}
            </Squircle>
          );
        })}
      </div>
    </div>
  );
}
