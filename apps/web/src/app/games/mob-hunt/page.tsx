import type { Metadata } from "next";
import dynamic from "next/dynamic";

const BlockHunt = dynamic(() => import("@/components/BlockHunt"));

export const metadata: Metadata = {
  title: "Minecraft Mob Hunt",
  description:
    "Guess the mystery Minecraft mob from six progressively easier clues in CrabCraft's daily mob game.",
  alternates: {
    canonical: "https://crabcraft.net/games/mob-hunt",
  },
  openGraph: {
    title: "Minecraft Mob Hunt - CrabCraft",
    description:
      "Six clues. One mystery Minecraft mob. See how quickly you can find it.",
    url: "https://crabcraft.net/games/mob-hunt",
    images: ["/logo.png"],
  },
  keywords: [
    "Minecraft",
    "mob guessing game",
    "Minecraft quiz",
    "daily game",
    "Minecraft mobs",
  ],
};

const jsonLd = {
  "@context": "https://schema.org",
  "@type": "Game",
  name: "Minecraft Mob Hunt",
  description:
    "A daily Minecraft mob guessing game with six progressively easier clues.",
  url: "https://crabcraft.net/games/mob-hunt",
  gamePlatform: "Web browser",
  applicationCategory: "GameApplication",
};

export default function MobHuntPage() {
  return (
    <>
      <script
        type="application/ld+json"
        dangerouslySetInnerHTML={{ __html: JSON.stringify(jsonLd) }}
      />
      <BlockHunt kind="mob" />
    </>
  );
}
