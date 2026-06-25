import type { Metadata } from "next";
import dynamic from "next/dynamic";

const BeaconCalculator = dynamic(() => import("@/components/BeaconCalculator"));

export const metadata: Metadata = {
  title: "Minecraft Beacon Calculator",
  description:
    "Calculate Minecraft beacon pyramid blocks, items, layers, and range — free online tool.",
  alternates: {
    canonical: "https://crabcraft.net/tools/beacon-calculator",
  },
  openGraph: {
    title: "Minecraft Beacon Calculator - CrabCraft",
    description:
      "Calculate beacon pyramid blocks, items, layers, and range.",
    url: "https://crabcraft.net/tools/beacon-calculator",
    images: ["/logo.png"],
  },
  keywords: [
    "Minecraft",
    "beacon calculator",
    "beacon pyramid",
    "pyramid blocks",
    "beacon range",
    "material calculator",
    "building tool",
  ],
};

const jsonLd = {
  "@context": "https://schema.org",
  "@type": "WebApplication",
  name: "Minecraft Beacon Calculator",
  description:
    "Calculate Minecraft beacon pyramid blocks, items, layers, and range.",
  url: "https://crabcraft.net/tools/beacon-calculator",
  applicationCategory: "GameApplication",
  operatingSystem: "Any",
  offers: {
    "@type": "Offer",
    price: "0",
    priceCurrency: "USD",
  },
};

export default function BeaconCalculatorPage() {
  return (
    <>
      <script
        type="application/ld+json"
        dangerouslySetInnerHTML={{ __html: JSON.stringify(jsonLd) }}
      />
      <BeaconCalculator />
    </>
  );
}
