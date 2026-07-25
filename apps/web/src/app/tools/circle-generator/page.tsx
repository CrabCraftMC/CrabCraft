import type { Metadata } from "next";
import dynamic from "next/dynamic";

const CircleGenerator = dynamic(() => import("@/components/CircleGenerator"));

export const metadata: Metadata = {
  title: "Minecraft Circle & Oval Generator",
  description:
    "Generate pixel-perfect Minecraft circles and ovals for building. Set width and height, choose outline, filled, or thick modes — free online tool.",
  alternates: {
    canonical: "https://crabcraft.net/tools/circle-generator",
  },
  openGraph: {
    title: "Minecraft Circle & Oval Generator - CrabCraft",
    description:
      "Generate pixel-perfect Minecraft circles and ovals for building. Set width and height, choose outline, filled, or thick modes.",
    url: "https://crabcraft.net/tools/circle-generator",
    images: ["/logo.png"],
  },
  keywords: [
    "Minecraft",
    "circle generator",
    "oval generator",
    "pixel circle",
    "pixel oval",
    "building",
    "block circle",
    "outline",
    "filled circle",
    "building tool",
  ],
};

const jsonLd = {
  "@context": "https://schema.org",
  "@type": "WebApplication",
  name: "Minecraft Circle & Oval Generator",
  description:
    "Generate pixel-perfect Minecraft circles and ovals for building. Set width and height, choose outline, filled, or thick modes.",
  url: "https://crabcraft.net/tools/circle-generator",
  applicationCategory: "GameApplication",
  operatingSystem: "Any",
  offers: {
    "@type": "Offer",
    price: "0",
    priceCurrency: "USD",
  },
};

export default function CirclePage() {
  return (
    <>
      <script
        type="application/ld+json"
        dangerouslySetInnerHTML={{ __html: JSON.stringify(jsonLd) }}
      />
      <CircleGenerator />
    </>
  );
}
