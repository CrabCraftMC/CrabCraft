import type { Metadata } from "next";
import { getWrappedData } from "@/lib/getWrappedData";
import WrappedStory from "@/components/wrapped/story/WrappedStory";
import WrappedErrorView from "@/components/wrapped/WrappedErrorView";

interface Props {
  params: Promise<{ season: string }>;
}

export async function generateMetadata({ params }: Props): Promise<Metadata> {
  const { season } = await params;
  return {
    title: `Wrapped Season ${season}`,
    description: `View your personalised CrabCraft Wrapped stats for Season ${season}.`,
    alternates: {
      canonical: `https://crabcraft.net/wrapped/${season}`,
    },
  };
}

export default async function WrappedSeasonPage({ params }: Props) {
  const { season } = await params;
  const result = await getWrappedData(season);

  if (result.kind === "ok") {
    return <WrappedStory data={result.data} />;
  }

  return <WrappedErrorView kind={result.kind} />;
}
