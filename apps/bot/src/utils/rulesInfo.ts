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

// NOTE: This is a signed Discord CDN attachment URL and WILL expire (~24h).
// Replace it with a permanent URL (or re-upload the banner) so it doesn't break.
const RULES_BANNER_URL =
  "https://cdn.discordapp.com/attachments/1219426087445205142/1455978843084165170/unknown.png?ex=6a342d76&is=6a32dbf6&hm=2224ec40ef92955f46a4c1b52efe8bd5b2d951bb61617eae8abd7ca4b1308238&";

const TICKET_CHANNEL_URL =
  "https://discord.com/channels/1215756131671212163/1397191941782896670";
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
        "CrabCraft is a community-focused survival server, founded in 2024 and running ever since. The community always comes first here, and we will always stick to our true vanilla roots.\n### Pure Vanilla Fun\nNo gimmicks, just some QoL features, playing Minecraft the way it was meant to be. Inspired by HermitCraft.\n### Voice Chat Built In\nWe use the [Simple Voice Chat](https://modrinth.com/plugin/simple-voice-chat) mod to bring proximity voice communication directly into the game\n### No ranks.. ever\nWe firmly believe in maintaining a completely level playing field for all players. There are no ranks, premium features, or pay-to-win mechanics.\n### Regular Backups\nAutomatic backups and rollback protection on a per-chunk basis.\n### Quick and Lag-Free\nThe server is hosted in France to provide the best latency we can for players all around the globe, and it runs on the latest hardware to keep CrabCraft as quick and lag-free as possible.",
      ),
    );

  const rules = new ContainerBuilder()
    .addTextDisplayComponents(
      new TextDisplayBuilder().setContent("# 📋 Server Rules"),
    )
    .addSeparatorComponents(divider())
    .addTextDisplayComponents(
      new TextDisplayBuilder().setContent(
        "-# The short version: don't grief, steal, cheat, dupe, or kill other players. CrabCraft is a 17+ server.\n### Respect\nTreat others with respect at all times. No griefing, stealing, harassment, bullying, discrimination, or intentionally annoying behaviour (spam, excessive trolling, etc.). If someone asks you to stop, you must stop. Killing other players is off limits, and that includes random totem popping.\n### Integrity\nPlay fairly and honestly. Cheating, hacking, exploiting, duping, or abusing bugs or unintended game mechanics is strictly prohibited (other than the allowed dupes listed in <#1377256699496366191>). Alt accounts are allowed, just keep it to two per player.\n### Community\nContribute to a positive and friendly server environment. Respect other players’ builds and shared areas, help others when you can, and do not disrupt others’ gameplay. Above all, use common sense and don't be an asshole.",
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
        "<:Crab:1397355651822256299> **Server IP:** `crabcraft.net`\n<:Earth:1397727941752000688> **World Map:** [map.crabcraft.net](https://map.crabcraft.net)\n<:Safari:1397723987253002370> **Website:** [crabcraft.net](https://crabcraft.net)\n<:Instagram:1397723985491529808> **Instagram:** [@crabcraftmc](https://www.instagram.com/crabcraftmc)\n<:TikTok:1397723983750893659> **TikTok:** [@playcrabcraft](http://tiktok.com/@playcrabcraft)",
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
    );

  return [welcome, rules, commitment, links, buttons];
}
