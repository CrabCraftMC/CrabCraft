import type { Metadata } from "next";
import dynamic from "next/dynamic";

const XPCalculator = dynamic(() => import("@/components/XPCalculator"));

export const metadata: Metadata = {
  title: "Minecraft XP Levels",
  description:
    "Calculate Minecraft XP needed between levels, including enchanting bottle estimates and target level totals — free online tool.",
  alternates: {
    canonical: "https://crabcraft.net/tools/xp-calculator",
  },
  openGraph: {
    title: "Minecraft XP Levels - CrabCraft",
    description:
      "Calculate XP needed between Minecraft levels with bottle estimates and target totals.",
    url: "https://crabcraft.net/tools/xp-calculator",
    images: ["/logo.png"],
  },
  keywords: [
    "Minecraft",
    "XP calculator",
    "experience calculator",
    "level calculator",
    "enchanting",
    "bottle o enchanting",
    "XP levels",
  ],
};

const jsonLd = {
  "@context": "https://schema.org",
  "@type": "WebApplication",
  name: "Minecraft XP Levels",
  description:
    "Calculate Minecraft XP needed between levels, including enchanting bottle estimates and target level totals.",
  url: "https://crabcraft.net/tools/xp-calculator",
  applicationCategory: "GameApplication",
  operatingSystem: "Any",
  offers: {
    "@type": "Offer",
    price: "0",
    priceCurrency: "USD",
  },
};

export default function XPCalculatorPage() {
  return (
    <>
      <script
        type="application/ld+json"
        dangerouslySetInnerHTML={{ __html: JSON.stringify(jsonLd) }}
      />
      <XPCalculator />
    </>
  );
}
