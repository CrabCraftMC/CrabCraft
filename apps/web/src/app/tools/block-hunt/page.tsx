import type { Metadata } from "next";
import dynamic from "next/dynamic";

const BlockHunt = dynamic(() => import("@/components/BlockHunt"));

export const metadata: Metadata = {
  title: "Minecraft Block Hunt",
  description:
    "Guess the mystery Minecraft block from six progressively easier technical clues in CrabCraft's daily block game.",
  alternates: {
    canonical: "https://crabcraft.net/tools/block-hunt",
  },
  openGraph: {
    title: "Minecraft Block Hunt - CrabCraft",
    description:
      "Six clues. One mystery Minecraft block. See how quickly you can find it.",
    url: "https://crabcraft.net/tools/block-hunt",
    images: ["/logo.png"],
  },
  keywords: [
    "Minecraft",
    "block guessing game",
    "Minecraft quiz",
    "daily game",
    "Minecraft blocks",
    "block properties",
  ],
};

const jsonLd = {
  "@context": "https://schema.org",
  "@type": "Game",
  name: "Minecraft Block Hunt",
  description:
    "A daily Minecraft block guessing game with six progressively easier technical clues.",
  url: "https://crabcraft.net/tools/block-hunt",
  gamePlatform: "Web browser",
  applicationCategory: "GameApplication",
};

export default function BlockHuntPage() {
  return (
    <>
      <script
        type="application/ld+json"
        dangerouslySetInnerHTML={{ __html: JSON.stringify(jsonLd) }}
      />
      <BlockHunt />
    </>
  );
}
