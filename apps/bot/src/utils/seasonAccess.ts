import {
  ActionRowBuilder,
  ButtonBuilder,
  ButtonStyle,
  TextDisplayBuilder,
} from "discord.js";

export const SEASON_PLAY_BUTTON_ID = "season-play";

const ANNOUNCEMENT_BEFORE_BUTTON = `## <:Crab:1397355651822256299> Release date **confirmed**

Hey @everyone!
-# (long message, but please read it all!)

As per the results of the poll above, the confirmed Season 7 release date is <t:1783706400:F> (<t:1783706400:R>).

**Get a notification when we launch:** https://discord.com/events/1215756131671212163/1523077214961143891
### What we're doing for the sulfur update (26.2):
1. As soon as we can, we will be updating the server to Minecraft 26.2
2. Any chunks with no player activity will be purged to allow for the new cave biome to be generated
3. A world border of 4k (8k total diameter) will be active while the server is running 26.1.2

If you plan to play Season 7, click this button to receive the <@&1516779370109206578> role. **(you must click this)**`;

const ANNOUNCEMENT_AFTER_BUTTON = `The server launches on 26.1.2, but we **highly recommend** creating your Minecraft instances on 26.2 since we plan to upgrade within the first few weeks. The server will support 26.2 clients, but 26.2 content will be added later.

A CrabCraft modpack with all recommended mods will be sent tomorrow.

We plan to make a few posts on <:Reddit_Logo:1511844016793325648> Reddit before the release, so if you'd like to help us by upvoting you can select the <@&1511843400603799602> role in <id:customize> ❤️

~ <:Crabby:1523297819048415383> **Crabby**`;

/**
 * The season release announcement (posted via /admin send): plain text with
 * the "Play season seven" button embedded between the two halves.
 */
export function buildSeasonAccessComponents() {
  return [
    new TextDisplayBuilder().setContent(ANNOUNCEMENT_BEFORE_BUTTON),
    buildSeasonAccessButton(),
    new TextDisplayBuilder().setContent(ANNOUNCEMENT_AFTER_BUTTON),
  ];
}

export function buildSeasonAccessButton(): ActionRowBuilder<ButtonBuilder> {
  return new ActionRowBuilder<ButtonBuilder>().addComponents(
    new ButtonBuilder()
      .setCustomId(SEASON_PLAY_BUTTON_ID)
      .setLabel("Play S7")
      .setStyle(ButtonStyle.Primary),
  );
}

/**
 * "Open ticket" fallback shown when we can't locate the clicker's Minecraft
 * account. Reuses the existing general-ticket flow (no intake questions, the
 * ticket opens straight away).
 */
export function buildOpenTicketButton(): ActionRowBuilder<ButtonBuilder> {
  return new ActionRowBuilder<ButtonBuilder>().addComponents(
    new ButtonBuilder()
      .setCustomId("ticket_open:general")
      .setLabel("Open ticket")
      .setStyle(ButtonStyle.Secondary),
  );
}
