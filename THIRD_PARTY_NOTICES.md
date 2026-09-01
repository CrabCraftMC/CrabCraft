# Third-party notices

This file records material source and binary components that are bundled or adapted in the repository. It is not a complete dependency licence report and is not legal advice.

## Leaves Jade implementation

The Jade implementation under `apps/minecraft/spigot/src/main/java/crabcraft/net/crabUtilities/jade` is derived from [LeavesMC/Leaves](https://github.com/LeavesMC/Leaves) and is identified upstream as GPL-3.0 licensed. The GPLv3 text is included in the repository's root `LICENSE`. The Minecraft module and its distribution terms must remain GPL-compatible wherever this derivative is distributed.

CrabCraft contributors have modified this implementation for standalone plug-in integration and security hardening, including modifications through 23 July 2026. A modification notice and the GPLv3 text are also included in the built Spigot JAR.

## AppleSkinSpigot

Source under `apps/minecraft/spigot/src/main/java/crabcraft/net/crabUtilities/appleskin` is adapted from [AppleSkinSpigot](https://github.com/jmattingley23/AppleSkinSpigot) at the audited upstream revision `20c95d…`.

Copyright (c) 2021 jmattingley23. Licensed under the MIT Licence. The required licence notice is included at `apps/minecraft/spigot/src/main/resources/appleskin/LICENSE`.

## Other bundled Minecraft components

- GreenChunk licence notices are preserved under `apps/minecraft/spigot/src/main/resources/greenchunk`.
- View Distance Tweaks' MIT notice is preserved under `apps/minecraft/spigot/src/main/resources/view-distance-tweaks/LICENSE`.
- The Unbounded font used by the Discord bot is licensed under the SIL Open Font Licence 1.1; its licence is preserved beside the font at `apps/bot/assets/player-card/OFL.txt`.

## PostHog SDKs

The website, Discord bot, and Velocity proxy use PostHog's official JavaScript,
Node.js, and JVM SDKs. The JVM SDK and its runtime dependencies are bundled into
the shaded Velocity plug-in. PostHog's SDKs are copyright PostHog, Inc. The
browser SDK is distributed under Apache-2.0 and MIT licences; the Node.js and
JVM SDKs are distributed under the MIT Licence. Their sources are available
from [PostHog/posthog-js](https://github.com/PostHog/posthog-js) and
[PostHog/posthog-android](https://github.com/PostHog/posthog-android).

## Minecraft artwork and textures

Minecraft names, textures, icons, screenshots, and other game-derived artwork are not granted under any CrabCraft source-code licence merely because they are present in this repository. Review the applicable Mojang/Microsoft usage terms before public redistribution. Texture downloads used during web builds are pinned to immutable upstream revisions for integrity, but integrity pinning does not grant redistribution rights.
