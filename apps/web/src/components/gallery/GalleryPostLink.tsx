"use client";

import type { ComponentProps } from "react";
import { preload } from "react-dom";
import Link from "next/link";
import { useRouter } from "next/navigation";

type GalleryPostLinkProps = Omit<
  ComponentProps<typeof Link>,
  "href" | "prefetch"
> & {
  href: string;
  imageUrl: string;
};

export default function GalleryPostLink({
  href,
  imageUrl,
  onFocus,
  onPointerDown,
  onPointerEnter,
  ...props
}: GalleryPostLinkProps) {
  const router = useRouter();
  const preloadPost = () => {
    router.prefetch(href);
    preload(imageUrl, { as: "image", fetchPriority: "high" });
  };

  return (
    <Link
      {...props}
      href={href}
      onFocus={(event) => {
        preloadPost();
        onFocus?.(event);
      }}
      onPointerDown={(event) => {
        preloadPost();
        onPointerDown?.(event);
      }}
      onPointerEnter={(event) => {
        preloadPost();
        onPointerEnter?.(event);
      }}
    />
  );
}
