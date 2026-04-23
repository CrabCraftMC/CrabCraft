import { auth } from "@/lib/auth";

export default async function SettingsLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  // Auth redirect is handled by middleware to preserve query params
  await auth();
  return <>{children}</>;
}
