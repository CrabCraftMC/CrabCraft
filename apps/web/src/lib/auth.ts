import NextAuth from "next-auth";
import Discord from "next-auth/providers/discord";
import { getUserForAuth, updateLastLogin } from "@crabcraft/db/queries/auth";

declare module "next-auth" {
  interface Session {
    user: {
      id: string;
      name?: string | null;
      image?: string | null;
      discordId: string;
      minecraftUuid: string | null;
      minecraftUsername: string | null;
      isAdmin: boolean;
    };
  }
}

declare module "@auth/core/jwt" {
  interface JWT {
    discordId?: string;
    minecraftUuid?: string | null;
    minecraftUsername?: string | null;
    isAdmin?: boolean;
  }
}

export const { handlers, auth, signIn, signOut } = NextAuth({
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
            token.minecraftUuid = user.minecraft_uuid;
            token.minecraftUsername = user.minecraft_username;
            token.isAdmin = user.is_admin;
            await updateLastLogin(profile.id as string);
          } else {
            token.minecraftUuid = null;
            token.minecraftUsername = null;
            token.isAdmin = false;
          }
        } catch {
          token.minecraftUuid = null;
          token.minecraftUsername = null;
          token.isAdmin = false;
        }
      }
      return token;
    },
    async session({ session, token }) {
      session.user.discordId = token.discordId!;
      session.user.minecraftUuid = token.minecraftUuid ?? null;
      session.user.minecraftUsername = token.minecraftUsername ?? null;
      session.user.isAdmin = token.isAdmin ?? false;
      return session;
    },
  },
});

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
