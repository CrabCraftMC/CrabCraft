import NextAuth from "next-auth";
import Discord from "next-auth/providers/discord";
import { cache } from "react";
import { getUserForAuth, updateOnLogin } from "@crabcraft/db/queries/auth";
import { fetchPlayerName } from "@crabcraft/shared/mojang";

declare module "next-auth" {
  interface Session {
    user: {
      id: string;
      name?: string | null;
      image?: string | null;
      discordId: string;
      minecraftUuid: string | null;
      minecraftUsername: string | null;
      minecraftNickname: string | null;
      role: string;
    };
  }
}

declare module "@auth/core/jwt" {
  interface JWT {
    discordId?: string;
    minecraftUuid?: string | null;
    minecraftUsername?: string | null;
    minecraftNickname?: string | null;
    role?: string;
  }
}

function clearLocalIdentity(token: {
  minecraftUuid?: string | null;
  minecraftUsername?: string | null;
  minecraftNickname?: string | null;
  role?: string;
}) {
  token.minecraftUuid = null;
  token.minecraftUsername = null;
  token.minecraftNickname = null;
  token.role = "unverified";
}

const {
  handlers,
  auth: uncachedAuth,
} = NextAuth({
  trustHost: true,
  useSecureCookies: process.env.NODE_ENV === "production",
  providers: [
    Discord({
      clientId: process.env.AUTH_DISCORD_ID!,
      clientSecret: process.env.AUTH_DISCORD_SECRET!,
      authorization:
        "https://discord.com/api/oauth2/authorize?scope=identify",
    }),
  ],
  pages: {
    signIn: "/login",
  },
  callbacks: {
    async jwt({ token, account, profile }) {
      if (account && profile) {
        token.discordId = profile.id as string;

        try {
          const user = await getUserForAuth(profile.id as string);
          if (user) {
            // Refresh Minecraft username from Mojang if they have a linked account
            const freshMcName = user.minecraft_uuid
              ? await fetchPlayerName(user.minecraft_uuid)
              : null;
            const mcName = freshMcName && freshMcName !== "Unknown" ? freshMcName : user.minecraft_username;

            token.minecraftUuid = user.minecraft_uuid;
            token.minecraftUsername = mcName;
            token.minecraftNickname = user.nickname;
            token.role = user.role;
            await updateOnLogin(
              profile.id as string,
              profile.username as string,
              mcName,
            );
          } else {
            clearLocalIdentity(token);
          }
        } catch (err) {
          console.error("[auth] JWT callback failed during sign-in:", err);
          clearLocalIdentity(token);
        }
      } else if (token.discordId) {
        // Refresh linked identity and permissions on every later request.
        try {
          const user = await getUserForAuth(token.discordId);
          if (user) {
            token.minecraftUuid = user.minecraft_uuid;
            token.minecraftUsername = user.minecraft_username;
            token.minecraftNickname = user.nickname;
            token.role = user.role;
          } else {
            clearLocalIdentity(token);
          }
        } catch (err) {
          console.error("[auth] JWT role refresh failed:", err);
          clearLocalIdentity(token);
        }
      }

      return token;
    },
    async session({ session, token }) {
      session.user.discordId = token.discordId!;
      session.user.minecraftUuid = token.minecraftUuid ?? null;
      session.user.minecraftUsername = token.minecraftUsername ?? null;
      session.user.minecraftNickname = token.minecraftNickname ?? null;
      session.user.role = token.role ?? "unverified";
      return session;
    },
  },
});

export { handlers };
export const auth = cache(uncachedAuth);

export function getAvatarUrl(user: {
  discordId: string;
  image?: string | null;
}): string {
  if (user.image) {
    return user.image;
  }
  const index = (BigInt(user.discordId) >> BigInt(22)) % BigInt(6);
  return `https://cdn.discordapp.com/embed/avatars/${index}.png`;
}
