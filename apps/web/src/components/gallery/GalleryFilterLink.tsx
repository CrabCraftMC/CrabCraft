"use client";

import type { ComponentProps } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";

type GalleryFilterLinkProps = Omit<
  ComponentProps<typeof Link>,
  "href" | "prefetch"
> & {
  href: string;
};

export default function GalleryFilterLink({
  href,
  onFocus,
  onPointerEnter,
  ...props
}: GalleryFilterLinkProps) {
  const router = useRouter();
  const prefetch = () => router.prefetch(href);

  return (
    <Link
      {...props}
      href={href}
      onFocus={(event) => {
        prefetch();
        onFocus?.(event);
      }}
      onPointerEnter={(event) => {
        prefetch();
        onPointerEnter?.(event);
      }}
    />
  );
}
