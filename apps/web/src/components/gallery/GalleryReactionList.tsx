import Image from "next/image";
import type { GalleryReaction } from "@/data/gallery";

export default function GalleryReactionList({
  reactions,
  limit,
}: {
  reactions: GalleryReaction[];
  limit?: number;
}) {
  if (reactions.length === 0) return null;
  const visible = limit ? reactions.slice(0, limit) : reactions;
  const remaining = reactions.length - visible.length;

  return (
    <div
      className="flex flex-wrap items-center gap-1.5"
      aria-label="Discord reactions"
    >
      {visible.map((reaction) => (
        <span
          key={reaction.key}
          title={`${reaction.name}: ${reaction.count}`}
          className="inline-flex min-h-7 items-center gap-1.5 rounded-lg border border-line bg-paper px-2 py-1 text-xs font-bold text-gray-700 dark:text-gray-300"
        >
          {reaction.emojiUrl ? (
            <Image
              src={reaction.emojiUrl}
              alt={`:${reaction.name}:`}
              width={18}
              height={18}
              className="h-[18px] w-[18px] object-contain"
              unoptimized
            />
          ) : (
            <span aria-hidden="true" className="text-base leading-none">
              {reaction.name}
            </span>
          )}
          <span>{reaction.count}</span>
        </span>
      ))}
      {remaining > 0 ? (
        <span className="text-xs font-bold text-gray-500 dark:text-gray-400">
          +{remaining}
        </span>
      ) : null}
    </div>
  );
}
