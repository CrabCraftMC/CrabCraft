import type { ReactNode } from "react";
import { blockGradientLayoutScript } from "@/lib/blockGradientLayout";

const examples = [
  {
    title: "Stone shades",
    description: "A dark-to-light stone gradient for shaded walls and weathered masonry.",
    blocks: [
      { name: "Blackstone", texture: "blackstone" },
      { name: "Cracked Deepslate Bricks", texture: "deepslate/cracked_deepslate_bricks" },
      { name: "Polished Basalt", texture: "polished_basalt_side" },
      { name: "Tuff", texture: "tuff" },
      { name: "Andesite", texture: "stone_andesite" },
      { name: "Smooth Stone", texture: "stone_slab_top" },
      { name: "Polished Diorite", texture: "stone_diorite_smooth" },
      { name: "Calcite", texture: "calcite" },
    ],
  },
  {
    title: "Gold to crimson",
    description: "Warm gold, terracotta and crimson tones for autumnal builds and bold accents.",
    blocks: [
      { name: "Honeycomb Block", texture: "honeycomb" },
      { name: "Yellow Terracotta", texture: "hardened_clay_stained_yellow" },
      { name: "Red Sand", texture: "red_sand" },
      { name: "Stripped Acacia Wood", texture: "stripped_acacia_log" },
      { name: "Orange Terracotta", texture: "hardened_clay_stained_orange" },
      { name: "Red Terracotta", texture: "hardened_clay_stained_red" },
      { name: "Stripped Mangrove Wood", texture: "stripped_mangrove_log_side" },
      { name: "Crimson Stem", texture: "huge_fungus/crimson_log_side" },
    ],
  },
  {
    title: "Purple to pink",
    description: "A vivid transition through purple, magenta and pink for colourful walls and pixel art.",
    blocks: [
      { name: "Purple Concrete", texture: "concrete_purple" },
      { name: "Purple Wool", texture: "wool_colored_purple" },
      { name: "Purple Concrete Powder", texture: "concrete_powder_purple" },
      { name: "Magenta Concrete", texture: "concrete_magenta" },
      { name: "Magenta Wool", texture: "wool_colored_magenta" },
      { name: "Magenta Concrete Powder", texture: "concrete_powder_magenta" },
      { name: "Pink Concrete", texture: "concrete_pink" },
      { name: "Pink Wool", texture: "wool_colored_pink" },
    ],
  },
];

export default function BlockGradientPageContent({ children }: { children: ReactNode }) {
  return (
    <div className="min-h-screen pt-24 pb-16">
      <script dangerouslySetInnerHTML={{ __html: blockGradientLayoutScript }} />
      <header className="container mx-auto px-4 max-w-6xl text-center mb-10">
        <h1 className="text-3xl sm:text-4xl lg:text-5xl font-bold text-orange-500 font-mc">
          Minecraft Block Gradient Generator
        </h1>
        <p className="mt-3 mx-auto max-w-3xl text-gray-600 dark:text-gray-400">
          Choose two blocks or colours, generate a Minecraft block palette, and preview it as a wall.
          Adjust randomness, exclude materials, then copy or share your result.
        </p>
      </header>

      {children}

      <section aria-labelledby="block-gradient-help" className="container mx-auto px-4 max-w-6xl mt-12">
        <h2 id="block-gradient-help" className="text-2xl font-bold text-gray-800 dark:text-gray-100">
          How to make a Minecraft block gradient
        </h2>
        <ol className="mt-6 grid gap-6 md:grid-cols-3 text-sm leading-relaxed text-gray-600 dark:text-gray-400">
          <li>
            <h3 className="mb-2 font-bold text-gray-800 dark:text-gray-100">1. Choose your blocks or colours</h3>
            Select a start and end block, or switch either endpoint to Colour. Use Blocks to adjust
            the palette size. Refresh Palette offers another combination between the same endpoints.
          </li>
          <li>
            <h3 className="mb-2 font-bold text-gray-800 dark:text-gray-100">2. Refine the materials</h3>
            Combine presets such as Survival obtainable and No transparent blocks to narrow the
            suggestions. Select a block in the list or wall and choose Exclude to replace it.
            Select an excluded block in Settings to allow it again.
          </li>
          <li>
            <h3 className="mb-2 font-bold text-gray-800 dark:text-gray-100">3. Preview, copy and share</h3>
            Randomness changes how the blocks mix in the wall preview; Gradient Length changes
            the height of its transition. Use Copy Block List for the material names, or Share
            to copy a link that opens your gradient settings.
          </li>
        </ol>
        <section id="popular-gradients" aria-labelledby="popular-gradients-heading" className="mt-8 rounded-2xl bg-paper-2 p-4 sm:p-6">
          <h2 id="popular-gradients-heading" className="text-2xl font-bold text-gray-800 dark:text-gray-100">
            Popular Gradients
          </h2>
          <div className="mt-6 space-y-6">
            {examples.map((example, index) => (
              <figure key={example.title} className={`grid gap-4 border-t border-line pt-6 first:border-0 first:pt-0 md:gap-6 md:items-center ${
                index % 2 === 0 ? "md:grid-cols-[13rem_minmax(0,1fr)]" : "md:grid-cols-[minmax(0,1fr)_13rem]"
              }`}>
                <figcaption className={index % 2 === 0 ? undefined : "md:order-2 md:text-right"}>
                  <h3 className="font-bold text-gray-800 dark:text-gray-100">{example.title}</h3>
                  <p className="mt-2 text-sm leading-relaxed text-gray-600 dark:text-gray-400">{example.description}</p>
                </figcaption>
                <div className="grid min-w-0 grid-cols-8 gap-1">
                  {example.blocks.map((block, blockIndex) => (
                    <span
                      key={block.texture}
                      tabIndex={0}
                      aria-describedby={`popular-gradient-${index}-${blockIndex}`}
                      className="group relative min-w-0 rounded-sm outline-none focus-visible:ring-2 focus-visible:ring-orange-500 focus-visible:ring-offset-2 focus-visible:ring-offset-paper-2"
                    >
                      <img
                        src={`/textures/blocks/${block.texture}.png`}
                        alt={`${block.name} block texture`}
                        width={80}
                        height={80}
                        loading="lazy"
                        className={`w-full h-auto aspect-square block-texture ${blockIndex === 0 ? "rounded-l-lg" : blockIndex === 7 ? "rounded-r-lg" : ""}`}
                      />
                      <span
                        id={`popular-gradient-${index}-${blockIndex}`}
                        role="tooltip"
                        className={`pointer-events-none absolute bottom-full z-[60] mb-2 hidden w-max max-w-56 rounded-xl border border-line bg-paper-2 p-3 text-sm font-bold text-gray-800 shadow-xl group-hover:block group-focus-visible:block dark:text-gray-200 ${
                          blockIndex < 2 ? "left-0" : blockIndex > 5 ? "right-0" : "left-1/2 -translate-x-1/2"
                        }`}
                      >
                        {block.name}
                      </span>
                    </span>
                  ))}
                </div>
              </figure>
            ))}
          </div>
        </section>
      </section>
    </div>
  );
}
