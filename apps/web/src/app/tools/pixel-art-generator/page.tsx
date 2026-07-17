import type { Metadata } from "next";
import dynamic from "next/dynamic";

const PixelArtGenerator = dynamic(
  () => import("@/components/PixelArtGenerator"),
);

export const metadata: Metadata = {
  title: "Minecraft Pixel Art Generator",
  description:
    "Turn any image into Minecraft pixel art made from real block textures. Upload an image, choose the detail level, and download your block mosaic — free online tool.",
  alternates: {
    canonical: "https://crabcraft.net/tools/pixel-art-generator",
  },
  openGraph: {
    title: "Minecraft Pixel Art Generator - CrabCraft",
    description:
      "Upload an image and turn it into pixel art made from real Minecraft block textures.",
    url: "https://crabcraft.net/tools/pixel-art-generator",
    images: ["/logo.png"],
  },
  keywords: [
    "Minecraft",
    "pixel art generator",
    "Minecraft pixel art",
    "block art",
    "image to blocks",
    "block mosaic",
    "building tool",
  ],
};

const jsonLd = {
  "@context": "https://schema.org",
  "@type": "WebApplication",
  name: "Minecraft Pixel Art Generator",
  description:
    "Turn an uploaded image into pixel art made from Minecraft block textures.",
  url: "https://crabcraft.net/tools/pixel-art-generator",
  applicationCategory: "GameApplication",
  operatingSystem: "Any",
  offers: {
    "@type": "Offer",
    price: "0",
    priceCurrency: "USD",
  },
};

export default function PixelArtGeneratorPage() {
  return (
    <>
      <script
        type="application/ld+json"
        dangerouslySetInnerHTML={{ __html: JSON.stringify(jsonLd) }}
      />
      <PixelArtGenerator />
    </>
  );
}
