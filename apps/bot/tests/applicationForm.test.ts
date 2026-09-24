import { describe, expect, test } from "bun:test";
import { parseApplicantAge } from "../src/utils/applicationForm.js";

describe("application form", () => {
  test("accepts realistic numeric ages only", () => {
    expect(parseApplicantAge("17")).toBe(17);
    expect(parseApplicantAge(" 24 ")).toBe(24);
    expect(parseApplicantAge("yes")).toBeNull();
    expect(parseApplicantAge("17 years old")).toBeNull();
    expect(parseApplicantAge("121")).toBeNull();
  });
});
