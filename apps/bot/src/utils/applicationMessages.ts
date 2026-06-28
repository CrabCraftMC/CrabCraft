export function applicationAcceptedMessage(userId: string) {
  return `**<a:CrabRave:1390633598658547795> Congratulations <@${userId}> **

Your application has been **accepted** - welcome to <:Crab:1397355651822256299> CrabCraft! 

To help you get started, here are some useful channels:
Stay up to date on any news in <#1215993045384953926>, install our official modpack in <#1219426087445205142>, and introduce yourself to our community in <#1377406712683696178> <:heart:1423390737730375764>

If you have any questions or need help, please feel free to ask us in this channel, or create a ticket in <#1397191941782896670>!`;
}

export function applicationDeniedMessage(reason: string) {
  return `Your application has been **rejected**.

**Reason:**
\`\`\`
${reason}
\`\`\`

Thank you for applying, and we're sorry we weren't able to accept you.`;
}
