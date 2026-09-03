import {
  ModalBuilder,
  TextInputBuilder,
  TextInputStyle,
} from "discord.js";

export const APPLICATION_QUESTIONS = {
  minecraftUsername: "Minecraft Username",
  age: "How old are you?",
  joinReason: "Why do you want to join CrabCraft?",
  aboutYou: "Tell us about yourself and how you play",
  referralSource: "Where did you find the server?",
} as const;

export const APPLICATION_REFERRAL_OPTIONS = [
  "Reddit",
  "Google",
  "Website",
  "AI Chat",
  "Minecraft server list",
  "Friend or existing member",
  "Other",
] as const;

export interface ApplicationFormValues {
  minecraftUsername: string;
  age: string;
  joinReason: string;
  aboutYou: string;
  referralSource: string;
}

export function buildApplicationModal(
  customId: "application" | "edit-application",
  title: string,
  values?: ApplicationFormValues,
): ModalBuilder {
  const minecraftUsername = new TextInputBuilder()
    .setCustomId("minecraft-username")
    .setPlaceholder("Steve")
    .setRequired(true)
    .setStyle(TextInputStyle.Short);

  const age = new TextInputBuilder()
    .setCustomId("age")
    .setPlaceholder("Enter your age as a number")
    .setMinLength(2)
    .setMaxLength(3)
    .setRequired(true)
    .setStyle(TextInputStyle.Short);

  const joinReason = new TextInputBuilder()
    .setCustomId("join-reason")
    .setPlaceholder(
      "In a sentence or two, tell us why CrabCraft appeals to you and what you hope to do here.",
    )
    .setMinLength(50)
    .setMaxLength(1000)
    .setRequired(true)
    .setStyle(TextInputStyle.Paragraph);

  const aboutYou = new TextInputBuilder()
    .setCustomId("about-you")
    .setPlaceholder(
      "Share your play style, interests, and how you like to get involved in a community.",
    )
    .setMinLength(50)
    .setMaxLength(1000)
    .setRequired(true)
    .setStyle(TextInputStyle.Paragraph);

  if (values) {
    if (values.minecraftUsername)
      minecraftUsername.setValue(values.minecraftUsername);
    if (values.age) age.setValue(values.age);
    if (values.joinReason) joinReason.setValue(values.joinReason);
    if (values.aboutYou) aboutYou.setValue(values.aboutYou);
  }

  const referralOptions: Array<{
    label: string;
    value: string;
    default: boolean;
  }> = APPLICATION_REFERRAL_OPTIONS.map((option) => ({
    label: option,
    value: option,
    default: values?.referralSource === option,
  }));
  if (
    values?.referralSource &&
    !APPLICATION_REFERRAL_OPTIONS.includes(
      values.referralSource as (typeof APPLICATION_REFERRAL_OPTIONS)[number],
    )
  ) {
    referralOptions.push({
      label: `Other: ${values.referralSource}`.slice(0, 100),
      value: values.referralSource,
      default: true,
    });
  }

  return new ModalBuilder()
    .setCustomId(customId)
    .setTitle(title)
    .addLabelComponents(
      (label) =>
        label
          .setLabel(APPLICATION_QUESTIONS.minecraftUsername)
          .setTextInputComponent(minecraftUsername),
      (label) =>
        label
          .setLabel(APPLICATION_QUESTIONS.age)
          .setTextInputComponent(age),
      (label) =>
        label
          .setLabel(APPLICATION_QUESTIONS.joinReason)
          .setTextInputComponent(joinReason),
      (label) =>
        label
          .setLabel(APPLICATION_QUESTIONS.aboutYou)
          .setTextInputComponent(aboutYou),
      (label) =>
        label
          .setLabel(APPLICATION_QUESTIONS.referralSource)
          .setStringSelectMenuComponent((select) =>
            select
              .setCustomId("referral-source")
              .setPlaceholder("Select an option")
              .setMinValues(0)
              .setMaxValues(1)
              .setRequired(false)
              .addOptions(referralOptions),
          ),
    );
}

export function parseApplicantAge(value: string): number | null {
  const trimmed = value.trim();
  if (!/^\d{2,3}$/.test(trimmed)) return null;

  const age = Number(trimmed);
  return Number.isInteger(age) && age <= 120 ? age : null;
}
