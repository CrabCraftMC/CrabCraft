import type { Metadata } from "next";
import dynamic from "next/dynamic";
import { notFound } from "next/navigation";
import { getBlockGradientShare } from "@crabcraft/db/queries/web";
import {
  BLOCK_GRADIENT_SHARE_ID_PATTERN,
  BLOCK_GRADIENT_SHARE_VERSION,
  normaliseBlockGradientShareState,
} from "@/lib/blockGradientShare";

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

export default async function BlockGradientPage({
  searchParams,
}: {
  searchParams: Promise<{ share?: string | string[] }>;
}) {
  const { share: shareId } = await searchParams;
  let initialState = null;

  if (shareId !== undefined) {
    if (
      typeof shareId !== "string" ||
      !BLOCK_GRADIENT_SHARE_ID_PATTERN.test(shareId)
    ) {
      notFound();
    }

    const share = await getBlockGradientShare(shareId);
    if (!share || share.version !== BLOCK_GRADIENT_SHARE_VERSION) {
      notFound();
    }
    initialState = normaliseBlockGradientShareState(share.state);
    if (!initialState) notFound();
  }

  return (
    <>
      <script
        type="application/ld+json"
        dangerouslySetInnerHTML={{ __html: JSON.stringify(jsonLd) }}
      />
      <BlockGradient
        initialState={initialState}
        initialShareId={typeof shareId === "string" ? shareId : null}
      />
    </>
  );
}
