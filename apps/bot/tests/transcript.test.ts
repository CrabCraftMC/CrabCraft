import { describe, expect, test } from "bun:test";
import { renderMarkdown, safeExternalUrl } from "../src/utils/transcript.js";

describe("transcript links", () => {
  test("keeps ordinary HTTPS links", () => {
    const html = renderMarkdown("[site](https://example.com/path?q=1&next=2)");

    expect(html).toContain('href="https://example.com/path?q=1&amp;next=2"');
    expect(html).toContain('rel="noopener noreferrer"');
  });

  test("removes active-content link schemes", () => {
    for (const value of [
      "javascript:alert(1)",
      "data:text/html,<script>alert(1)</script>",
      "file:///etc/passwd",
    ]) {
      const html = renderMarkdown(`[open](${value})`);
      expect(html).not.toContain("href=");
      expect(html).toContain("unsafe link removed");
    }
  });

  test("accepts only HTTP and HTTPS as external URLs", () => {
    expect(safeExternalUrl("https://example.com")).toBe("https://example.com/");
    expect(safeExternalUrl("http://example.com")).toBe("http://example.com/");
    expect(safeExternalUrl("JaVaScRiPt:alert(1)")).toBeNull();
    expect(safeExternalUrl("//example.com/path")).toBeNull();
  });
});
