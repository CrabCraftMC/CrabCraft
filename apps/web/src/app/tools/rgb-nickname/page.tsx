import type { Metadata } from "next";
import dynamic from "next/dynamic";

const RGBGenerator = dynamic(() => import("@/components/RGBGenerator"));

export const metadata: Metadata = {
  title: "Minecraft RGB Nickname Generator",
  description:
    "Create gradient RGB nicknames for Minecraft. Generate coloured text with MiniMessage, hex codes, and section symbols. Copy the /nick command instantly — free online tool.",
  alternates: {
    canonical: "https://crabcraft.net/tools/rgb-nickname",
  },
  openGraph: {
    title: "Minecraft RGB Nickname Generator - CrabCraft",
    description:
      "Create gradient RGB nicknames for Minecraft. Generate coloured text and copy the /nick command instantly.",
    url: "https://crabcraft.net/tools/rgb-nickname",
    images: ["/logo.png"],
  },
  keywords: [
    "Minecraft",
    "RGB",
    "nickname",
    "gradient",
    "text",
    "color codes",
    "MiniMessage",
    "nick command",
    "coloured name",
  ],
};

const jsonLd = {
  "@context": "https://schema.org",
  "@type": "WebApplication",
  name: "Minecraft RGB Nickname Generator",
  description:
    "Create gradient RGB nicknames for Minecraft. Generate coloured text with MiniMessage, hex codes, and section symbols.",
  url: "https://crabcraft.net/tools/rgb-nickname",
  applicationCategory: "GameApplication",
  operatingSystem: "Any",
  offers: {
    "@type": "Offer",
    price: "0",
    priceCurrency: "USD",
  },
};

export default function RGBPage() {
  return (
    <>
      <script
        type="application/ld+json"
        dangerouslySetInnerHTML={{ __html: JSON.stringify(jsonLd) }}
      />
      <RGBGenerator />
    </>
  );
}
