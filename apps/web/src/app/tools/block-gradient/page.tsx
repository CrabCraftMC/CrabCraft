import type { Metadata } from "next";
import dynamic from "next/dynamic";

const BlockGradient = dynamic(() => import("@/components/BlockGradient"));

export const metadata: Metadata = {
  title: "Minecraft Block Gradient Generator",
  description:
    "Create smooth Minecraft block gradients for building. Pick colours or blocks, adjust randomness, and preview a wall of blocks with accurate OkLAB colour matching — free online tool.",
  alternates: {
    canonical: "https://crabcraft.net/tools/block-gradient",
  },
  openGraph: {
    title: "Minecraft Block Gradient Generator - CrabCraft",
    description:
      "Create smooth Minecraft block gradients for building. Pick colours or blocks and generate a gradient palette.",
    url: "https://crabcraft.net/tools/block-gradient",
    images: ["/logo.png"],
  },
  keywords: [
    "Minecraft",
    "block gradient",
    "gradient generator",
    "building",
    "palette",
    "blocks",
    "colour matching",
    "OkLAB",
    "wall gradient",
  ],
};

const jsonLd = {
  "@context": "https://schema.org",
  "@type": "WebApplication",
  name: "Minecraft Block Gradient Generator",
  description:
    "Create smooth Minecraft block gradients for building. Pick colours or blocks, adjust randomness, and preview a wall of blocks with accurate colour matching.",
  url: "https://crabcraft.net/tools/block-gradient",
  applicationCategory: "GameApplication",
  operatingSystem: "Any",
  offers: {
    "@type": "Offer",
    price: "0",
    priceCurrency: "USD",
  },
};

export default function BlockGradientPage() {
  return (
    <>
      <script
        type="application/ld+json"
        dangerouslySetInnerHTML={{ __html: JSON.stringify(jsonLd) }}
      />
      <BlockGradient />
    </>
  );
}
