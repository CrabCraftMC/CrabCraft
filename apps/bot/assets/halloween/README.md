# Halloween announcement artwork

These source images were supplied by the project owner for the Halloween card:

- `crabcraft-ghost.png`: the first frame of the supplied CrabCraft ghost animation,
  with its purple background layer omitted.
- `reward-pumpkin.png`: the supplied `Emojis_48x48_168.png` image.

The announcement is rendered without generative AI:

```sh
bun apps/bot/src/scripts/renderHalloweenCard.ts
```

The generated PNG is written to `output/halloween-challenges-2026.png` and is not
part of the source commit. Existing Minecraft textures and font notices apply to
the other artwork used by the renderer; see `THIRD_PARTY_NOTICES.md`.
