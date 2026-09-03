import { describe, expect, test } from "bun:test";
import {
  APPLICATION_QUESTIONS,
  APPLICATION_REFERRAL_OPTIONS,
  buildApplicationModal,
  parseApplicantAge,
} from "../src/utils/applicationForm.js";

describe("application form", () => {
  test("uses all five modal fields and only makes the referral question optional", () => {
    const modal = buildApplicationModal("application", "CrabCraft Application");
    const labels = modal.toJSON().components as Array<{
      label: string;
      component: {
        custom_id: string;
        required?: boolean;
        min_length?: number;
        min_values?: number;
        max_values?: number;
        options?: Array<{ label: string; value: string }>;
      };
    }>;
    const inputs = labels.map((label) => label.component);

    expect(inputs.map((input) => input.custom_id)).toEqual([
      "minecraft-username",
      "age",
      "join-reason",
      "about-you",
      "referral-source",
    ]);
    expect(inputs.map((input) => input.required)).toEqual([
      true,
      true,
      true,
      true,
      false,
    ]);
    expect(labels.map((label) => label.label)).toEqual([
      APPLICATION_QUESTIONS.minecraftUsername,
      APPLICATION_QUESTIONS.age,
      APPLICATION_QUESTIONS.joinReason,
      APPLICATION_QUESTIONS.aboutYou,
      APPLICATION_QUESTIONS.referralSource,
    ]);
    expect(inputs[2].min_length).toBe(50);
    expect(inputs[3].min_length).toBe(50);
    expect(inputs[4].min_values).toBe(0);
    expect(inputs[4].max_values).toBe(1);
    expect(inputs[4].options?.map((option) => option.value)).toEqual(
      APPLICATION_REFERRAL_OPTIONS,
    );
  });

  test("accepts realistic numeric ages only", () => {
    expect(parseApplicantAge("17")).toBe(17);
    expect(parseApplicantAge(" 24 ")).toBe(24);
    expect(parseApplicantAge("yes")).toBeNull();
    expect(parseApplicantAge("17 years old")).toBeNull();
    expect(parseApplicantAge("121")).toBeNull();
  });
});
