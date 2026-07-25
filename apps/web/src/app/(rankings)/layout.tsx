import LeaderboardSwitcher from "@/components/LeaderboardSwitcher";

export default function RankingsLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <div className="min-h-screen pt-24 pb-16">
      <div className="container mx-auto px-4">
        <div className="text-center mb-6">
          <LeaderboardSwitcher />
        </div>
        {children}
      </div>
    </div>
  );
}
