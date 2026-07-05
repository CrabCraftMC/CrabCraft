// Season 7 launches 10 July 2026 19:00 BST (18:00 UTC); the event line below
// is only included until then.
const SEASON_SEVEN_LAUNCH_MS = 1783706400 * 1000;
const SEASON_SEVEN_EVENT_URL =
  "https://discord.com/events/1215756131671212163/1523077214961143891";

export function applicationAcceptedMessage(userId: string) {
  const seasonEventLine =
    Date.now() < SEASON_SEVEN_LAUNCH_MS
      ? `\n\nFollow this event to be notified when season seven starts: ${SEASON_SEVEN_EVENT_URL}`
      : "";

  return `**<a:CrabRave:1390633598658547795> Congratulations <@${userId}> **

Your application has been **accepted** - welcome to <:Crab:1397355651822256299> CrabCraft!

To help you get started, here are some useful channels:
Stay up to date on any news in <#1215993045384953926>, install our official modpack in <#1219426087445205142>, and introduce yourself to our community in <#1377406712683696178> <:heart:1423390737730375764>

If you have any questions or need help, please feel free to ask us in this channel, or create a ticket in <#1397191941782896670>!${seasonEventLine}`;
}

export function applicationDeniedMessage(reason: string) {
  return `Your application has been **rejected**.

**Reason:**
\`\`\`
${reason}
\`\`\`

Thank you for applying, and we're sorry we weren't able to accept you.`;
}
