import {
  ActionRowBuilder,
  ButtonBuilder,
  ButtonStyle,
  ContainerBuilder,
  MediaGalleryBuilder,
  MediaGalleryItemBuilder,
  SeparatorBuilder,
  SeparatorSpacingSize,
  TextDisplayBuilder,
  type MessageActionRowComponentBuilder,
} from "discord.js";

const RULES_BANNER_URL = "https://crabcraft.net/rules-info.png";

const TICKET_CHANNEL_URL =
  "https://discord.com/channels/1215756131671212163/1397191941782896670";
const FAQS_CHANNEL_URL =
  "https://discord.com/channels/1215756131671212163/1377256699496366191";
const MODPACK_URL = "https://modrinth.com/modpack/crabcraft-modpack";

/** A divider separator with small spacing (matches the source layout). */
function divider(): SeparatorBuilder {
  return new SeparatorBuilder()
    .setDivider(true)
    .setSpacing(SeparatorSpacingSize.Small);
}

/**
 * The Rules & Info panel — four Components V2 containers (welcome, rules,
 * community commitment, useful links) plus a row of link buttons.
 */
export function buildRulesInfoComponents() {
  const welcome = new ContainerBuilder()
    .addMediaGalleryComponents(
      new MediaGalleryBuilder().addItems(
        new MediaGalleryItemBuilder().setURL(RULES_BANNER_URL),
      ),
    )
    .addTextDisplayComponents(
      new TextDisplayBuilder().setContent(
        "# <:Crab:1397355651822256299> Welcome to CrabCraft",
      ),
    )
    .addSeparatorComponents(divider())
    .addTextDisplayComponents(
      new TextDisplayBuilder().setContent(
        "CrabCraft is a community-focused survival server, founded in 2024 and running ever since. The community always comes first here, and we will always stick to our true vanilla roots.\n### Pure Vanilla Fun\nNo gimmicks, just some QoL features, playing Minecraft the way it was meant to be. Inspired by HermitCraft.\n### Voice Chat Built In\nWe use the [Simple Voice Chat](https://modrinth.com/plugin/simple-voice-chat) mod to bring proximity voice communication directly into the game\n### No ranks.. ever\nWe firmly believe in maintaining a completely level playing field for all players. There are no ranks, premium features, or pay-to-win mechanics.\n### Regular Backups\nAutomatic backups and rollback protection on a per-chunk basis.",
      ),
    );

  const rules = new ContainerBuilder()
    .addTextDisplayComponents(
      new TextDisplayBuilder().setContent("# 📋 Server Rules"),
    )
    .addSeparatorComponents(divider())
    .addTextDisplayComponents(
      new TextDisplayBuilder().setContent(
        "-# CrabCraft is a 17+ server. By playing here, you agree to follow these rules.\n<:arrow:1541478047121678478> **Protect other players’ work.** Never steal, grief, or alter someone else’s build without permission, even if it appears abandoned or is unclaimed.\n<:arrow:1541478047121678478> **No unwanted PvP.** Do not kill other players, set traps for them, or pop their totems without their consent.\n<:arrow:1541478047121678478> **Play fairly.** X-ray, hacked clients, exploits, and other unfair modifications are prohibited. Auto-clickers and freecam are allowed only for non-abusive purposes and must not be used to disrupt others.\n<:arrow:1541478047121678478> **No unauthorised duplication.** Only the duplication methods listed in <#1377256699496366191> are allowed.\n<:arrow:1541478047121678478> **Keep the server running smoothly.** Lag machines and farms or builds that place an excessive load on the server are not allowed.\n<:arrow:1541478047121678478> **Treat everyone well.** Do not spam, harass, target, bully, scam, or use toxic language, swearing, or slurs. Discrimination and derogatory behaviour are never tolerated. Respect all players and staff.\n<:arrow:1541478047121678478> **Contribute positively.** Help keep CrabCraft friendly and welcoming, respect shared spaces, and do not deliberately disrupt anyone else’s experience.\n<:arrow:1541478047121678478> **Alternate accounts are allowed.** Each player may have up to two whitelisted Minecraft accounts in total. To whitelist an alternate account, open a ticket in <#1397191941782896670>.",
      ),
    )
    .addSeparatorComponents(divider())
    .addTextDisplayComponents(
      new TextDisplayBuilder().setContent(
        "We have a zero-tolerance policy: breaking any of these rules will get you permanently banned. Punishments can be appealed by opening a ticket, but if you are banned from our Discord you lose the ability to appeal.",
      ),
    );

  const commitment = new ContainerBuilder()
    .addTextDisplayComponents(
      new TextDisplayBuilder().setContent("# 🤝 Community Commitment"),
    )
    .addSeparatorComponents(divider())
    .addTextDisplayComponents(
      new TextDisplayBuilder().setContent(
        "At CrabCraft, we value a **welcoming, inclusive community** for all.\n\nWhile a working microphone is expected for communication, we understand that not everyone may be able to speak.\n\nWe’re committed to making space for everyone to participate in ways that work for them.",
      ),
    );

  const links = new ContainerBuilder()
    .addTextDisplayComponents(
      new TextDisplayBuilder().setContent("# 💬 Useful Links"),
    )
    .addSeparatorComponents(divider())
    .addTextDisplayComponents(
      new TextDisplayBuilder().setContent(
        "<:Crab:1397355651822256299> **Server IP:** `crabcraft.net`\n<:Earth:1397727941752000688> **World Map:** [map.crabcraft.net](https://map.crabcraft.net)\n<:Safari:1397723987253002370> **Website:** [crabcraft.net](https://crabcraft.net)\n<:Instagram:1397723985491529808> **Instagram:** [@crabcraftmc](https://www.instagram.com/crabcraftmc)\n<:TikTok:1397723983750893659> **TikTok:** [@playcrabcraft](https://tiktok.com/@playcrabcraft)",
      ),
    );

  const buttons =
    new ActionRowBuilder<MessageActionRowComponentBuilder>().addComponents(
      new ButtonBuilder()
        .setStyle(ButtonStyle.Link)
        .setLabel("Open a Ticket")
        .setEmoji("🎟️")
        .setURL(TICKET_CHANNEL_URL),
      new ButtonBuilder()
        .setStyle(ButtonStyle.Link)
        .setLabel("Official Modpack")
        .setEmoji("📦")
        .setURL(MODPACK_URL),
      new ButtonBuilder()
        .setStyle(ButtonStyle.Link)
        .setLabel("FAQs")
        .setEmoji("❓")
        .setURL(FAQS_CHANNEL_URL),
    );

  return [welcome, rules, commitment, links, buttons];
}
