import type { Metadata } from "next";

export const metadata: Metadata = {
  title: "Privacy policy",
  description: "How CrabCraft uses information, browser storage and analytics, and how to exercise your privacy rights.",
  alternates: { canonical: "https://www.crabcraft.net/privacy" },
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
          <h2>Analytics</h2>
          <p>
            Our cookie-free Umami analytics service records page visits, feature
            interactions and performance information to help us improve the site. It is
            served from web.maxmoon.sh and runs by default. Umami processes IP addresses
            and browser information to group visits using a rotating identifier.
          </p>
        </section>

        <section>
          <h2>Providers and retention</h2>
          <p>
            Hosting, analytics, Discord and media services process information needed
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
            based on legitimate interests. We do not charge for access to our tools
            or treat you differently for making a privacy request.
          </p>
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
