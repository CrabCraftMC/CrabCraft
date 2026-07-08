---
name: verify
description: Build, run, and drive the CrabCraft web app (apps/web) to verify changes end-to-end in a real browser.
---

# Verifying apps/web changes

## Build & launch

- Install once from the repo root: `bun install`.
- Dev server: `cd apps/web && bunx next dev --port <port>` (use `bunx next dev`
  directly to skip the `predev` texture download when textures already exist in
  `public/textures/blocks`). Page compiles lazily; first hit takes ~4s.
- The server logs a next-auth `MissingSecret` error when `AUTH_SECRET` is unset.
  It is environment noise, not an app bug — pages still return 200.

## Driving

- Chromium lives at `/opt/pw-browsers/chromium`; use `playwright-core` with
  `executablePath` (install it in the scratchpad, not the repo).
- React range sliders don't respond to `fill()`. Set values via the native
  setter + `input` event:
  `Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, "value").set.call(el, v); el.dispatchEvent(new Event("input", {bubbles: true}))`.
- Clipboard checks need `permissions: ["clipboard-read", "clipboard-write"]`
  on the browser context.

## Page gotchas

- `/tools/block-gradient` persists state in localStorage
  (`crabcraft-block-gradient`) — reset via the page's Reset button (aria-label
  "Reset all settings", then confirm) before asserting defaults.
- The preview wall is the `div.relative` whose `div.flex` children each hold
  10 `img.block-texture` cells. Generic selectors like `.relative > .flex`
  match unrelated layout nodes — always anchor on the 10-image rows.
- The gradient intentionally shows fewer blocks than the "Blocks" slider value
  when the color path has fewer unique palette matches.
