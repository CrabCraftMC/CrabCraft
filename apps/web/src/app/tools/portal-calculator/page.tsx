import type { Metadata } from "next";
import dynamic from "next/dynamic";

const NetherPortalCalculator = dynamic(
  () => import("@/components/NetherPortalCalculator")
);

export const metadata: Metadata = {
  title: "Minecraft Nether Portal Coordinates",
  description:
    "Convert coordinates between the Overworld and Nether in Minecraft. Bidirectional calculator with portal linking guide — free online tool.",
  alternates: {
    canonical: "https://crabcraft.net/tools/portal-calculator",
  },
  openGraph: {
    title: "Minecraft Nether Portal Coordinates - CrabCraft",
    description:
      "Convert coordinates between the Overworld and Nether in Minecraft. Bidirectional calculator with portal linking guide.",
    url: "https://crabcraft.net/tools/portal-calculator",
    images: ["/logo.png"],
  },
  keywords: [
    "Minecraft",
    "nether portal calculator",
    "coordinate converter",
    "overworld to nether",
    "nether to overworld",
    "portal linking",
    "nether coordinates",
    "portal calculator",
    "building tool",
  ],
};

const jsonLd = {
  "@context": "https://schema.org",
  "@type": "WebApplication",
  name: "Minecraft Nether Portal Coordinates",
  description:
    "Convert coordinates between the Overworld and Nether in Minecraft. Bidirectional calculator with portal linking guide.",
  url: "https://crabcraft.net/tools/portal-calculator",
  applicationCategory: "GameApplication",
  operatingSystem: "Any",
  offers: {
    "@type": "Offer",
    price: "0",
    priceCurrency: "USD",
  },
};

export default function NetherPortalPage() {
  return (
    <>
      <script
        type="application/ld+json"
        dangerouslySetInnerHTML={{ __html: JSON.stringify(jsonLd) }}
      />
      <NetherPortalCalculator />
    </>
  );
}
