import type { Metadata } from "next";
import dynamic from "next/dynamic";

const BlockHunt = dynamic(() => import("@/components/BlockHunt"));

export const metadata: Metadata = {
  title: "Mob Hunt | Daily Minecraft Guessing Game",
  description:
    "Play Mob Hunt, a free daily Minecraft mob guessing game. Work through up to six clues, name the mystery mob, and share your result.",
  alternates: {
    canonical: "https://crabcraft.net/games/mob-hunt",
  },
  openGraph: {
    title: "Mob Hunt | Daily Minecraft Guessing Game",
    description:
      "Identify today's mystery Minecraft mob from up to six clues.",
    url: "https://crabcraft.net/games/mob-hunt",
    images: ["/logo.png"],
  },
  twitter: {
    card: "summary",
    title: "Mob Hunt | Daily Minecraft Guessing Game",
    description:
      "Identify today's mystery Minecraft mob from up to six clues.",
  },
};

const jsonLd = {
  "@context": "https://schema.org",
  "@type": "WebApplication",
  name: "Mob Hunt",
  alternateName: "Minecraft Mob Hunt",
  description:
    "A free daily Minecraft mob guessing game with up to six clues.",
  url: "https://crabcraft.net/games/mob-hunt",
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
