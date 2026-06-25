import {
  LabelBuilder,
  ModalBuilder,
  StringSelectMenuBuilder,
  TextInputBuilder,
  TextInputStyle,
} from "discord.js";

/**
 * A preset denial reason offered in the deny-application modal dropdown.
 *
 * `value` is the stable option id Discord sends back on submit; `reason` is the
 * text shown to the applicant and written to the logs. Keep `label`, `value`,
 * and `description` each within Discord's 100-character select-option limit.
 */
export interface DenyReasonPreset {
  value: string;
  label: string;
  reason: string;
  description?: string;
}

/**
 * Common reasons a moderator can pick from when denying an application. Adding,
 * removing, or editing an entry here is all that's needed to change the
 * dropdown — the modal and the resolver both read from this list.
 */
export const DENY_REASON_PRESETS: DenyReasonPreset[] = [
  {
    value: "age",
    label: "Age requirement not met",
    reason: "You must be 17 or older to join CrabCraft.",
    description: "Applicant does not meet the 17+ age requirement",
  },
  {
    value: "low_effort",
    label: "Low-effort application",
    reason:
      "Your application didn't give us enough to go on. Feel free to reapply with more detail.",
    description: "Application lacked enough detail or effort",
  },
  {
    value: "policy",
    label: "Did not agree to server policy",
    reason: "You did not agree to CrabCraft's griefing & stealing policy.",
    description: "Applicant declined the griefing & stealing policy",
  },
  {
    value: "alt",
    label: "Suspected alt / ban evasion",
    reason:
      "Your application was denied due to a suspected alternate account or ban evasion.",
    description: "Possible alt account or ban evasion",
  },
  {
    value: "guidelines",
    label: "Does not meet community guidelines",
    reason:
      "Your application does not meet our community guidelines at this time.",
    description: "Does not align with our community standards",
  },
];

/** Custom id of the preset-reason dropdown inside the deny modal. */
export const DENY_REASON_PRESET_SELECT_ID = "deny_reason_preset";
/** Custom id of the free-text custom-reason input inside the deny modal. */
export const DENY_REASON_CUSTOM_ID = "deny_reason";

/** Fallback used when neither a preset nor a custom reason is supplied. */
export const DEFAULT_DENY_REASON = "No reason provided";

/**
 * Build the deny-application modal. The applicant's Minecraft username (when
 * known) rides along on the modal custom id so it survives the round-trip.
 *
 * The modal leads with a dropdown of preset reasons; if none fit, the
 * moderator can type a custom reason in the text box below. Both are optional.
 */
export function buildDenyModal(minecraftUsername: string): ModalBuilder {
  const modal = new ModalBuilder()
    .setCustomId(
      minecraftUsername ? `deny_modal:${minecraftUsername}` : "deny_modal",
    )
    .setTitle("Deny Application");

  const presetSelect = new StringSelectMenuBuilder()
    .setCustomId(DENY_REASON_PRESET_SELECT_ID)
    .setPlaceholder("Pick a preset reason…")
    .setRequired(false)
    .setMinValues(0)
    .setMaxValues(1)
    .addOptions(
      DENY_REASON_PRESETS.map((preset) =>
        preset.description
          ? {
              label: preset.label,
              value: preset.value,
              description: preset.description,
            }
          : { label: preset.label, value: preset.value },
      ),
    );

  const presetLabel = new LabelBuilder()
    .setLabel("Preset reason")
    .setDescription("Choose a common reason, or leave blank to write your own.")
    .setStringSelectMenuComponent(presetSelect);

  const customInput = new TextInputBuilder()
    .setCustomId(DENY_REASON_CUSTOM_ID)
    .setStyle(TextInputStyle.Paragraph)
    .setRequired(false)
    .setMaxLength(1000)
    .setPlaceholder("Write a custom reason if none of the presets fit.");

  const customLabel = new LabelBuilder()
    .setLabel("Custom reason (optional)")
    .setTextInputComponent(customInput);

  modal.addLabelComponents(presetLabel, customLabel);
  return modal;
}

/**
 * Turn the moderator's modal input into the final denial reason.
 *
 * A custom reason and a preset can both be present: the preset leads and the
 * custom note is appended. If only one is given, that one is used; if neither,
 * the default fallback is returned.
 */
export function resolveDenyReason(
  presetValue: string | undefined,
  customReason: string | undefined,
): string {
  const custom = customReason?.trim() ?? "";
  const preset = presetValue
    ? DENY_REASON_PRESETS.find((p) => p.value === presetValue)?.reason
    : undefined;

  if (preset && custom) return `${preset}\n\n${custom}`;
  if (preset) return preset;
  if (custom) return custom;
  return DEFAULT_DENY_REASON;
}
