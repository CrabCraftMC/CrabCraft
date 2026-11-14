import { expect, test } from "bun:test";
import { renderToStaticMarkup } from "react-dom/server";
import BlockGradientPageContent from "../src/components/BlockGradientPageContent";

test("renders a descriptive heading and usage guidance without the interactive generator", () => {
  const html = renderToStaticMarkup(<BlockGradientPageContent>{null}</BlockGradientPageContent>);

  expect(html.match(/<h1\b/g)).toHaveLength(1);
  expect(html).toContain("Minecraft Block Gradient Generator</h1>");
  expect(html).toContain('aria-labelledby="block-gradient-help"');
  expect(html).toContain("How to make a Minecraft block gradient");
  expect(html).toContain('aria-labelledby="popular-gradients-heading"');
  expect(html).toContain("Popular Gradients</h2>");
  expect(html).not.toContain(" → ");
  expect(html).not.toContain('href="/tools/rgb-nickname"');
  expect(html.indexOf("<script")).toBeLessThan(html.indexOf("<header"));
  const figures = html.match(/<figure\b[\s\S]*?<\/figure>/g) ?? [];
  expect(figures).toHaveLength(3);
  for (const figure of figures) {
    expect(figure.match(/<img\b/g)).toHaveLength(8);
    expect(figure.match(/role="tooltip"/g)).toHaveLength(8);
  }
  for (const block of ["Blackstone", "Calcite", "Honeycomb Block", "Crimson Stem", "Purple Concrete", "Pink Wool"]) {
    expect(html).toContain(`alt="${block} block texture"`);
  }
});
