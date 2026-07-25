import type { Metadata } from "next";
import { Geist_Mono, Unbounded } from "next/font/google";
import { auth, getAvatarUrl } from "@/lib/auth";
import Navbar from "@/components/Navbar";
import Footer from "@/components/Footer";
import GridPattern from "@/components/GridPattern";
import CommandMenu from "@/components/CommandMenu";
import MascotJoin from "@/components/MascotJoin";
import "@/styles/globals.css";

const unbounded = Unbounded({
  subsets: ["latin"],
  variable: "--font-sans",
  display: "swap",
});

const geistMono = Geist_Mono({
  subsets: ["latin"],
  variable: "--font-mono",
  display: "swap",
});

export const metadata: Metadata = {
  title: {
    default: "CrabCraft",
    template: "%s - CrabCraft",
  },
  description:
    "CrabCraft is a whitelisted Minecraft survival server. Apply to join and start your adventure.",
  metadataBase: new URL("https://crabcraft.net"),
  openGraph: {
    type: "website",
    siteName: "CrabCraft",
    images: ["/logo.png"],
  },
  twitter: {
    card: "summary",
  },
  icons: {
    icon: [
      { url: "/favicon.ico", sizes: "any" },
      { url: "/logo.png", type: "image/png" },
    ],
    apple: "/apple-touch-icon.png",
  },
  manifest: "/manifest.webmanifest",
  other: {
    "theme-color": "#f97316",
  },
};

const jsonLd = {
  "@context": "https://schema.org",
  "@graph": [
    {
      "@type": "WebSite",
      name: "CrabCraft",
      url: "https://crabcraft.net",
      description: "A whitelisted Minecraft survival server",
    },
    {
      "@type": "Organization",
      name: "CrabCraft",
      url: "https://crabcraft.net",
      logo: "https://crabcraft.net/logo.png",
      sameAs: [
        "https://www.youtube.com/@CrabCraftMC",
        "https://www.tiktok.com/@playcrabcraft",
        "https://www.instagram.com/crabcraftmc/",
      ],
    },
  ],
};

export default async function RootLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  const session = await auth();

  let userData = null;
  if (session?.user) {
    const user = session.user;
    userData = {
      name: user.name || "User",
      avatarUrl: user.minecraftUuid
        ? `https://mc-heads.net/avatar/${user.minecraftUuid}/56.png`
        : getAvatarUrl(user),
      minecraftUuid: user.minecraftUuid,
      minecraftUsername: user.minecraftUsername,
      role: user.role,
    };
  }

  return (
    <html lang="en" suppressHydrationWarning>
      <head>
        <script
          dangerouslySetInnerHTML={{
            __html: `(function(){document.documentElement.classList.add('no-trans');var s=localStorage.getItem('theme');if(s==='dark'||(s===null&&window.matchMedia('(prefers-color-scheme:dark)').matches)){document.documentElement.classList.add('dark')}requestAnimationFrame(function(){requestAnimationFrame(function(){document.documentElement.classList.remove('no-trans')})})})();(function(){var w=console.warn;console.warn=function(){var m=arguments[0];if(typeof m==='string'&&m.indexOf('THREE.Clock: This module has been deprecated')===0)return;return w.apply(console,arguments)}})()`,
          }}
        />
        <script
          type="application/ld+json"
          dangerouslySetInnerHTML={{ __html: JSON.stringify(jsonLd) }}
          suppressHydrationWarning
        />
      </head>
      <body className={`${unbounded.variable} ${geistMono.variable} font-sans antialiased bg-paper transition-colors duration-200 relative`}>
        <div className="fixed inset-0 z-0 pointer-events-none">
          <GridPattern />
        </div>
        <div className="relative z-10 min-h-screen flex flex-col">
          <Navbar user={userData} />
          <main className="flex-1 flex flex-col">{children}</main>
          <Footer />
        </div>
        <CommandMenu />
        {/* Only nudge signed-out visitors to join — signed-in users already have. */}
        {!userData && <MascotJoin />}
      </body>
    </html>
  );
}
