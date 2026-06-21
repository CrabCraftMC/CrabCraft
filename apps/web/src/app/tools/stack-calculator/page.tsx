import type { Metadata } from "next";
import dynamic from "next/dynamic";

const StackCalculator = dynamic(() => import("@/components/StackCalculator"));

export const metadata: Metadata = {
  title: "Minecraft Stack Calculator",
  description:
    "Convert Minecraft item counts into stacks, shulker boxes, double chests, and split materials between builders — free online tool.",
  alternates: {
    canonical: "https://crabcraft.net/tools/stack-calculator",
  },
  openGraph: {
    title: "Minecraft Stack Calculator - CrabCraft",
    description:
      "Convert item counts into stacks, shulker boxes, double chests, and builder splits.",
    url: "https://crabcraft.net/tools/stack-calculator",
    images: ["/logo.png"],
  },
  keywords: [
    "Minecraft",
    "stack calculator",
    "shulker calculator",
    "item calculator",
    "material calculator",
    "storage calculator",
    "double chest",
    "building tool",
  ],
};

const jsonLd = {
  "@context": "https://schema.org",
  "@type": "WebApplication",
  name: "Minecraft Stack Calculator",
  description:
    "Convert Minecraft item counts into stacks, shulker boxes, double chests, and builder splits.",
  url: "https://crabcraft.net/tools/stack-calculator",
  applicationCategory: "GameApplication",
  operatingSystem: "Any",
  offers: {
    "@type": "Offer",
    price: "0",
    priceCurrency: "USD",
  },
};

export default function StackCalculatorPage() {
  return (
    <>
      <script
        type="application/ld+json"
        dangerouslySetInnerHTML={{ __html: JSON.stringify(jsonLd) }}
      />
      <StackCalculator />
    </>
  );
}
