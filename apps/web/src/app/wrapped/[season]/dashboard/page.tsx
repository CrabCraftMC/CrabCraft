import type { Metadata } from "next";
import Link from "next/link";
import { ChevronLeft } from "lucide-react";
import { getWrappedData } from "@/lib/getWrappedData";
import WrappedContainer from "@/components/wrapped/WrappedContainer";
import WrappedErrorView from "@/components/wrapped/WrappedErrorView";

interface Props {
  params: Promise<{ season: string }>;
}

export const revalidate = 60;

export async function generateMetadata({ params }: Props): Promise<Metadata> {
  const { season } = await params;
  return {
    title: `Wrapped Season ${season} — Dashboard`,
    description: `Detailed Wrapped dashboard for Season ${season}.`,
    alternates: {
      canonical: `https://crabcraft.net/wrapped/${season}/dashboard`,
    },
  };
}

export default async function WrappedDashboardPage({ params }: Props) {
  const { season } = await params;
  const result = await getWrappedData(season);

  if (result.kind !== "ok") {
    return <WrappedErrorView kind={result.kind} />;
  }

  return (
    <div>
      <div className="container mx-auto px-4 pt-20">
        <Link
          href={`/wrapped/${season}`}
          className="inline-flex items-center gap-1 text-sm text-gray-500 transition-colors hover:text-orange-500 dark:text-gray-400"
        >
          <ChevronLeft className="h-4 w-4" aria-hidden />
          Back to story
        </Link>
      </div>
      <WrappedContainer data={result.data} />
    </div>
  );
}
