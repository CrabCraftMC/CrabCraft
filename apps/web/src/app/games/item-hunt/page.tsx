import type { Metadata } from "next";
import dynamic from "next/dynamic";

const BlockHunt = dynamic(() => import("@/components/BlockHunt"));

export const metadata: Metadata = {
  title: "Minecraft Item Hunt",
  description:
    "Guess the mystery Minecraft item from six progressively easier clues in CrabCraft's daily item game.",
  alternates: {
    canonical: "https://crabcraft.net/games/item-hunt",
  },
  openGraph: {
    title: "Minecraft Item Hunt - CrabCraft",
    description:
      "Six clues. One mystery Minecraft item. See how quickly you can find it.",
    url: "https://crabcraft.net/games/item-hunt",
    images: ["/logo.png"],
  },
  keywords: [
    "Minecraft",
    "item guessing game",
    "Minecraft quiz",
    "daily game",
    "Minecraft items",
  ],
};

const jsonLd = {
  "@context": "https://schema.org",
  "@type": "Game",
  name: "Minecraft Item Hunt",
  description:
    "A daily Minecraft item guessing game with six progressively easier clues.",
  url: "https://crabcraft.net/games/item-hunt",
  gamePlatform: "Web browser",
  applicationCategory: "GameApplication",
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
