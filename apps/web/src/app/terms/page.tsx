import type { Metadata } from "next";
import Link from "next/link";

export const metadata: Metadata = {
  title: "Terms",
  description: "The basics of using CrabCraft's website, Minecraft tools and community features.",
  alternates: { canonical: "https://crabcraft.net/terms" },
};

export default function TermsPage() {
  return (
    <article className="mx-auto w-full max-w-3xl px-4 pt-24 pb-16">
      <header className="mb-10">
        <p className="mb-3 text-xs font-bold uppercase tracking-widest text-orange-500">Information</p>
        <h1 className="text-3xl font-bold sm:text-4xl">Terms</h1>
        <p className="mt-3 text-sm text-gray-500 dark:text-gray-400">Last updated 14 November 2026</p>
      </header>

      <div className="space-y-8 text-sm leading-7 text-gray-700 dark:text-gray-300 [&_h2]:mb-2 [&_h2]:text-lg [&_h2]:font-bold [&_h2]:text-gray-900 dark:[&_h2]:text-gray-100 [&_a]:underline [&_a]:underline-offset-4 [&_a]:decoration-orange-500/60">
        <section>
          <h2>Using CrabCraft</h2>
          <p>
            CrabCraft is a Crab Studios project providing Minecraft tools and community
            features. Use the website lawfully, respect other people, and do not disrupt
            the service, bypass security or misuse accounts. Joining our Minecraft server
            and Discord community is also subject to their membership requirements and rules.
          </p>
        </section>

        <section>
          <h2>Your content and shared links</h2>
          <p>
            Only submit content you have permission to use. You keep your rights in your
            content and allow us to store and display it as needed for the features you use.
            Gradient share links are public to anyone who has the link, so do not put
            private information in shared content. We may remove unlawful or abusive content
            and restrict access where reasonably necessary to protect the community.
          </p>
        </section>

        <section>
          <h2>Tools, availability and other services</h2>
          <p>
            Tool results are guides and may contain mistakes or differ between Minecraft
            versions. Check important results yourself. We may update features or interrupt
            availability for maintenance and security.
          </p>
          <p className="mt-3">
            Google adverts appear only for signed-out visitors on the block gradient generator. Adverts and external
            links lead to services with their own terms; an advert is not our endorsement.
            Our <Link href="/privacy">privacy policy</Link> explains data use and your choices.
          </p>
        </section>

        <section>
          <h2>Rights and contact</h2>
          <p>
            Nothing here limits rights or liabilities that cannot lawfully be excluded.
            CrabCraft is not an official Minecraft product and is not approved by or
            associated with Mojang or Microsoft. We will publish changes to these terms
            here. Contact <a href="mailto:privacy@crabcraft.net">privacy@crabcraft.net</a>{" "}
            with questions about these terms or your information.
          </p>
        </section>
      </div>
    </article>
  );
}
