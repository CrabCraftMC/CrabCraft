import type { Metadata } from "next";
import Link from "next/link";
import { PrivacySettingsButton } from "@/components/PrivacyConsent";

export const metadata: Metadata = {
  title: "Privacy policy",
  description: "How CrabCraft uses information, browser storage and advertising, and how to manage your choices.",
  alternates: { canonical: "https://crabcraft.net/privacy" },
};

export default function PrivacyPage() {
  return (
    <article className="mx-auto w-full max-w-3xl px-4 pt-24 pb-16">
      <header className="mb-10">
        <p className="mb-3 text-xs font-bold uppercase tracking-widest text-orange-500">Information</p>
        <h1 className="text-3xl font-bold sm:text-4xl">Privacy policy</h1>
        <p className="mt-3 text-sm text-gray-500 dark:text-gray-400">Last updated 14 November 2026</p>
      </header>

      <div className="space-y-8 text-sm leading-7 text-gray-700 dark:text-gray-300 [&_h2]:mb-2 [&_h2]:text-lg [&_h2]:font-bold [&_h2]:text-gray-900 dark:[&_h2]:text-gray-100 [&_a]:underline [&_a]:underline-offset-4 [&_a]:decoration-orange-500/60">
        <section>
          <h2>Who we are</h2>
          <p>
            CrabCraft is a Crab Studios project. This notice covers crabcraft.net.
            For privacy questions or requests, contact the CrabCraft team at{" "}
            <a href="mailto:privacy@crabcraft.net">privacy@crabcraft.net</a>.
          </p>
        </section>

        <section>
          <h2>Information used to run the site</h2>
          <p>
            Discord sign-in provides your Discord ID, username and avatar, not your email.
            We use these with your linked Minecraft identity, role and login records to
            provide your account. Session and security cookies support sign-in. Application
            answers and moderation records are restricted to authorised people.
          </p>
          <p className="mt-3">
            Public community pages display player names, statistics, awards, gallery
            posts and recent public in-game chat. Hosting and security services process
            connection information; player search uses temporary IP-based rate limits.
            We use this information to provide requested services and for our legitimate
            interests in running, protecting and moderating the community.
          </p>
        </section>

        <section>
          <h2>Tools and browser storage</h2>
          <p>
            Tool settings, theme preferences and game progress are saved in your browser
            until you clear them. Pixel-art images are processed on your device, not uploaded.
            Creating a gradient share link saves its colours, blocks and settings on our
            server; anyone with the link can view it. Shared recipes have no automatic expiry.
          </p>
        </section>

        <section>
          <h2>Analytics and adverts</h2>
          <p>
            Our cookie-free Umami analytics service records page visits, feature
            interactions and performance information to help us improve the site. It is
            served from web.maxmoon.sh and runs by default. Umami processes IP addresses
            and browser information to group visits using a rotating identifier.
            You can switch analytics off in privacy settings; existing opt-outs are respected.
          </p>
          <p className="mt-3">
            Google AdSense adverts appear only for signed-out visitors on the{" "}
            <Link href="/tools/block-gradient">block gradient generator</Link>.
            They require your advertising permission and any additional choices requested
            by Google&apos;s consent manager. Google and its advertising partners may process
            identifiers, IP addresses, device information and advertising interactions,
            and use cookies. Personalised advertising also depends on your consent choices.
            See <a href="https://policies.google.com/technologies/partner-sites">how Google uses information from partner sites</a>.
          </p>
          <p className="mt-3">
            Accept and Deny in the banner apply to advertising only. Denying adverts does
            not change your analytics setting or restrict the tools.
            Advertising consent expires after 180 days. Advertising refusals and analytics
            preferences remain until you change your choice or clear browser storage.
            You can withdraw advertising consent or opt out of analytics at any
            time; withdrawal does not undo earlier lawful processing.
          </p>
          <PrivacySettingsButton className="mt-3 cursor-pointer font-semibold text-orange-600 underline underline-offset-4 dark:text-orange-400">
            Change privacy and cookie settings
          </PrivacySettingsButton>
        </section>

        <section>
          <h2>Providers and retention</h2>
          <p>
            Hosting, analytics, Discord, media services and Google process information needed
            for the features they provide. Their processing may take place outside your
            country. Account and community records support membership, publication,
            moderation and legal obligations; retention depends on those purposes.
            Contact us about deleting records or shared content. External services have
            their own privacy notices and retention arrangements.
          </p>
        </section>

        <section>
          <h2>Your rights</h2>
          <p>
            Depending on your location, you can request access, correction, deletion or a
            portable copy of your information, restrict processing, or object to processing
            based on legitimate interests. California residents can also opt out of sale or
            sharing for cross-context advertising. We do not charge for access to our tools
            or treat you differently for making a privacy request.
          </p>
          <PrivacySettingsButton optOut className="mt-3 cursor-pointer font-semibold text-orange-600 underline underline-offset-4 dark:text-orange-400">
            Do not sell or share my personal information
          </PrivacySettingsButton>
          <p className="mt-3">
            Email <a href="mailto:privacy@crabcraft.net">privacy@crabcraft.net</a> to exercise
            your rights; we may need to verify your identity. You can complain to the{" "}
            <a href="https://ico.org.uk/make-a-complaint/">UK Information Commissioner</a>{" "}
            or your local data protection authority. We will update this notice when our
            practices change.
          </p>
        </section>
      </div>
    </article>
  );
}
