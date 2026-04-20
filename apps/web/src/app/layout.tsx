import type { Metadata } from "next";
import Script from "next/script";
import { Unbounded } from "next/font/google";
import { Geist_Mono } from "next/font/google";
import localFont from "next/font/local";
import { auth, getAvatarUrl } from "@/lib/auth";
import Navbar from "@/components/Navbar";
import Footer from "@/components/Footer";
import GridPattern from "@/components/GridPattern";
import CommandMenu from "@/components/CommandMenu";
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

const minecraft = localFont({
  src: [
    { path: "../../public/fonts/MinecraftRegular-Bmg3.otf", weight: "400", style: "normal" },
    { path: "../../public/fonts/MinecraftBold-nMK1.otf", weight: "700", style: "normal" },
    { path: "../../public/fonts/MinecraftItalic-R8Mo.otf", weight: "400", style: "italic" },
    { path: "../../public/fonts/MinecraftBoldItalic-1y1e.otf", weight: "700", style: "italic" },
  ],
  variable: "--font-mc",
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
      discordId: user.discordId,
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
            __html: `(function(){document.documentElement.classList.add('no-trans');var s=localStorage.getItem('theme');if(s==='dark'||(s===null&&window.matchMedia('(prefers-color-scheme:dark)').matches)){document.documentElement.classList.add('dark')}requestAnimationFrame(function(){requestAnimationFrame(function(){document.documentElement.classList.remove('no-trans')})})})()`,
          }}
        />
        <script
          type="application/ld+json"
          dangerouslySetInnerHTML={{ __html: JSON.stringify(jsonLd) }}
          suppressHydrationWarning
        />
      </head>
      <body className={`${unbounded.variable} ${geistMono.variable} ${minecraft.variable} font-sans antialiased bg-paper transition-colors duration-200 relative`}>
        <div className="fixed inset-0 z-0 pointer-events-none">
          <GridPattern />
        </div>
        <div className="relative z-10 min-h-screen flex flex-col">
          <Navbar user={userData} />
          <main className="flex-1 flex flex-col">{children}</main>
          <Footer />
        </div>
        <CommandMenu />
        {process.env.NODE_ENV === "production" && (
          <Script
            src="https://web.maxmoon.sh/script.js"
            strategy="lazyOnload"
            data-website-id="b47dfe1d-3ed3-49d8-948a-96776107f338"
            data-domains="crabcraft.net,www.crabcraft.net"
          />
        )}
      </body>
    </html>
  );
}
