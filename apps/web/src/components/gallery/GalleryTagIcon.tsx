"use client";

import { useState } from "react";
import Image from "next/image";

export default function GalleryTagIcon({
  emojiName,
  emojiUrl,
  size = 14,
}: {
  emojiName: string | null;
  emojiUrl: string | null;
  size?: number;
}) {
  const [failedUrl, setFailedUrl] = useState<string | null>(null);

  if (emojiUrl && failedUrl !== emojiUrl) {
    return (
      <Image
        src={emojiUrl}
        alt=""
        width={size}
        height={size}
        className="shrink-0 object-contain"
        unoptimized
        onError={() => setFailedUrl(emojiUrl)}
      />
    );
  }

  if (emojiName) {
    return (
      <span aria-hidden="true" className="shrink-0 leading-none">
        {emojiName}
      </span>
    );
  }

  return null;
}
