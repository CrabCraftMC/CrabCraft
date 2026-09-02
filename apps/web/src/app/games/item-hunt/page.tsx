import type { Metadata } from "next";
import dynamic from "next/dynamic";

const BlockHunt = dynamic(() => import("@/components/BlockHunt"));

export const metadata: Metadata = {
  title: "Item Hunt | Daily Minecraft Guessing Game",
  description:
    "Play Item Hunt, a free daily Minecraft item guessing game. Reveal up to six clues, identify the mystery item, and share your result.",
  alternates: {
    canonical: "https://crabcraft.net/games/item-hunt",
  },
  openGraph: {
    title: "Item Hunt | Daily Minecraft Guessing Game",
    description:
      "Reveal up to six clues and identify today's mystery Minecraft item.",
    url: "https://crabcraft.net/games/item-hunt",
    images: ["/logo.png"],
  },
  twitter: {
    card: "summary",
    title: "Item Hunt | Daily Minecraft Guessing Game",
    description:
      "Reveal up to six clues and identify today's mystery Minecraft item.",
  },
};

const jsonLd = {
  "@context": "https://schema.org",
  "@type": "WebApplication",
  name: "Item Hunt",
  alternateName: "Minecraft Item Hunt",
  description:
    "A free daily Minecraft item guessing game with up to six clues.",
  url: "https://crabcraft.net/games/item-hunt",
  applicationCategory: "GameApplication",
  operatingSystem: "Any",
  browserRequirements: "Requires JavaScript",
  isAccessibleForFree: true,
  offers: {
    "@type": "Offer",
    price: "0",
    priceCurrency: "GBP",
  },
  publisher: {
    "@type": "Organization",
    name: "CrabCraft",
    url: "https://crabcraft.net",
  },
};

export default function ItemHuntPage() {
  return (
    <>
      <script
        type="application/ld+json"
        dangerouslySetInnerHTML={{ __html: JSON.stringify(jsonLd) }}
      />
      <BlockHunt kind="item" />
    </>
  );
}
