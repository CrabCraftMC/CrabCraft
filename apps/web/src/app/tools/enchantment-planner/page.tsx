import type { Metadata } from "next";
import dynamic from "next/dynamic";

const EnchantmentPlanner = dynamic(
  () => import("@/components/EnchantmentPlanner")
);

export const metadata: Metadata = {
  title: "Minecraft Enchantment Planner",
  description:
    "Plan compatible Minecraft enchantments for swords, spears, tools, bows, armor, tridents, maces, elytra, and fishing rods — free online tool.",
  alternates: {
    canonical: "https://crabcraft.net/tools/enchantment-planner",
  },
  openGraph: {
    title: "Minecraft Enchantment Planner - CrabCraft",
    description:
      "Build compatible Minecraft enchantment loadouts with item icons, conflicts, max levels, and commands.",
    url: "https://crabcraft.net/tools/enchantment-planner",
    images: ["/logo.png"],
  },
  keywords: [
    "Minecraft",
    "enchantment planner",
    "enchantment calculator",
    "enchantments",
    "best enchantments",
    "Minecraft tools",
    "armor enchantments",
    "weapon enchantments",
  ],
};

const jsonLd = {
  "@context": "https://schema.org",
  "@type": "WebApplication",
  name: "Minecraft Enchantment Planner",
  description:
    "Plan compatible Minecraft enchantments for gear with conflicts, max levels, and commands.",
  url: "https://crabcraft.net/tools/enchantment-planner",
  applicationCategory: "GameApplication",
  operatingSystem: "Any",
  offers: {
    "@type": "Offer",
    price: "0",
    priceCurrency: "USD",
  },
};

export default function EnchantmentPlannerPage() {
  return (
    <>
      <script
        type="application/ld+json"
        dangerouslySetInnerHTML={{ __html: JSON.stringify(jsonLd) }}
      />
      <EnchantmentPlanner />
    </>
  );
}
