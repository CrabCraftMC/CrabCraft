import type { Metadata } from "next";
import dynamic from "next/dynamic";

const BlockHunt = dynamic(() => import("@/components/BlockHunt"));

export const metadata: Metadata = {
  title: "Block Hunt | Daily Minecraft Guessing Game",
  description:
    "Play Block Hunt, a free daily Minecraft block guessing game. Use up to six clues to identify the mystery block, then share how quickly you found it.",
  alternates: {
    canonical: "https://crabcraft.net/games/block-hunt",
  },
  openGraph: {
    title: "Block Hunt | Daily Minecraft Guessing Game",
    description:
      "Use up to six clues to identify today's mystery Minecraft block.",
    url: "https://crabcraft.net/games/block-hunt",
    images: ["/logo.png"],
  },
  twitter: {
    card: "summary",
    title: "Block Hunt | Daily Minecraft Guessing Game",
    description:
      "Use up to six clues to identify today's mystery Minecraft block.",
  },
};

const jsonLd = {
  "@context": "https://schema.org",
  "@type": "WebApplication",
  name: "Block Hunt",
  alternateName: "Minecraft Block Hunt",
  description:
    "A free daily Minecraft block guessing game with up to six clues.",
  url: "https://crabcraft.net/games/block-hunt",
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

export default function BlockHuntPage() {
  return (
    <>
      <script
        type="application/ld+json"
        dangerouslySetInnerHTML={{ __html: JSON.stringify(jsonLd) }}
      />
      <BlockHunt kind="block" />
    </>
  );
}
