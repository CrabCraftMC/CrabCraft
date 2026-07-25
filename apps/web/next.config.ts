import path from "path";
import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  output: "standalone",
  outputFileTracingRoot: path.join(import.meta.dirname, "../../"),
  experimental: {
    optimizePackageImports: ["react-icons", "gsap", "@gsap/react", "lucide-react"],
  },
  async redirects() {
    return [
      {
        source: "/rgb",
        destination: "/tools/rgb-nickname",
        permanent: true,
      },
      {
        source: "/tools/rgb",
        destination: "/tools/rgb-nickname",
        permanent: true,
      },
      {
        source: "/tools/circle",
        destination: "/tools/circle-generator",
        permanent: true,
      },
    ];
  },
  async headers() {
    const scriptSrc =
      process.env.NODE_ENV === "development"
        ? "'self' 'unsafe-inline' 'unsafe-eval'"
        : "'self' 'unsafe-inline'";
    const csp = `default-src 'self'; script-src ${scriptSrc}; img-src 'self' data: https://mc-heads.net https://cdn.discordapp.com https://map.crabcraft.net https://starlightskins.lunareclipse.studio https://mc-api.io; style-src 'self' 'unsafe-inline'; connect-src 'self' https://api.crabcraft.net; font-src 'self'; frame-ancestors 'none'`;
    return [
      {
        source: "/:path*.webp",
        headers: [
          { key: "Cache-Control", value: "public, max-age=86400, stale-while-revalidate=604800" },
        ],
      },
      {
        source: "/(.*)",
        headers: [
          { key: "X-Content-Type-Options", value: "nosniff" },
          { key: "X-Frame-Options", value: "DENY" },
          {
            key: "Referrer-Policy",
            value: "strict-origin-when-cross-origin",
          },
          {
            key: "Strict-Transport-Security",
            value: "max-age=31536000; includeSubDomains",
          },
          {
            key: "Permissions-Policy",
            value: "camera=(), microphone=(), geolocation=()",
          },
          {
            key: "Content-Security-Policy",
            value: csp,
          },
        ],
      },
    ];
  },
  images: {
    remotePatterns: [
      { protocol: "https", hostname: "mc-heads.net" },
      { protocol: "https", hostname: "map.crabcraft.net" },
      { protocol: "https", hostname: "cdn.discordapp.com" },
      {
        protocol: "https",
        hostname: "starlightskins.lunareclipse.studio",
      },
    ],
    minimumCacheTTL: 2678400,
    formats: ["image/webp"],
    deviceSizes: [640, 828, 1080, 1920],
    imageSizes: [16, 32, 48, 64, 96],
    qualities: [75],
  },
};

export default nextConfig;
