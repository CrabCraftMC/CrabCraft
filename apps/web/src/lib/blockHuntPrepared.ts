import type { BlockHuntPuzzle } from "@/lib/blockHunt";

// Curated against the current Java Edition block pages on Minecraft Wiki.
export const ADDITIONAL_BLOCK_HUNT_PUZZLES =
[
  {
    "answer": "Obsidian",
    "texture": "obsidian",
    "clues": [
      {
        "kind": "data",
        "label": "Engine profile",
        "text": "In its engine profile, this block has no block entity, has luminance 0, and makes a note block above it play bass drum. It appears black on maps."
      },
      {
        "kind": "destruction",
        "label": "Mining profile",
        "text": "In its mining data, this block has hardness 50, has blast resistance 1200, and uses a pickaxe as its intended tool. Its harvesting rule does not require Silk Touch."
      },
      {
        "kind": "redstone",
        "label": "Signal rules",
        "text": "Under redstone rules, this block is conductive, supports redstone dust, and provides no comparator output. It does not emit redstone power by itself."
      },
      {
        "kind": "shape",
        "label": "Physical rules",
        "text": "Under physical block rules, this block is an opaque full cube, cannot be moved by pistons, and is not flammable."
      },
      {
        "kind": "behaviour",
        "label": "Behaviour",
        "text": "Flowing water creates it when it touches a lava source block."
      },
      {
        "kind": "world",
        "label": "Identity",
        "text": "This dark volcanic block forms Nether portal frames and appears in enchanting table recipes."
      }
    ]
  },
  {
    "answer": "Crying Obsidian",
    "texture": "crying_obsidian",
    "clues": [
      {
        "kind": "data",
        "label": "Engine profile",
        "text": "In its engine profile, this block has no block entity, makes a note block above it play bass drum, and has luminance 10. It appears black on maps."
      },
      {
        "kind": "destruction",
        "label": "Mining profile",
        "text": "In its mining data, this block has hardness 50, uses a pickaxe as its intended tool, and has blast resistance 1200. Its harvesting rule does not require Silk Touch."
      },
      {
        "kind": "redstone",
        "label": "Signal rules",
        "text": "Under redstone rules, this block is conductive, provides no comparator output, and supports redstone dust. It does not emit redstone power by itself."
      },
      {
        "kind": "shape",
        "label": "Physical rules",
        "text": "Under physical block rules, this block is an opaque full cube, is not flammable, and cannot be moved by pistons."
      },
      {
        "kind": "behaviour",
        "label": "Behaviour",
        "text": "It releases purple particles and can form part of a charged respawn point."
      },
      {
        "kind": "world",
        "label": "Identity",
        "text": "This luminous purple cousin of obsidian is commonly found around ruined portals."
      }
    ]
  },
  {
    "answer": "Glowstone",
    "texture": "glowstone",
    "clues": [
      {
        "kind": "data",
        "label": "Engine profile",
        "text": "In its engine profile, this block has luminance 15, has no block entity, and makes a note block above it play pling. It appears sand on maps."
      },
      {
        "kind": "destruction",
        "label": "Mining profile",
        "text": "In its mining data, this block has blast resistance 0.3, has hardness 0.3, and uses a pickaxe as its intended tool. Silk Touch is required to collect the block itself."
      },
      {
        "kind": "redstone",
        "label": "Signal rules",
        "text": "Under redstone rules, this block supports redstone dust, is not conductive, and provides no comparator output. It does not emit redstone power by itself."
      },
      {
        "kind": "shape",
        "label": "Physical rules",
        "text": "Under physical block rules, this block can be moved by pistons, is an opaque full cube, and is not flammable."
      },
      {
        "kind": "behaviour",
        "label": "Behaviour",
        "text": "Without Silk Touch it breaks into dust, and four pieces of that dust recreate the block."
      },
      {
        "kind": "world",
        "label": "Identity",
        "text": "This bright golden block grows in clusters hanging from Nether ceilings."
      }
    ]
  },
  {
    "answer": "Magma Block",
    "texture": "magma",
    "clues": [
      {
        "kind": "data",
        "label": "Engine profile",
        "text": "In its engine profile, this block has luminance 3, makes a note block above it play bass drum, and has no block entity. It appears nether on maps."
      },
      {
        "kind": "destruction",
        "label": "Mining profile",
        "text": "In its mining data, this block has blast resistance 0.5, uses a pickaxe as its intended tool, and has hardness 0.5. Its harvesting rule does not require Silk Touch."
      },
      {
        "kind": "redstone",
        "label": "Signal rules",
        "text": "Under redstone rules, this block supports redstone dust, provides no comparator output, and is conductive. It does not emit redstone power by itself."
      },
      {
        "kind": "shape",
        "label": "Physical rules",
        "text": "Under physical block rules, this block can be moved by pistons, is not flammable, and is an opaque full cube."
      },
      {
        "kind": "behaviour",
        "label": "Behaviour",
        "text": "It hurts entities standing on it and creates downward bubble columns beneath water."
      },
      {
        "kind": "world",
        "label": "Identity",
        "text": "This hot, cracked block occurs throughout the Nether and on the ocean floor."
      }
    ]
  },
  {
    "answer": "Slime Block",
    "texture": "slime",
    "clues": [
      {
        "kind": "data",
        "label": "Engine profile",
        "text": "In its engine profile, this block makes a note block above it play harp, has no block entity, and has luminance 0. It appears grass on maps."
      },
      {
        "kind": "destruction",
        "label": "Mining profile",
        "text": "In its mining data, this block is intended to be mined by hand, has hardness 0, and has blast resistance 0. Its harvesting rule does not require Silk Touch."
      },
      {
        "kind": "redstone",
        "label": "Signal rules",
        "text": "Under redstone rules, this block provides no comparator output, is conductive, and supports redstone dust. It does not emit redstone power by itself."
      },
      {
        "kind": "shape",
        "label": "Physical rules",
        "text": "Under physical block rules, this block is not an opaque full cube, has a movement interaction of slows when walked on, and is a full cube."
      },
      {
        "kind": "behaviour",
        "label": "Behaviour",
        "text": "Entities bounce on it, and piston machines use its ability to carry neighbouring blocks."
      },
      {
        "kind": "world",
        "label": "Identity",
        "text": "Nine slimeballs craft this translucent green block."
      }
    ]
  },
  {
    "answer": "Sea Lantern",
    "texture": "sea_lantern",
    "clues": [
      {
        "kind": "data",
        "label": "Engine profile",
        "text": "In its engine profile, this block makes a note block above it play hat, has luminance 15, and has no block entity. It appears quartz on maps."
      },
      {
        "kind": "destruction",
        "label": "Mining profile",
        "text": "In its mining data, this block uses a pickaxe as its intended tool, has blast resistance 0.3, and has hardness 0.3. Silk Touch is required to collect the block itself."
      },
      {
        "kind": "redstone",
        "label": "Signal rules",
        "text": "Under redstone rules, this block provides no comparator output, supports redstone dust, and is not conductive. It does not emit redstone power by itself."
      },
      {
        "kind": "shape",
        "label": "Physical rules",
        "text": "Under physical block rules, this block is not flammable, can be moved by pistons, and is an opaque full cube."
      },
      {
        "kind": "behaviour",
        "label": "Behaviour",
        "text": "Breaking it without Silk Touch can yield prismarine crystals instead of the block."
      },
      {
        "kind": "world",
        "label": "Identity",
        "text": "This pale underwater light is built into ocean monuments."
      }
    ]
  },
  {
    "answer": "Redstone Lamp",
    "texture": "redstone_lamp_off",
    "clues": [
      {
        "kind": "data",
        "label": "Engine profile",
        "text": "Under its stored block properties, this block has no block entity, has luminance 0 to 15, and makes a note block above it play harp. It appears terracotta orange on maps."
      },
      {
        "kind": "destruction",
        "label": "Mining profile",
        "text": "Under harvesting rules, this block has hardness 0.3, has blast resistance 0.3, and uses a pickaxe as its intended tool. Its harvesting rule does not require Silk Touch."
      },
      {
        "kind": "redstone",
        "label": "Signal rules",
        "text": "When wired into a circuit, this block is conductive, supports redstone dust, and provides no comparator output. It does not emit redstone power by itself."
      },
      {
        "kind": "shape",
        "label": "Physical rules",
        "text": "When the engine checks its shape, this block is an opaque full cube, can be moved by pistons, and is not flammable."
      },
      {
        "kind": "behaviour",
        "label": "Behaviour",
        "text": "It reaches light level 15 while powered and goes dark again when power is removed."
      },
      {
        "kind": "world",
        "label": "Identity",
        "text": "Redstone dust surrounding glowstone crafts this switchable light."
      }
    ]
  },
  {
    "answer": "Crafter",
    "texture": "crafter_south",
    "clues": [
      {
        "kind": "data",
        "label": "Engine profile",
        "text": "Under its stored block properties, this block uses a ticking block entity, makes a note block above it play harp, and has luminance 0. It appears stone on maps."
      },
      {
        "kind": "destruction",
        "label": "Mining profile",
        "text": "Under harvesting rules, this block has hardness 1.5, uses a pickaxe as its intended tool, and has blast resistance 3.5. Its harvesting rule does not require Silk Touch."
      },
      {
        "kind": "redstone",
        "label": "Signal rules",
        "text": "When wired into a circuit, this block is conductive, can provide comparator output 0 to 9, and supports redstone dust. It does not emit redstone power by itself."
      },
      {
        "kind": "shape",
        "label": "Physical rules",
        "text": "When the engine checks its shape, this block is an opaque full cube, is not flammable, and cannot be moved by pistons."
      },
      {
        "kind": "behaviour",
        "label": "Behaviour",
        "text": "Its nine recipe slots can be disabled individually before a redstone pulse triggers crafting."
      },
      {
        "kind": "world",
        "label": "Identity",
        "text": "This copper-fronted machine automates crafting recipes."
      }
    ]
  },
  {
    "answer": "Crafting Table",
    "texture": "crafting_table_side",
    "clues": [
      {
        "kind": "data",
        "label": "Engine profile",
        "text": "Under its stored block properties, this block has luminance 0, has no block entity, and makes a note block above it play bass. It appears wood on maps."
      },
      {
        "kind": "destruction",
        "label": "Mining profile",
        "text": "Under harvesting rules, this block has blast resistance 2.5, has hardness 2.5, and uses an axe as its intended tool. Its harvesting rule does not require Silk Touch."
      },
      {
        "kind": "redstone",
        "label": "Signal rules",
        "text": "When wired into a circuit, this block supports redstone dust, is conductive, and provides no comparator output. It does not emit redstone power by itself."
      },
      {
        "kind": "shape",
        "label": "Physical rules",
        "text": "When the engine checks its shape, this block can be moved by pistons, is an opaque full cube, and is not flammable."
      },
      {
        "kind": "behaviour",
        "label": "Behaviour",
        "text": "Using it opens the full three-by-three crafting grid."
      },
      {
        "kind": "world",
        "label": "Identity",
        "text": "Four planks craft this familiar workbench."
      }
    ]
  },
  {
    "answer": "Furnace",
    "texture": "furnace_side",
    "clues": [
      {
        "kind": "data",
        "label": "Engine profile",
        "text": "Under its stored block properties, this block has luminance 0 to 13, makes a note block above it play bass drum, and uses a ticking block entity. It appears stone on maps."
      },
      {
        "kind": "destruction",
        "label": "Mining profile",
        "text": "Under harvesting rules, this block has blast resistance 3.5, uses a pickaxe as its intended tool, and has hardness 3.5. Its harvesting rule does not require Silk Touch."
      },
      {
        "kind": "redstone",
        "label": "Signal rules",
        "text": "When wired into a circuit, this block supports redstone dust, can provide comparator output 0 to 15, and is conductive. It does not emit redstone power by itself."
      },
      {
        "kind": "shape",
        "label": "Physical rules",
        "text": "When the engine checks its shape, this block cannot be moved by pistons, is not flammable, and is an opaque full cube."
      },
      {
        "kind": "behaviour",
        "label": "Behaviour",
        "text": "It consumes fuel to process food, ores, and many other recipes through three inventory slots."
      },
      {
        "kind": "world",
        "label": "Identity",
        "text": "Eight cobblestone arranged in a ring craft this basic smelting block."
      }
    ]
  },
  {
    "answer": "Smoker",
    "texture": "smoker_side",
    "clues": [
      {
        "kind": "data",
        "label": "Engine profile",
        "text": "Under its stored block properties, this block makes a note block above it play bass drum, uses a ticking block entity, and has luminance 0 to 13. It appears stone on maps."
      },
      {
        "kind": "destruction",
        "label": "Mining profile",
        "text": "Under harvesting rules, this block uses a pickaxe as its intended tool, has hardness 3.5, and has blast resistance 3.5. Its harvesting rule does not require Silk Touch."
      },
      {
        "kind": "redstone",
        "label": "Signal rules",
        "text": "When wired into a circuit, this block can provide comparator output 0 to 15, is conductive, and supports redstone dust. It does not emit redstone power by itself."
      },
      {
        "kind": "shape",
        "label": "Physical rules",
        "text": "When the engine checks its shape, this block is not flammable, is an opaque full cube, and cannot be moved by pistons."
      },
      {
        "kind": "behaviour",
        "label": "Behaviour",
        "text": "It cooks food twice as quickly as a normal furnace but cannot process ores."
      },
      {
        "kind": "world",
        "label": "Identity",
        "text": "Logs surrounding a furnace craft this food-focused workstation."
      }
    ]
  },
  {
    "answer": "Blast Furnace",
    "texture": "blast_furnace_side",
    "clues": [
      {
        "kind": "data",
        "label": "Engine profile",
        "text": "Under its stored block properties, this block makes a note block above it play bass drum, has luminance 0 to 13, and uses a ticking block entity. It appears stone on maps."
      },
      {
        "kind": "destruction",
        "label": "Mining profile",
        "text": "Under harvesting rules, this block uses a pickaxe as its intended tool, has blast resistance 3.5, and has hardness 3.5. Its harvesting rule does not require Silk Touch."
      },
      {
        "kind": "redstone",
        "label": "Signal rules",
        "text": "When wired into a circuit, this block can provide comparator output 0 to 15, supports redstone dust, and is conductive. It does not emit redstone power by itself."
      },
      {
        "kind": "shape",
        "label": "Physical rules",
        "text": "When the engine checks its shape, this block is not flammable, cannot be moved by pistons, and is an opaque full cube."
      },
      {
        "kind": "behaviour",
        "label": "Behaviour",
        "text": "It smelts ores, raw metals, armour, and tools at twice normal furnace speed."
      },
      {
        "kind": "world",
        "label": "Identity",
        "text": "Iron ingots and smooth stone upgrade a furnace into this metal-focused workstation."
      }
    ]
  },
  {
    "answer": "Dispenser",
    "texture": "dispenser_front_horizontal",
    "clues": [
      {
        "kind": "data",
        "label": "Engine profile",
        "text": "When its internal data is inspected, this block uses a non-ticking block entity, has luminance 0, and makes a note block above it play bass drum. It appears stone on maps."
      },
      {
        "kind": "destruction",
        "label": "Mining profile",
        "text": "When its breaking properties are checked, this block has hardness 3.5, has blast resistance 3.5, and uses a pickaxe as its intended tool. Its harvesting rule does not require Silk Touch."
      },
      {
        "kind": "redstone",
        "label": "Signal rules",
        "text": "As a redstone surface, this block is conductive, supports redstone dust, and can provide comparator output 0 to 15. It does not emit redstone power by itself."
      },
      {
        "kind": "shape",
        "label": "Physical rules",
        "text": "In its movement profile, this block is an opaque full cube, cannot be moved by pistons, and is not flammable."
      },
      {
        "kind": "behaviour",
        "label": "Behaviour",
        "text": "A redstone pulse makes it actively use many items, including arrows, buckets, and fire charges."
      },
      {
        "kind": "world",
        "label": "Identity",
        "text": "Its recipe combines cobblestone, redstone dust, and a bow."
      }
    ]
  },
  {
    "answer": "Dropper",
    "texture": "dropper_front_horizontal",
    "clues": [
      {
        "kind": "data",
        "label": "Engine profile",
        "text": "When its internal data is inspected, this block uses a non-ticking block entity, makes a note block above it play bass drum, and has luminance 0. It appears stone on maps."
      },
      {
        "kind": "destruction",
        "label": "Mining profile",
        "text": "When its breaking properties are checked, this block has hardness 3.5, uses a pickaxe as its intended tool, and has blast resistance 3.5. Its harvesting rule does not require Silk Touch."
      },
      {
        "kind": "redstone",
        "label": "Signal rules",
        "text": "As a redstone surface, this block is conductive, can provide comparator output 0 to 15, and supports redstone dust. It does not emit redstone power by itself."
      },
      {
        "kind": "shape",
        "label": "Physical rules",
        "text": "In its movement profile, this block is an opaque full cube, is not flammable, and cannot be moved by pistons."
      },
      {
        "kind": "behaviour",
        "label": "Behaviour",
        "text": "A pulse ejects an item or transfers it into a container without using the item."
      },
      {
        "kind": "world",
        "label": "Identity",
        "text": "It resembles a dispenser but its recipe omits the bow."
      }
    ]
  },
  {
    "answer": "Observer",
    "texture": "observer_side",
    "clues": [
      {
        "kind": "data",
        "label": "Engine profile",
        "text": "When its internal data is inspected, this block has luminance 0, has no block entity, and makes a note block above it play bass drum. It appears stone on maps."
      },
      {
        "kind": "destruction",
        "label": "Mining profile",
        "text": "When its breaking properties are checked, this block has blast resistance 3, has hardness 3, and uses a pickaxe as its intended tool. Its harvesting rule does not require Silk Touch."
      },
      {
        "kind": "redstone",
        "label": "Signal rules",
        "text": "As a redstone surface, this block supports redstone dust, is not conductive, and provides no comparator output. It can emit redstone power."
      },
      {
        "kind": "shape",
        "label": "Physical rules",
        "text": "In its movement profile, this block can be moved by pistons, is an opaque full cube, and is not flammable."
      },
      {
        "kind": "behaviour",
        "label": "Behaviour",
        "text": "Its detecting face notices a block update and the opposite red dot emits a short pulse."
      },
      {
        "kind": "world",
        "label": "Identity",
        "text": "This watchful redstone component is crafted with cobblestone, quartz, and redstone dust."
      }
    ]
  },
  {
    "answer": "Note Block",
    "texture": "noteblock",
    "clues": [
      {
        "kind": "data",
        "label": "Engine profile",
        "text": "When its internal data is inspected, this block has luminance 0, makes a note block above it play bass, and has no block entity. It appears wood on maps."
      },
      {
        "kind": "destruction",
        "label": "Mining profile",
        "text": "When its breaking properties are checked, this block has blast resistance 0.8, uses an axe as its intended tool, and has hardness 0.8. Its harvesting rule does not require Silk Touch."
      },
      {
        "kind": "redstone",
        "label": "Signal rules",
        "text": "As a redstone surface, this block supports redstone dust, provides no comparator output, and is conductive. It does not emit redstone power by itself."
      },
      {
        "kind": "shape",
        "label": "Physical rules",
        "text": "In its movement profile, this block can be moved by pistons, is not flammable, and is an opaque full cube."
      },
      {
        "kind": "behaviour",
        "label": "Behaviour",
        "text": "The block beneath it selects an instrument, while repeated use changes its pitch."
      },
      {
        "kind": "world",
        "label": "Identity",
        "text": "Planks surrounding redstone dust craft this musical block."
      }
    ]
  },
  {
    "answer": "Jukebox",
    "texture": "jukebox_side",
    "clues": [
      {
        "kind": "data",
        "label": "Engine profile",
        "text": "When its internal data is inspected, this block makes a note block above it play bass, uses a ticking block entity, and has luminance 0. It appears dirt on maps."
      },
      {
        "kind": "destruction",
        "label": "Mining profile",
        "text": "When its breaking properties are checked, this block uses an axe as its intended tool, has hardness 2, and has blast resistance 6. Its harvesting rule does not require Silk Touch."
      },
      {
        "kind": "redstone",
        "label": "Signal rules",
        "text": "As a redstone surface, this block can provide comparator output 0 to 15, is conductive, and supports redstone dust. It can emit redstone power."
      },
      {
        "kind": "shape",
        "label": "Physical rules",
        "text": "In its movement profile, this block is not flammable, is an opaque full cube, and cannot be moved by pistons."
      },
      {
        "kind": "behaviour",
        "label": "Behaviour",
        "text": "It plays music discs and lets a comparator identify the inserted disc."
      },
      {
        "kind": "world",
        "label": "Identity",
        "text": "A diamond surrounded by planks crafts this music player."
      }
    ]
  },
  {
    "answer": "TNT",
    "texture": "tnt_side",
    "clues": [
      {
        "kind": "data",
        "label": "Engine profile",
        "text": "When its internal data is inspected, this block makes a note block above it play harp, has luminance 0, and has no block entity. It appears fire on maps."
      },
      {
        "kind": "destruction",
        "label": "Mining profile",
        "text": "When its breaking properties are checked, this block is intended to be mined by hand, has blast resistance 0, and has hardness 0. Its harvesting rule does not require Silk Touch."
      },
      {
        "kind": "redstone",
        "label": "Signal rules",
        "text": "As a redstone surface, this block provides no comparator output, supports redstone dust, and is not conductive. It does not emit redstone power by itself."
      },
      {
        "kind": "shape",
        "label": "Physical rules",
        "text": "In its movement profile, this block is flammable, can be moved by pistons, and is an opaque full cube."
      },
      {
        "kind": "behaviour",
        "label": "Behaviour",
        "text": "After ignition it flashes, waits through a short fuse, and creates an explosion."
      },
      {
        "kind": "world",
        "label": "Identity",
        "text": "Alternating sand and gunpowder craft this red explosive block."
      }
    ]
  },
  {
    "answer": "Loom",
    "texture": "loom_side",
    "clues": [
      {
        "kind": "data",
        "label": "Engine profile",
        "text": "According to the block data, this block has no block entity, has luminance 0, and makes a note block above it play bass. It appears wood on maps."
      },
      {
        "kind": "destruction",
        "label": "Mining profile",
        "text": "According to its tool profile, this block has hardness 2.5, has blast resistance 2.5, and uses an axe as its intended tool. Its harvesting rule does not require Silk Touch."
      },
      {
        "kind": "redstone",
        "label": "Signal rules",
        "text": "For signal handling, this block is conductive, supports redstone dust, and provides no comparator output. It does not emit redstone power by itself."
      },
      {
        "kind": "shape",
        "label": "Physical rules",
        "text": "For piston and collision behaviour, this block is an opaque full cube, can be moved by pistons, and is not flammable."
      },
      {
        "kind": "behaviour",
        "label": "Behaviour",
        "text": "It applies banner patterns while using fewer materials than direct crafting recipes."
      },
      {
        "kind": "world",
        "label": "Identity",
        "text": "Two string above two planks craft this shepherd workstation."
      }
    ]
  },
  {
    "answer": "Cartography Table",
    "texture": "cartography_table_side2",
    "clues": [
      {
        "kind": "data",
        "label": "Engine profile",
        "text": "According to the block data, this block has no block entity, makes a note block above it play bass, and has luminance 0. It appears wood on maps."
      },
      {
        "kind": "destruction",
        "label": "Mining profile",
        "text": "According to its tool profile, this block has hardness 2.5, uses an axe as its intended tool, and has blast resistance 2.5. Its harvesting rule does not require Silk Touch."
      },
      {
        "kind": "redstone",
        "label": "Signal rules",
        "text": "For signal handling, this block is conductive, provides no comparator output, and supports redstone dust. It does not emit redstone power by itself."
      },
      {
        "kind": "shape",
        "label": "Physical rules",
        "text": "For piston and collision behaviour, this block is an opaque full cube, is not flammable, and can be moved by pistons."
      },
      {
        "kind": "behaviour",
        "label": "Behaviour",
        "text": "It can copy, enlarge, or lock maps depending on the second item supplied."
      },
      {
        "kind": "world",
        "label": "Identity",
        "text": "Paper and planks craft this map-making workstation used by cartographers."
      }
    ]
  },
  {
    "answer": "Smithing Table",
    "texture": "smithing_table_side",
    "clues": [
      {
        "kind": "data",
        "label": "Engine profile",
        "text": "According to the block data, this block has luminance 0, has no block entity, and makes a note block above it play bass. It appears wood on maps."
      },
      {
        "kind": "destruction",
        "label": "Mining profile",
        "text": "According to its tool profile, this block has blast resistance 2.5, has hardness 2.5, and uses an axe as its intended tool. Its harvesting rule does not require Silk Touch."
      },
      {
        "kind": "redstone",
        "label": "Signal rules",
        "text": "For signal handling, this block supports redstone dust, is conductive, and provides no comparator output. It does not emit redstone power by itself."
      },
      {
        "kind": "shape",
        "label": "Physical rules",
        "text": "For piston and collision behaviour, this block can be moved by pistons, is an opaque full cube, and is not flammable."
      },
      {
        "kind": "behaviour",
        "label": "Behaviour",
        "text": "It applies armour trims and upgrades diamond equipment using smithing templates."
      },
      {
        "kind": "world",
        "label": "Identity",
        "text": "This dark-topped toolsmith workstation is crafted with iron ingots and planks."
      }
    ]
  },
  {
    "answer": "Pumpkin",
    "texture": "pumpkin_side",
    "clues": [
      {
        "kind": "data",
        "label": "Engine profile",
        "text": "According to the block data, this block has luminance 0, makes a note block above it play didgeridoo, and has no block entity. It appears orange on maps."
      },
      {
        "kind": "destruction",
        "label": "Mining profile",
        "text": "According to its tool profile, this block has blast resistance 1, uses an axe as its intended tool, and has hardness 1. Its harvesting rule does not require Silk Touch."
      },
      {
        "kind": "redstone",
        "label": "Signal rules",
        "text": "For signal handling, this block supports redstone dust, provides no comparator output, and is conductive. It does not emit redstone power by itself."
      },
      {
        "kind": "shape",
        "label": "Physical rules",
        "text": "For piston and collision behaviour, this block breaks instead of moving when pushed by a piston, is not flammable, and is an opaque full cube."
      },
      {
        "kind": "behaviour",
        "label": "Behaviour",
        "text": "A stem grows this fruit, and shears can turn its front into a carved face."
      },
      {
        "kind": "world",
        "label": "Identity",
        "text": "This plain orange gourd can be crafted into seeds but cannot be worn yet."
      }
    ]
  },
  {
    "answer": "Carved Pumpkin",
    "texture": "pumpkin_face_off",
    "clues": [
      {
        "kind": "data",
        "label": "Engine profile",
        "text": "According to the block data, this block makes a note block above it play harp, has no block entity, and has luminance 0. It appears orange on maps."
      },
      {
        "kind": "destruction",
        "label": "Mining profile",
        "text": "According to its tool profile, this block uses an axe as its intended tool, has hardness 1, and has blast resistance 1. Its harvesting rule does not require Silk Touch."
      },
      {
        "kind": "redstone",
        "label": "Signal rules",
        "text": "For signal handling, this block provides no comparator output, is conductive, and supports redstone dust. It does not emit redstone power by itself."
      },
      {
        "kind": "shape",
        "label": "Physical rules",
        "text": "For piston and collision behaviour, this block is not flammable, is an opaque full cube, and breaks instead of moving when pushed by a piston."
      },
      {
        "kind": "behaviour",
        "label": "Behaviour",
        "text": "Wearing it prevents Endermen becoming angry when looked at and restricts the player's view."
      },
      {
        "kind": "world",
        "label": "Identity",
        "text": "Shearing a plain pumpkin creates this golem-building mask."
      }
    ]
  },
  {
    "answer": "Jack o'Lantern",
    "texture": "pumpkin_face_on",
    "clues": [
      {
        "kind": "data",
        "label": "Engine profile",
        "text": "According to the block data, this block makes a note block above it play harp, has luminance 15, and has no block entity. It appears orange on maps."
      },
      {
        "kind": "destruction",
        "label": "Mining profile",
        "text": "According to its tool profile, this block uses an axe as its intended tool, has blast resistance 1, and has hardness 1. Its harvesting rule does not require Silk Touch."
      },
      {
        "kind": "redstone",
        "label": "Signal rules",
        "text": "For signal handling, this block provides no comparator output, supports redstone dust, and is conductive. It does not emit redstone power by itself."
      },
      {
        "kind": "shape",
        "label": "Physical rules",
        "text": "For piston and collision behaviour, this block is not flammable, breaks instead of moving when pushed by a piston, and is an opaque full cube."
      },
      {
        "kind": "behaviour",
        "label": "Behaviour",
        "text": "It provides maximum block light while keeping the carved face on one side."
      },
      {
        "kind": "world",
        "label": "Identity",
        "text": "Placing a torch beneath a carved pumpkin crafts this glowing decoration."
      }
    ]
  },
  {
    "answer": "Melon",
    "texture": "melon_side",
    "clues": [
      {
        "kind": "data",
        "label": "Engine profile",
        "text": "At the rules-engine level, this block has no block entity, has luminance 0, and makes a note block above it play harp. It appears light green on maps."
      },
      {
        "kind": "destruction",
        "label": "Mining profile",
        "text": "For survival-mode mining, this block has hardness 1, has blast resistance 1, and uses an axe as its intended tool. Silk Touch is required to collect the block itself."
      },
      {
        "kind": "redstone",
        "label": "Signal rules",
        "text": "In redstone terms, this block is conductive, supports redstone dust, and provides no comparator output. It does not emit redstone power by itself."
      },
      {
        "kind": "shape",
        "label": "Physical rules",
        "text": "According to its physical properties, this block is an opaque full cube, breaks instead of moving when pushed by a piston, and is not flammable."
      },
      {
        "kind": "behaviour",
        "label": "Behaviour",
        "text": "Breaking it normally drops edible slices, while Silk Touch preserves the whole fruit."
      },
      {
        "kind": "world",
        "label": "Identity",
        "text": "This green-striped crop grows from a stem and is common around jungle settlements."
      }
    ]
  },
  {
    "answer": "Bookshelf",
    "texture": "bookshelf",
    "clues": [
      {
        "kind": "data",
        "label": "Engine profile",
        "text": "At the rules-engine level, this block has no block entity, makes a note block above it play bass, and has luminance 0. It appears wood on maps."
      },
      {
        "kind": "destruction",
        "label": "Mining profile",
        "text": "For survival-mode mining, this block has hardness 1.5, uses an axe as its intended tool, and has blast resistance 1.5. Silk Touch is required to collect the block itself."
      },
      {
        "kind": "redstone",
        "label": "Signal rules",
        "text": "In redstone terms, this block is conductive, provides no comparator output, and supports redstone dust. It does not emit redstone power by itself."
      },
      {
        "kind": "shape",
        "label": "Physical rules",
        "text": "According to its physical properties, this block is an opaque full cube, is flammable, and can be moved by pistons."
      },
      {
        "kind": "behaviour",
        "label": "Behaviour",
        "text": "Up to fifteen of them, separated by air, raise an enchanting table's available levels."
      },
      {
        "kind": "world",
        "label": "Identity",
        "text": "Three books between two rows of planks craft this familiar storage decoration."
      }
    ]
  },
  {
    "answer": "Chiseled Bookshelf",
    "texture": "chiseled_bookshelf_occupied",
    "clues": [
      {
        "kind": "data",
        "label": "Engine profile",
        "text": "At the rules-engine level, this block has luminance 0, uses a non-ticking block entity, and makes a note block above it play bass. It appears wood on maps."
      },
      {
        "kind": "destruction",
        "label": "Mining profile",
        "text": "For survival-mode mining, this block has blast resistance 1.5, has hardness 1.5, and uses an axe as its intended tool. Silk Touch is required to collect the block itself."
      },
      {
        "kind": "redstone",
        "label": "Signal rules",
        "text": "In redstone terms, this block supports redstone dust, is conductive, and can provide comparator output 0-6. It does not emit redstone power by itself."
      },
      {
        "kind": "shape",
        "label": "Physical rules",
        "text": "According to its physical properties, this block cannot be moved by pistons, is an opaque full cube, and is not flammable."
      },
      {
        "kind": "behaviour",
        "label": "Behaviour",
        "text": "It stores six individual books and a comparator reports the last slot a player touched."
      },
      {
        "kind": "world",
        "label": "Identity",
        "text": "This carved bookshelf is both functional storage and a redstone input."
      }
    ]
  },
  {
    "answer": "Barrel",
    "texture": "barrel_side",
    "clues": [
      {
        "kind": "data",
        "label": "Engine profile",
        "text": "At the rules-engine level, this block has luminance 0, makes a note block above it play bass, and uses a non-ticking block entity. It appears wood on maps."
      },
      {
        "kind": "destruction",
        "label": "Mining profile",
        "text": "For survival-mode mining, this block has blast resistance 2.5, uses an axe as its intended tool, and has hardness 2.5. Its harvesting rule does not require Silk Touch."
      },
      {
        "kind": "redstone",
        "label": "Signal rules",
        "text": "In redstone terms, this block supports redstone dust, can provide comparator output 0 to 15, and is conductive. It does not emit redstone power by itself."
      },
      {
        "kind": "shape",
        "label": "Physical rules",
        "text": "According to its physical properties, this block cannot be moved by pistons, is not flammable, and is an opaque full cube."
      },
      {
        "kind": "behaviour",
        "label": "Behaviour",
        "text": "Its 27 inventory slots remain accessible even when a solid block sits directly above it."
      },
      {
        "kind": "world",
        "label": "Identity",
        "text": "This compact wooden container is the job-site block for fishermen."
      }
    ]
  },
  {
    "answer": "Beehive",
    "texture": "beehive_front",
    "clues": [
      {
        "kind": "data",
        "label": "Engine profile",
        "text": "At the rules-engine level, this block makes a note block above it play bass, uses a ticking block entity, and has luminance 0. It appears wood on maps."
      },
      {
        "kind": "destruction",
        "label": "Mining profile",
        "text": "For survival-mode mining, this block uses an axe as its intended tool, has hardness 0.6, and has blast resistance 0.6. Silk Touch is required to collect the block itself."
      },
      {
        "kind": "redstone",
        "label": "Signal rules",
        "text": "In redstone terms, this block can provide comparator output 0 to 5, is conductive, and supports redstone dust. It does not emit redstone power by itself."
      },
      {
        "kind": "shape",
        "label": "Physical rules",
        "text": "According to its physical properties, this block is flammable, is an opaque full cube, and cannot be moved by pistons."
      },
      {
        "kind": "behaviour",
        "label": "Behaviour",
        "text": "Smoke calms its occupants while honey level rises after pollen-carrying bees return."
      },
      {
        "kind": "world",
        "label": "Identity",
        "text": "Six planks and three honeycomb craft this player-made counterpart to a bee nest."
      }
    ]
  },
  {
    "answer": "Piston",
    "texture": "piston_side",
    "clues": [
      {
        "kind": "data",
        "label": "Engine profile",
        "text": "At the rules-engine level, this block makes a note block above it play harp, has luminance 0, and has no block entity. It appears stone on maps."
      },
      {
        "kind": "destruction",
        "label": "Mining profile",
        "text": "For survival-mode mining, this block uses a pickaxe as its intended tool, has blast resistance 1.5, and has hardness 1.5. Its harvesting rule does not require Silk Touch."
      },
      {
        "kind": "redstone",
        "label": "Signal rules",
        "text": "In redstone terms, this block provides no comparator output, supports redstone dust only in some states or orientations, and is not conductive. It does not emit redstone power by itself."
      },
      {
        "kind": "shape",
        "label": "Physical rules",
        "text": "According to its physical properties, this block is not flammable, can be moved by pistons only in some states, and is an opaque full cube only in some states."
      },
      {
        "kind": "behaviour",
        "label": "Behaviour",
        "text": "When powered it can push a line of up to twelve movable blocks but cannot pull them back."
      },
      {
        "kind": "world",
        "label": "Identity",
        "text": "This redstone machine extends a wooden face from a stone body."
      }
    ]
  },
  {
    "answer": "Sticky Piston",
    "texture": "piston_top_sticky",
    "clues": [
      {
        "kind": "data",
        "label": "Engine profile",
        "text": "In Minecraft's technical records, this block has no block entity, has luminance 0, and makes a note block above it play harp. It appears stone on maps."
      },
      {
        "kind": "destruction",
        "label": "Mining profile",
        "text": "In the block's destruction data, this block has hardness 1.5, has blast resistance 1.5, and uses a pickaxe as its intended tool. Its harvesting rule does not require Silk Touch."
      },
      {
        "kind": "redstone",
        "label": "Signal rules",
        "text": "Inside a circuit, this block is not conductive, supports redstone dust only in some states or orientations, and provides no comparator output. It does not emit redstone power by itself."
      },
      {
        "kind": "shape",
        "label": "Physical rules",
        "text": "When placed in the world, this block is an opaque full cube only in some states, can be moved by pistons only in some states, and is not flammable."
      },
      {
        "kind": "behaviour",
        "label": "Behaviour",
        "text": "Its extending face can pull most blocks back when power is removed."
      },
      {
        "kind": "world",
        "label": "Identity",
        "text": "Adding a slimeball to a piston creates this green-faced variant."
      }
    ]
  },
  {
    "answer": "Shulker Box",
    "texture": "shulker_top_undyed",
    "clues": [
      {
        "kind": "data",
        "label": "Engine profile",
        "text": "In Minecraft's technical records, this block uses a ticking block entity, makes a note block above it play harp, and has luminance 0. It appears purple on maps."
      },
      {
        "kind": "destruction",
        "label": "Mining profile",
        "text": "In the block's destruction data, this block has hardness 2, uses a pickaxe as its intended tool, and has blast resistance 2. Its harvesting rule does not require Silk Touch."
      },
      {
        "kind": "redstone",
        "label": "Signal rules",
        "text": "Inside a circuit, this block conducts redstone only in some states, can provide comparator output 0 to 15, and supports redstone dust. It does not emit redstone power by itself."
      },
      {
        "kind": "shape",
        "label": "Physical rules",
        "text": "When placed in the world, this block is a full cube only in some states, breaks instead of moving when pushed by a piston, and is not an opaque full cube."
      },
      {
        "kind": "behaviour",
        "label": "Behaviour",
        "text": "It keeps its 27 inventory slots when broken and expands slightly while opening."
      },
      {
        "kind": "world",
        "label": "Identity",
        "text": "A chest between two shulker shells crafts this portable container."
      }
    ]
  },
  {
    "answer": "Lodestone",
    "texture": "lodestone_side",
    "clues": [
      {
        "kind": "data",
        "label": "Engine profile",
        "text": "In Minecraft's technical records, this block has luminance 0, has no block entity, and makes a note block above it play harp. It appears metal on maps."
      },
      {
        "kind": "destruction",
        "label": "Mining profile",
        "text": "In the block's destruction data, this block has blast resistance 3.5, has hardness 3.5, and uses a pickaxe as its intended tool. Its harvesting rule does not require Silk Touch."
      },
      {
        "kind": "redstone",
        "label": "Signal rules",
        "text": "Inside a circuit, this block supports redstone dust, is conductive, and provides no comparator output. It does not emit redstone power by itself."
      },
      {
        "kind": "shape",
        "label": "Physical rules",
        "text": "When placed in the world, this block cannot be moved by pistons, is an opaque full cube, and is not flammable."
      },
      {
        "kind": "behaviour",
        "label": "Behaviour",
        "text": "Using a compass on it binds that compass to this position, even outside the Overworld."
      },
      {
        "kind": "world",
        "label": "Identity",
        "text": "A netherite ingot surrounded by chiseled stone bricks crafts this compass anchor."
      }
    ]
  },
  {
    "answer": "Coal Ore",
    "texture": "coal_ore",
    "clues": [
      {
        "kind": "data",
        "label": "Engine profile",
        "text": "In Minecraft's technical records, this block has luminance 0, makes a note block above it play bass drum, and has no block entity. It appears stone on maps."
      },
      {
        "kind": "destruction",
        "label": "Mining profile",
        "text": "In the block's destruction data, this block has blast resistance 3, uses a pickaxe as its intended tool, and has hardness 3. Silk Touch is required to collect the block itself."
      },
      {
        "kind": "redstone",
        "label": "Signal rules",
        "text": "Inside a circuit, this block supports redstone dust, provides no comparator output, and is conductive. It does not emit redstone power by itself."
      },
      {
        "kind": "shape",
        "label": "Physical rules",
        "text": "When placed in the world, this block can be moved by pistons, is not flammable, and is an opaque full cube."
      },
      {
        "kind": "behaviour",
        "label": "Behaviour",
        "text": "Without Silk Touch it drops coal and experience, with Fortune increasing the coal yield."
      },
      {
        "kind": "world",
        "label": "Identity",
        "text": "Black mineral flecks mark this common fuel ore, especially at higher elevations."
      }
    ]
  },
  {
    "answer": "Iron Ore",
    "texture": "iron_ore",
    "clues": [
      {
        "kind": "data",
        "label": "Engine profile",
        "text": "In Minecraft's technical records, this block makes a note block above it play bass drum, has no block entity, and has luminance 0. It appears stone on maps."
      },
      {
        "kind": "destruction",
        "label": "Mining profile",
        "text": "In the block's destruction data, this block uses a pickaxe as its intended tool, has hardness 3, and has blast resistance 3. Silk Touch is required to collect the block itself."
      },
      {
        "kind": "redstone",
        "label": "Signal rules",
        "text": "Inside a circuit, this block provides no comparator output, is conductive, and supports redstone dust. It does not emit redstone power by itself."
      },
      {
        "kind": "shape",
        "label": "Physical rules",
        "text": "When placed in the world, this block is not flammable, is an opaque full cube, and can be moved by pistons."
      },
      {
        "kind": "behaviour",
        "label": "Behaviour",
        "text": "Mining it drops raw iron, which must be smelted before becoming an ingot."
      },
      {
        "kind": "world",
        "label": "Identity",
        "text": "Beige mineral patches identify this widespread Overworld ore."
      }
    ]
  },
  {
    "answer": "Copper Ore",
    "texture": "copper_ore",
    "clues": [
      {
        "kind": "data",
        "label": "Engine profile",
        "text": "In Minecraft's technical records, this block makes a note block above it play bass drum, has luminance 0, and has no block entity. It appears stone on maps."
      },
      {
        "kind": "destruction",
        "label": "Mining profile",
        "text": "In the block's destruction data, this block uses a pickaxe as its intended tool, has blast resistance 3, and has hardness 3. Silk Touch is required to collect the block itself."
      },
      {
        "kind": "redstone",
        "label": "Signal rules",
        "text": "Inside a circuit, this block provides no comparator output, supports redstone dust, and is conductive. It does not emit redstone power by itself."
      },
      {
        "kind": "shape",
        "label": "Physical rules",
        "text": "When placed in the world, this block is not flammable, can be moved by pistons, and is an opaque full cube."
      },
      {
        "kind": "behaviour",
        "label": "Behaviour",
        "text": "It drops several pieces of raw copper, and Fortune can increase that quantity."
      },
      {
        "kind": "world",
        "label": "Identity",
        "text": "Orange and green mineral flecks identify the ore behind Minecraft's ageing metal."
      }
    ]
  },
  {
    "answer": "Gold Ore",
    "texture": "gold_ore",
    "clues": [
      {
        "kind": "data",
        "label": "Engine profile",
        "text": "Within its registered properties, this block has no block entity, has luminance 0, and makes a note block above it play bass drum. It appears stone on maps."
      },
      {
        "kind": "destruction",
        "label": "Mining profile",
        "text": "Under the engine's mining rules, this block has hardness 3, has blast resistance 3, and uses a pickaxe as its intended tool. Silk Touch is required to collect the block itself."
      },
      {
        "kind": "redstone",
        "label": "Signal rules",
        "text": "For dust and power checks, this block is conductive, supports redstone dust, and provides no comparator output. It does not emit redstone power by itself."
      },
      {
        "kind": "shape",
        "label": "Physical rules",
        "text": "At the block-physics level, this block is an opaque full cube, can be moved by pistons, and is not flammable."
      },
      {
        "kind": "behaviour",
        "label": "Behaviour",
        "text": "It drops raw gold and becomes especially common in badlands biomes."
      },
      {
        "kind": "world",
        "label": "Identity",
        "text": "Bright yellow mineral flecks identify this Overworld precious-metal ore."
      }
    ]
  },
  {
    "answer": "Diamond Ore",
    "texture": "diamond_ore",
    "clues": [
      {
        "kind": "data",
        "label": "Engine profile",
        "text": "Within its registered properties, this block has no block entity, makes a note block above it play bass drum, and has luminance 0. It appears stone on maps."
      },
      {
        "kind": "destruction",
        "label": "Mining profile",
        "text": "Under the engine's mining rules, this block has hardness 3, uses a pickaxe as its intended tool, and has blast resistance 3. Silk Touch is required to collect the block itself."
      },
      {
        "kind": "redstone",
        "label": "Signal rules",
        "text": "For dust and power checks, this block is conductive, provides no comparator output, and supports redstone dust. It does not emit redstone power by itself."
      },
      {
        "kind": "shape",
        "label": "Physical rules",
        "text": "At the block-physics level, this block is an opaque full cube, is not flammable, and can be moved by pistons."
      },
      {
        "kind": "behaviour",
        "label": "Behaviour",
        "text": "Without Silk Touch it drops a gem plus experience, and Fortune can multiply the gems."
      },
      {
        "kind": "world",
        "label": "Identity",
        "text": "Cyan mineral flecks mark this prized ore deep beneath the Overworld."
      }
    ]
  },
  {
    "answer": "Emerald Ore",
    "texture": "emerald_ore",
    "clues": [
      {
        "kind": "data",
        "label": "Engine profile",
        "text": "Within its registered properties, this block has luminance 0, has no block entity, and makes a note block above it play bass drum. It appears stone on maps."
      },
      {
        "kind": "destruction",
        "label": "Mining profile",
        "text": "Under the engine's mining rules, this block has blast resistance 3, has hardness 3, and uses a pickaxe as its intended tool. Silk Touch is required to collect the block itself."
      },
      {
        "kind": "redstone",
        "label": "Signal rules",
        "text": "For dust and power checks, this block supports redstone dust, is conductive, and provides no comparator output. It does not emit redstone power by itself."
      },
      {
        "kind": "shape",
        "label": "Physical rules",
        "text": "At the block-physics level, this block can be moved by pistons, is an opaque full cube, and is not flammable."
      },
      {
        "kind": "behaviour",
        "label": "Behaviour",
        "text": "It usually forms as isolated blocks and is concentrated beneath mountain biomes."
      },
      {
        "kind": "world",
        "label": "Identity",
        "text": "Green mineral flecks identify the rare ore that supplies villager trading currency."
      }
    ]
  },
  {
    "answer": "Redstone Ore",
    "texture": "redstone_ore",
    "clues": [
      {
        "kind": "data",
        "label": "Engine profile",
        "text": "Within its registered properties, this block has luminance 0 to 9, makes a note block above it play bass drum, and has no block entity. It appears stone on maps."
      },
      {
        "kind": "destruction",
        "label": "Mining profile",
        "text": "Under the engine's mining rules, this block has blast resistance 3, uses a pickaxe as its intended tool, and has hardness 3. The source data marks Silk Touch as not applicable to its normal drop rule."
      },
      {
        "kind": "redstone",
        "label": "Signal rules",
        "text": "For dust and power checks, this block supports redstone dust, provides no comparator output, and is conductive. It does not emit redstone power by itself."
      },
      {
        "kind": "shape",
        "label": "Physical rules",
        "text": "At the block-physics level, this block can be moved by pistons, is not flammable, and is an opaque full cube."
      },
      {
        "kind": "behaviour",
        "label": "Behaviour",
        "text": "Touching or striking it briefly lights it, while mining releases several pieces of dust."
      },
      {
        "kind": "world",
        "label": "Identity",
        "text": "Glowing red mineral flecks identify this deep source of redstone dust."
      }
    ]
  },
  {
    "answer": "Lapis Ore",
    "texture": "lapis_ore",
    "clues": [
      {
        "kind": "data",
        "label": "Engine profile",
        "text": "Within its registered properties, this block makes a note block above it play bass drum, has no block entity, and has luminance 0. It appears stone on maps."
      },
      {
        "kind": "destruction",
        "label": "Mining profile",
        "text": "Under the engine's mining rules, this block uses a pickaxe as its intended tool, has hardness 3, and has blast resistance 3. Silk Touch is required to collect the block itself."
      },
      {
        "kind": "redstone",
        "label": "Signal rules",
        "text": "For dust and power checks, this block provides no comparator output, is conductive, and supports redstone dust. It does not emit redstone power by itself."
      },
      {
        "kind": "shape",
        "label": "Physical rules",
        "text": "At the block-physics level, this block is not flammable, is an opaque full cube, and can be moved by pistons."
      },
      {
        "kind": "behaviour",
        "label": "Behaviour",
        "text": "A single block can drop several blue items, with Fortune increasing the total."
      },
      {
        "kind": "world",
        "label": "Identity",
        "text": "Deep blue mineral flecks identify the ore used alongside enchanting tables."
      }
    ]
  },
  {
    "answer": "Nether Gold Ore",
    "texture": "nether_gold_ore",
    "clues": [
      {
        "kind": "data",
        "label": "Engine profile",
        "text": "Within its registered properties, this block makes a note block above it play bass drum, has luminance 0, and has no block entity. It appears nether on maps."
      },
      {
        "kind": "destruction",
        "label": "Mining profile",
        "text": "Under the engine's mining rules, this block uses a pickaxe as its intended tool, has blast resistance 3, and has hardness 3. Silk Touch is required to collect the block itself."
      },
      {
        "kind": "redstone",
        "label": "Signal rules",
        "text": "For dust and power checks, this block provides no comparator output, supports redstone dust, and is conductive. It does not emit redstone power by itself."
      },
      {
        "kind": "shape",
        "label": "Physical rules",
        "text": "At the block-physics level, this block is not flammable, can be moved by pistons, and is an opaque full cube."
      },
      {
        "kind": "behaviour",
        "label": "Behaviour",
        "text": "It normally drops gold nuggets, and nearby piglins object when a player mines it."
      },
      {
        "kind": "world",
        "label": "Identity",
        "text": "Gold flecks embedded in netherrack identify this Nether ore."
      }
    ]
  },
  {
    "answer": "Nether Quartz Ore",
    "texture": "quartz_ore",
    "clues": [
      {
        "kind": "data",
        "label": "Engine profile",
        "text": "From the engine's point of view, this block has no block entity, has luminance 0, and makes a note block above it play bass drum. It appears nether on maps."
      },
      {
        "kind": "destruction",
        "label": "Mining profile",
        "text": "When the game calculates breaking, this block has hardness 3, has blast resistance 3, and uses a pickaxe as its intended tool. Silk Touch is required to collect the block itself."
      },
      {
        "kind": "redstone",
        "label": "Signal rules",
        "text": "In a powered build, this block is conductive, supports redstone dust, and provides no comparator output. It does not emit redstone power by itself."
      },
      {
        "kind": "shape",
        "label": "Physical rules",
        "text": "Under shape and movement checks, this block is an opaque full cube, can be moved by pistons, and is not flammable."
      },
      {
        "kind": "behaviour",
        "label": "Behaviour",
        "text": "Mining it releases quartz and experience unless Silk Touch preserves the ore itself."
      },
      {
        "kind": "world",
        "label": "Identity",
        "text": "White mineral flecks embedded in netherrack identify this common Nether ore."
      }
    ]
  },
  {
    "answer": "Grass Block",
    "texture": "grass_side_carried",
    "clues": [
      {
        "kind": "data",
        "label": "Engine profile",
        "text": "From the engine's point of view, this block has no block entity, makes a note block above it play harp, and has luminance 0. It appears grass on maps."
      },
      {
        "kind": "destruction",
        "label": "Mining profile",
        "text": "When the game calculates breaking, this block has hardness 0.6, uses a shovel as its intended tool, and has blast resistance 0.6. Silk Touch is required to collect the block itself."
      },
      {
        "kind": "redstone",
        "label": "Signal rules",
        "text": "In a powered build, this block is conductive, provides no comparator output, and supports redstone dust. It does not emit redstone power by itself."
      },
      {
        "kind": "shape",
        "label": "Physical rules",
        "text": "Under shape and movement checks, this block is an opaque full cube, is not flammable, and can be moved by pistons."
      },
      {
        "kind": "behaviour",
        "label": "Behaviour",
        "text": "It spreads onto nearby dirt with enough light and returns to dirt when heavily covered."
      },
      {
        "kind": "world",
        "label": "Identity",
        "text": "This green-topped block forms most of the Overworld's natural surface."
      }
    ]
  },
  {
    "answer": "Mycelium",
    "texture": "mycelium_side",
    "clues": [
      {
        "kind": "data",
        "label": "Engine profile",
        "text": "From the engine's point of view, this block has luminance 0, has no block entity, and makes a note block above it play harp. It appears purple on maps."
      },
      {
        "kind": "destruction",
        "label": "Mining profile",
        "text": "When the game calculates breaking, this block has blast resistance 0.6, has hardness 0.6, and uses a shovel as its intended tool. Silk Touch is required to collect the block itself."
      },
      {
        "kind": "redstone",
        "label": "Signal rules",
        "text": "In a powered build, this block supports redstone dust, is conductive, and provides no comparator output. It does not emit redstone power by itself."
      },
      {
        "kind": "shape",
        "label": "Physical rules",
        "text": "Under shape and movement checks, this block can be moved by pistons, is an opaque full cube, and is not flammable."
      },
      {
        "kind": "behaviour",
        "label": "Behaviour",
        "text": "Mushrooms can remain on it at any light level, and it spreads to nearby dirt."
      },
      {
        "kind": "world",
        "label": "Identity",
        "text": "This purple-grey ground naturally carpets mushroom field islands."
      }
    ]
  },
  {
    "answer": "Podzol",
    "texture": "dirt_podzol_side",
    "clues": [
      {
        "kind": "data",
        "label": "Engine profile",
        "text": "From the engine's point of view, this block has luminance 0, makes a note block above it play harp, and has no block entity. It appears podzol on maps."
      },
      {
        "kind": "destruction",
        "label": "Mining profile",
        "text": "When the game calculates breaking, this block has blast resistance 0.5, uses a shovel as its intended tool, and has hardness 0.5. Silk Touch is required to collect the block itself."
      },
      {
        "kind": "redstone",
        "label": "Signal rules",
        "text": "In a powered build, this block supports redstone dust, provides no comparator output, and is conductive. It does not emit redstone power by itself."
      },
      {
        "kind": "shape",
        "label": "Physical rules",
        "text": "Under shape and movement checks, this block can be moved by pistons, is not flammable, and is an opaque full cube."
      },
      {
        "kind": "behaviour",
        "label": "Behaviour",
        "text": "Mushrooms can remain on it in bright light, but grass cannot spread across it."
      },
      {
        "kind": "world",
        "label": "Identity",
        "text": "This brown, needle-covered ground forms beneath giant spruce trees."
      }
    ]
  },
  {
    "answer": "Dirt",
    "texture": "dirt",
    "clues": [
      {
        "kind": "data",
        "label": "Engine profile",
        "text": "From the engine's point of view, this block makes a note block above it play harp, has no block entity, and has luminance 0. It appears dirt on maps."
      },
      {
        "kind": "destruction",
        "label": "Mining profile",
        "text": "When the game calculates breaking, this block uses a shovel as its intended tool, has hardness 0.5, and has blast resistance 0.5. Its harvesting rule does not require Silk Touch."
      },
      {
        "kind": "redstone",
        "label": "Signal rules",
        "text": "In a powered build, this block provides no comparator output, is conductive, and supports redstone dust. It does not emit redstone power by itself."
      },
      {
        "kind": "shape",
        "label": "Physical rules",
        "text": "Under shape and movement checks, this block is not flammable, is an opaque full cube, and can be moved by pistons."
      },
      {
        "kind": "behaviour",
        "label": "Behaviour",
        "text": "A hoe can till it, a shovel can make a path, and a water bottle can turn it into mud."
      },
      {
        "kind": "world",
        "label": "Identity",
        "text": "This ordinary brown block sits beneath grass across much of the Overworld."
      }
    ]
  },
  {
    "answer": "Coarse Dirt",
    "texture": "coarse_dirt",
    "clues": [
      {
        "kind": "data",
        "label": "Engine profile",
        "text": "From the engine's point of view, this block makes a note block above it play harp, has luminance 0, and has no block entity. It appears dirt on maps."
      },
      {
        "kind": "destruction",
        "label": "Mining profile",
        "text": "When the game calculates breaking, this block uses a shovel as its intended tool, has blast resistance 0.5, and has hardness 0.5. Its harvesting rule does not require Silk Touch."
      },
      {
        "kind": "redstone",
        "label": "Signal rules",
        "text": "In a powered build, this block provides no comparator output, supports redstone dust, and is conductive. It does not emit redstone power by itself."
      },
      {
        "kind": "shape",
        "label": "Physical rules",
        "text": "Under shape and movement checks, this block is not flammable, can be moved by pistons, and is an opaque full cube."
      },
      {
        "kind": "behaviour",
        "label": "Behaviour",
        "text": "Grass cannot spread onto it, though using a hoe converts it back into ordinary dirt."
      },
      {
        "kind": "world",
        "label": "Identity",
        "text": "Two dirt and two gravel craft this rough brown ground block."
      }
    ]
  },
  {
    "answer": "Rooted Dirt",
    "texture": "dirt_with_roots",
    "clues": [
      {
        "kind": "data",
        "label": "Engine profile",
        "text": "Under internal property checks, this block has no block entity, has luminance 0, and makes a note block above it play harp. It appears dirt on maps."
      },
      {
        "kind": "destruction",
        "label": "Mining profile",
        "text": "In its recorded harvesting profile, this block has hardness 0.5, has blast resistance 0.5, and uses a shovel as its intended tool. Its harvesting rule does not require Silk Touch."
      },
      {
        "kind": "redstone",
        "label": "Signal rules",
        "text": "As part of redstone logic, this block is conductive, supports redstone dust, and provides no comparator output. It does not emit redstone power by itself."
      },
      {
        "kind": "shape",
        "label": "Physical rules",
        "text": "In the game's physical model, this block is an opaque full cube, can be moved by pistons, and is not flammable."
      },
      {
        "kind": "behaviour",
        "label": "Behaviour",
        "text": "Bonemeal used beneath it produces hanging roots, and a hoe converts it to dirt."
      },
      {
        "kind": "world",
        "label": "Identity",
        "text": "This root-filled soil generates below azalea trees above lush caves."
      }
    ]
  },
  {
    "answer": "Gravel",
    "texture": "gravel",
    "clues": [
      {
        "kind": "data",
        "label": "Engine profile",
        "text": "Under internal property checks, this block has no block entity, makes a note block above it play snare, and has luminance 0. It appears stone on maps."
      },
      {
        "kind": "destruction",
        "label": "Mining profile",
        "text": "In its recorded harvesting profile, this block has hardness 0.6, uses a shovel as its intended tool, and has blast resistance 0.6. Its harvesting rule does not require Silk Touch."
      },
      {
        "kind": "redstone",
        "label": "Signal rules",
        "text": "As part of redstone logic, this block is conductive, provides no comparator output, and supports redstone dust. It does not emit redstone power by itself."
      },
      {
        "kind": "shape",
        "label": "Physical rules",
        "text": "In the game's physical model, this block falls when unsupported, can be moved by pistons, and is an opaque full cube."
      },
      {
        "kind": "behaviour",
        "label": "Behaviour",
        "text": "It falls without support and can drop flint instead of itself when mined."
      },
      {
        "kind": "world",
        "label": "Identity",
        "text": "This loose grey aggregate covers beaches, riverbeds, and parts of the Nether."
      }
    ]
  },
  {
    "answer": "Sand",
    "texture": "sand",
    "clues": [
      {
        "kind": "data",
        "label": "Engine profile",
        "text": "Under internal property checks, this block has luminance 0, has no block entity, and makes a note block above it play snare. It appears sand on maps."
      },
      {
        "kind": "destruction",
        "label": "Mining profile",
        "text": "In its recorded harvesting profile, this block has blast resistance 0.5, has hardness 0.5, and uses a shovel as its intended tool. Its harvesting rule does not require Silk Touch."
      },
      {
        "kind": "redstone",
        "label": "Signal rules",
        "text": "As part of redstone logic, this block supports redstone dust, is conductive, and provides no comparator output. It does not emit redstone power by itself."
      },
      {
        "kind": "shape",
        "label": "Physical rules",
        "text": "In the game's physical model, this block is an opaque full cube, falls when unsupported, and can be moved by pistons."
      },
      {
        "kind": "behaviour",
        "label": "Behaviour",
        "text": "It falls without support and smelting it produces a transparent building block."
      },
      {
        "kind": "world",
        "label": "Identity",
        "text": "This pale granular block covers deserts and many beaches."
      }
    ]
  },
  {
    "answer": "Red Sand",
    "texture": "red_sand",
    "clues": [
      {
        "kind": "data",
        "label": "Engine profile",
        "text": "Under internal property checks, this block has luminance 0, makes a note block above it play snare, and has no block entity. It appears orange on maps."
      },
      {
        "kind": "destruction",
        "label": "Mining profile",
        "text": "In its recorded harvesting profile, this block has blast resistance 0.5, uses a shovel as its intended tool, and has hardness 0.5. Its harvesting rule does not require Silk Touch."
      },
      {
        "kind": "redstone",
        "label": "Signal rules",
        "text": "As part of redstone logic, this block supports redstone dust, provides no comparator output, and is conductive. It does not emit redstone power by itself."
      },
      {
        "kind": "shape",
        "label": "Physical rules",
        "text": "In the game's physical model, this block is an opaque full cube, can be moved by pistons, and falls when unsupported."
      },
      {
        "kind": "behaviour",
        "label": "Behaviour",
        "text": "It falls without support and can be smelted into ordinary glass."
      },
      {
        "kind": "world",
        "label": "Identity",
        "text": "This rust-coloured sand is naturally associated with badlands biomes."
      }
    ]
  },
  {
    "answer": "Soul Sand",
    "texture": "soul_sand",
    "clues": [
      {
        "kind": "data",
        "label": "Engine profile",
        "text": "Under internal property checks, this block makes a note block above it play cow bell, has no block entity, and has luminance 0. It appears brown on maps."
      },
      {
        "kind": "destruction",
        "label": "Mining profile",
        "text": "In its recorded harvesting profile, this block uses a shovel as its intended tool, has hardness 0.5, and has blast resistance 0.5. Its harvesting rule does not require Silk Touch."
      },
      {
        "kind": "redstone",
        "label": "Signal rules",
        "text": "As part of redstone logic, this block provides no comparator output, is conductive, and supports redstone dust. It does not emit redstone power by itself."
      },
      {
        "kind": "shape",
        "label": "Physical rules",
        "text": "In the game's physical model, this block can be moved by pistons, has a movement interaction of slows when walked on or speeds up when walked on, and is an opaque full cube."
      },
      {
        "kind": "behaviour",
        "label": "Behaviour",
        "text": "It slows walkers, supports Nether wart, and creates upward bubble columns under water."
      },
      {
        "kind": "world",
        "label": "Identity",
        "text": "Faces appear trapped across this brown Nether block."
      }
    ]
  },
  {
    "answer": "Soul Soil",
    "texture": "soul_soil",
    "clues": [
      {
        "kind": "data",
        "label": "Engine profile",
        "text": "Under internal property checks, this block makes a note block above it play harp, has luminance 0, and has no block entity. It appears brown on maps."
      },
      {
        "kind": "destruction",
        "label": "Mining profile",
        "text": "In its recorded harvesting profile, this block uses a shovel as its intended tool, has blast resistance 0.5, and has hardness 0.5. Its harvesting rule does not require Silk Touch."
      },
      {
        "kind": "redstone",
        "label": "Signal rules",
        "text": "As part of redstone logic, this block provides no comparator output, supports redstone dust, and is conductive. It does not emit redstone power by itself."
      },
      {
        "kind": "shape",
        "label": "Physical rules",
        "text": "In the game's physical model, this block can be moved by pistons, is an opaque full cube, and has a movement interaction of no or speeds up when walked on."
      },
      {
        "kind": "behaviour",
        "label": "Behaviour",
        "text": "Soul fire burns on it, Soul Speed works above it, and lava beside blue ice can create basalt."
      },
      {
        "kind": "world",
        "label": "Identity",
        "text": "This dark Nether soil resembles soul sand but does not slow ordinary movement."
      }
    ]
  },
  {
    "answer": "Moss Block",
    "texture": "moss_block",
    "clues": [
      {
        "kind": "data",
        "label": "Engine profile",
        "text": "In the game's block table, this block has no block entity, has luminance 0, and makes a note block above it play harp. It appears green on maps."
      },
      {
        "kind": "destruction",
        "label": "Mining profile",
        "text": "For tool and resistance checks, this block has hardness 0.1, has blast resistance 0.1, and uses a hoe as its intended tool. Its harvesting rule does not require Silk Touch."
      },
      {
        "kind": "redstone",
        "label": "Signal rules",
        "text": "For neighbouring components, this block is conductive, supports redstone dust, and provides no comparator output. It does not emit redstone power by itself."
      },
      {
        "kind": "shape",
        "label": "Physical rules",
        "text": "For movement through the world, this block is an opaque full cube, breaks instead of moving when pushed by a piston, and is not flammable."
      },
      {
        "kind": "behaviour",
        "label": "Behaviour",
        "text": "Bonemeal spreads it across replaceable stone and can grow azaleas on its surface."
      },
      {
        "kind": "world",
        "label": "Identity",
        "text": "This soft green block carpets the floors and walls of lush caves."
      }
    ]
  },
  {
    "answer": "Mud",
    "texture": "mud",
    "clues": [
      {
        "kind": "data",
        "label": "Engine profile",
        "text": "In the game's block table, this block has no block entity, makes a note block above it play harp, and has luminance 0. It appears terracotta cyan on maps."
      },
      {
        "kind": "destruction",
        "label": "Mining profile",
        "text": "For tool and resistance checks, this block has hardness 0.5, uses a shovel as its intended tool, and has blast resistance 0.5. Its harvesting rule does not require Silk Touch."
      },
      {
        "kind": "redstone",
        "label": "Signal rules",
        "text": "For neighbouring components, this block is conductive, provides no comparator output, and supports redstone dust. It does not emit redstone power by itself."
      },
      {
        "kind": "shape",
        "label": "Physical rules",
        "text": "For movement through the world, this block is an opaque full cube, is not flammable, and can be moved by pistons."
      },
      {
        "kind": "behaviour",
        "label": "Behaviour",
        "text": "Pointed dripstone beneath it can eventually dry it into clay."
      },
      {
        "kind": "world",
        "label": "Identity",
        "text": "Using a water bottle on dirt creates this low, dark ground found in mangrove swamps."
      }
    ]
  },
  {
    "answer": "Packed Mud",
    "texture": "packed_mud",
    "clues": [
      {
        "kind": "data",
        "label": "Engine profile",
        "text": "In the game's block table, this block has luminance 0, has no block entity, and makes a note block above it play harp. It appears dirt on maps."
      },
      {
        "kind": "destruction",
        "label": "Mining profile",
        "text": "For tool and resistance checks, this block has blast resistance 3, has hardness 1, and uses a pickaxe as its intended tool. Its harvesting rule does not require Silk Touch."
      },
      {
        "kind": "redstone",
        "label": "Signal rules",
        "text": "For neighbouring components, this block supports redstone dust, is conductive, and provides no comparator output. It does not emit redstone power by itself."
      },
      {
        "kind": "shape",
        "label": "Physical rules",
        "text": "For movement through the world, this block can be moved by pistons, is an opaque full cube, and is not flammable."
      },
      {
        "kind": "behaviour",
        "label": "Behaviour",
        "text": "It serves as the compacted ingredient used to craft mud bricks."
      },
      {
        "kind": "world",
        "label": "Identity",
        "text": "Combining mud with wheat creates this firm brown building block."
      }
    ]
  },
  {
    "answer": "Clay",
    "texture": "clay",
    "clues": [
      {
        "kind": "data",
        "label": "Engine profile",
        "text": "In the game's block table, this block has luminance 0, makes a note block above it play flute, and has no block entity. It appears clay on maps."
      },
      {
        "kind": "destruction",
        "label": "Mining profile",
        "text": "For tool and resistance checks, this block has blast resistance 0.6, uses a shovel as its intended tool, and has hardness 0.6. Silk Touch is required to collect the block itself."
      },
      {
        "kind": "redstone",
        "label": "Signal rules",
        "text": "For neighbouring components, this block supports redstone dust, provides no comparator output, and is conductive. It does not emit redstone power by itself."
      },
      {
        "kind": "shape",
        "label": "Physical rules",
        "text": "For movement through the world, this block can be moved by pistons, is not flammable, and is an opaque full cube."
      },
      {
        "kind": "behaviour",
        "label": "Behaviour",
        "text": "Breaking it normally yields four clay balls, which can be smelted into bricks."
      },
      {
        "kind": "world",
        "label": "Identity",
        "text": "This smooth grey block forms around water and beneath lush caves."
      }
    ]
  },
  {
    "answer": "Snow Block",
    "texture": "snow",
    "clues": [
      {
        "kind": "data",
        "label": "Engine profile",
        "text": "In the game's block table, this block makes a note block above it play harp, has no block entity, and has luminance 0. It appears snow on maps."
      },
      {
        "kind": "destruction",
        "label": "Mining profile",
        "text": "For tool and resistance checks, this block uses a shovel as its intended tool, has hardness 0.2, and has blast resistance 0.2. Silk Touch is required to collect the block itself."
      },
      {
        "kind": "redstone",
        "label": "Signal rules",
        "text": "For neighbouring components, this block provides no comparator output, is conductive, and supports redstone dust. It does not emit redstone power by itself."
      },
      {
        "kind": "shape",
        "label": "Physical rules",
        "text": "For movement through the world, this block is not flammable, is an opaque full cube, and can be moved by pistons."
      },
      {
        "kind": "behaviour",
        "label": "Behaviour",
        "text": "Four snowballs craft it, and placing two beneath a carved pumpkin creates a snow golem."
      },
      {
        "kind": "world",
        "label": "Identity",
        "text": "This solid white block accumulates throughout cold and snowy biomes."
      }
    ]
  },
  {
    "answer": "Ice",
    "texture": "ice",
    "clues": [
      {
        "kind": "data",
        "label": "Engine profile",
        "text": "In the game's block table, this block makes a note block above it play harp, has luminance 0, and has no block entity. It appears ice on maps."
      },
      {
        "kind": "destruction",
        "label": "Mining profile",
        "text": "For tool and resistance checks, this block uses a pickaxe as its intended tool, has blast resistance 0.5, and has hardness 0.5. Silk Touch is required to collect the block itself."
      },
      {
        "kind": "redstone",
        "label": "Signal rules",
        "text": "For neighbouring components, this block provides no comparator output, supports redstone dust, and is not conductive. It does not emit redstone power by itself."
      },
      {
        "kind": "shape",
        "label": "Physical rules",
        "text": "For movement through the world, this block is not an opaque full cube, is a full cube, and has a movement interaction of slippery."
      },
      {
        "kind": "behaviour",
        "label": "Behaviour",
        "text": "It is slippery, melts under sufficient block light, and normally requires Silk Touch to collect."
      },
      {
        "kind": "world",
        "label": "Identity",
        "text": "This transparent frozen-water block covers cold lakes and rivers."
      }
    ]
  },
  {
    "answer": "Packed Ice",
    "texture": "ice_packed",
    "clues": [
      {
        "kind": "data",
        "label": "Engine profile",
        "text": "When the block registry describes it, this block has no block entity, has luminance 0, and makes a note block above it play chime. It appears ice on maps."
      },
      {
        "kind": "destruction",
        "label": "Mining profile",
        "text": "At the block-data level, this block has hardness 0.5, has blast resistance 0.5, and uses a pickaxe as its intended tool. Silk Touch is required to collect the block itself."
      },
      {
        "kind": "redstone",
        "label": "Signal rules",
        "text": "In signal tests, this block is conductive, supports redstone dust, and provides no comparator output. It does not emit redstone power by itself."
      },
      {
        "kind": "shape",
        "label": "Physical rules",
        "text": "When neighbouring blocks affect it, this block has a movement interaction of slippery, is an opaque full cube, and can be moved by pistons."
      },
      {
        "kind": "behaviour",
        "label": "Behaviour",
        "text": "It stays frozen near light sources and is more slippery than ordinary ice."
      },
      {
        "kind": "world",
        "label": "Identity",
        "text": "Nine ice blocks craft this cloudy blue material found in icebergs."
      }
    ]
  },
  {
    "answer": "Blue Ice",
    "texture": "blue_ice",
    "clues": [
      {
        "kind": "data",
        "label": "Engine profile",
        "text": "When the block registry describes it, this block has no block entity, makes a note block above it play harp, and has luminance 0. It appears ice on maps."
      },
      {
        "kind": "destruction",
        "label": "Mining profile",
        "text": "At the block-data level, this block has hardness 2.8, uses a pickaxe as its intended tool, and has blast resistance 2.8. Silk Touch is required to collect the block itself."
      },
      {
        "kind": "redstone",
        "label": "Signal rules",
        "text": "In signal tests, this block is conductive, provides no comparator output, and supports redstone dust. It does not emit redstone power by itself."
      },
      {
        "kind": "shape",
        "label": "Physical rules",
        "text": "When neighbouring blocks affect it, this block has a movement interaction of slippery, can be moved by pistons, and is an opaque full cube."
      },
      {
        "kind": "behaviour",
        "label": "Behaviour",
        "text": "Boats move exceptionally quickly across it, making it the slipperiest solid ice variant."
      },
      {
        "kind": "world",
        "label": "Identity",
        "text": "Nine packed ice blocks craft this deep-blue iceberg material."
      }
    ]
  },
  {
    "answer": "Wet Sponge",
    "texture": "sponge_wet",
    "clues": [
      {
        "kind": "data",
        "label": "Engine profile",
        "text": "When the block registry describes it, this block has luminance 0, has no block entity, and makes a note block above it play harp. It appears yellow on maps."
      },
      {
        "kind": "destruction",
        "label": "Mining profile",
        "text": "At the block-data level, this block has blast resistance 0.6, has hardness 0.6, and uses a hoe as its intended tool. Its harvesting rule does not require Silk Touch."
      },
      {
        "kind": "redstone",
        "label": "Signal rules",
        "text": "In signal tests, this block supports redstone dust, is conductive, and provides no comparator output. It does not emit redstone power by itself."
      },
      {
        "kind": "shape",
        "label": "Physical rules",
        "text": "When neighbouring blocks affect it, this block can be moved by pistons, is an opaque full cube, and is not flammable."
      },
      {
        "kind": "behaviour",
        "label": "Behaviour",
        "text": "It dries instantly in the Nether or gradually in a furnace, where a bucket can collect the water."
      },
      {
        "kind": "world",
        "label": "Identity",
        "text": "This darkened saturated block is what remains after a sponge absorbs nearby water."
      }
    ]
  },
  {
    "answer": "Tinted Glass",
    "texture": "tinted_glass",
    "clues": [
      {
        "kind": "data",
        "label": "Engine profile",
        "text": "When the block registry describes it, this block has luminance 0, makes a note block above it play hat, and has no block entity. It appears gray on maps."
      },
      {
        "kind": "destruction",
        "label": "Mining profile",
        "text": "At the block-data level, this block has blast resistance 0.3, uses a pickaxe as its intended tool, and has hardness 0.3. Its harvesting rule does not require Silk Touch."
      },
      {
        "kind": "redstone",
        "label": "Signal rules",
        "text": "In signal tests, this block supports redstone dust, provides no comparator output, and is not conductive. It does not emit redstone power by itself."
      },
      {
        "kind": "shape",
        "label": "Physical rules",
        "text": "When neighbouring blocks affect it, this block is not an opaque full cube, can be moved by pistons, and is a full cube."
      },
      {
        "kind": "behaviour",
        "label": "Behaviour",
        "text": "Players can see through it, but unlike normal glass it completely blocks light."
      },
      {
        "kind": "world",
        "label": "Identity",
        "text": "Amethyst shards surrounding glass craft this dark transparent block."
      }
    ]
  },
  {
    "answer": "Glass",
    "texture": "glass",
    "clues": [
      {
        "kind": "data",
        "label": "Engine profile",
        "text": "When the block registry describes it, this block makes a note block above it play hat, has no block entity, and has luminance 0. It has no map colour."
      },
      {
        "kind": "destruction",
        "label": "Mining profile",
        "text": "At the block-data level, this block uses a pickaxe as its intended tool, has hardness 0.3, and has blast resistance 0.3. Silk Touch is required to collect the block itself."
      },
      {
        "kind": "redstone",
        "label": "Signal rules",
        "text": "In signal tests, this block provides no comparator output, is not conductive, and supports redstone dust. It does not emit redstone power by itself."
      },
      {
        "kind": "shape",
        "label": "Physical rules",
        "text": "When neighbouring blocks affect it, this block can be moved by pistons, is a full cube, and is not an opaque full cube."
      },
      {
        "kind": "behaviour",
        "label": "Behaviour",
        "text": "It is transparent and fragile, and breaking it normally leaves no item behind."
      },
      {
        "kind": "world",
        "label": "Identity",
        "text": "Smelting sand creates this clear building block."
      }
    ]
  },
  {
    "answer": "Amethyst Block",
    "texture": "amethyst_block",
    "clues": [
      {
        "kind": "data",
        "label": "Engine profile",
        "text": "When the block registry describes it, this block makes a note block above it play harp, has luminance 0, and has no block entity. It appears purple on maps."
      },
      {
        "kind": "destruction",
        "label": "Mining profile",
        "text": "At the block-data level, this block uses a pickaxe as its intended tool, has blast resistance 1.5, and has hardness 1.5. Its harvesting rule does not require Silk Touch."
      },
      {
        "kind": "redstone",
        "label": "Signal rules",
        "text": "In signal tests, this block provides no comparator output, supports redstone dust, and is conductive. It does not emit redstone power by itself."
      },
      {
        "kind": "shape",
        "label": "Physical rules",
        "text": "When neighbouring blocks affect it, this block is not flammable, can be moved by pistons, and is an opaque full cube."
      },
      {
        "kind": "behaviour",
        "label": "Behaviour",
        "text": "Walking on it and striking it produce resonant chime sounds, but buds cannot grow from it."
      },
      {
        "kind": "world",
        "label": "Identity",
        "text": "Four amethyst shards craft this purple crystal block found inside geodes."
      }
    ]
  },
  {
    "answer": "Calcite",
    "texture": "calcite",
    "clues": [
      {
        "kind": "data",
        "label": "Engine profile",
        "text": "Across its core engine values, this block has no block entity, has luminance 0, and makes a note block above it play bass drum. It appears terracotta white on maps."
      },
      {
        "kind": "destruction",
        "label": "Mining profile",
        "text": "Within its destruction properties, this block has hardness 0.75, has blast resistance 0.75, and uses a pickaxe as its intended tool. Its harvesting rule does not require Silk Touch."
      },
      {
        "kind": "redstone",
        "label": "Signal rules",
        "text": "From a circuit's perspective, this block is conductive, supports redstone dust, and provides no comparator output. It does not emit redstone power by itself."
      },
      {
        "kind": "shape",
        "label": "Physical rules",
        "text": "Inside the block physics system, this block is an opaque full cube, can be moved by pistons, and is not flammable."
      },
      {
        "kind": "behaviour",
        "label": "Behaviour",
        "text": "It has no crafting recipe and is used mainly as a pale decorative stone."
      },
      {
        "kind": "world",
        "label": "Identity",
        "text": "This chalky white layer surrounds the inner crystals of amethyst geodes."
      }
    ]
  },
  {
    "answer": "Dripstone Block",
    "texture": "dripstone_block",
    "clues": [
      {
        "kind": "data",
        "label": "Engine profile",
        "text": "Across its core engine values, this block has no block entity, makes a note block above it play bass drum, and has luminance 0. It appears terracotta brown on maps."
      },
      {
        "kind": "destruction",
        "label": "Mining profile",
        "text": "Within its destruction properties, this block has hardness 1.5, uses a pickaxe as its intended tool, and has blast resistance 1. Its harvesting rule does not require Silk Touch."
      },
      {
        "kind": "redstone",
        "label": "Signal rules",
        "text": "From a circuit's perspective, this block is conductive, provides no comparator output, and supports redstone dust. It does not emit redstone power by itself."
      },
      {
        "kind": "shape",
        "label": "Physical rules",
        "text": "Inside the block physics system, this block is an opaque full cube, is not flammable, and can be moved by pistons."
      },
      {
        "kind": "behaviour",
        "label": "Behaviour",
        "text": "Pointed dripstone can grow from it when water and suitable space are present."
      },
      {
        "kind": "world",
        "label": "Identity",
        "text": "Four pointed dripstone pieces craft this brown cave block."
      }
    ]
  },
  {
    "answer": "Tuff",
    "texture": "tuff",
    "clues": [
      {
        "kind": "data",
        "label": "Engine profile",
        "text": "Across its core engine values, this block has luminance 0, has no block entity, and makes a note block above it play bass drum. It appears terracotta gray on maps."
      },
      {
        "kind": "destruction",
        "label": "Mining profile",
        "text": "Within its destruction properties, this block has blast resistance 6, has hardness 1.5, and uses a pickaxe as its intended tool. Its harvesting rule does not require Silk Touch."
      },
      {
        "kind": "redstone",
        "label": "Signal rules",
        "text": "From a circuit's perspective, this block supports redstone dust, is conductive, and provides no comparator output. It does not emit redstone power by itself."
      },
      {
        "kind": "shape",
        "label": "Physical rules",
        "text": "Inside the block physics system, this block can be moved by pistons, is an opaque full cube, and is not flammable."
      },
      {
        "kind": "behaviour",
        "label": "Behaviour",
        "text": "It can be cut into polished, brick, and chiseled decorative families."
      },
      {
        "kind": "world",
        "label": "Identity",
        "text": "This rough grey-green stone appears deep underground and around some ore veins."
      }
    ]
  },
  {
    "answer": "Bone Block",
    "texture": "bone_block_side",
    "clues": [
      {
        "kind": "data",
        "label": "Engine profile",
        "text": "Across its core engine values, this block has luminance 0, makes a note block above it play xylophone, and has no block entity. It appears sand on maps."
      },
      {
        "kind": "destruction",
        "label": "Mining profile",
        "text": "Within its destruction properties, this block has blast resistance 2, uses a pickaxe as its intended tool, and has hardness 2. Its harvesting rule does not require Silk Touch."
      },
      {
        "kind": "redstone",
        "label": "Signal rules",
        "text": "From a circuit's perspective, this block supports redstone dust, provides no comparator output, and is conductive. It does not emit redstone power by itself."
      },
      {
        "kind": "shape",
        "label": "Physical rules",
        "text": "Inside the block physics system, this block can be moved by pistons, is not flammable, and is an opaque full cube."
      },
      {
        "kind": "behaviour",
        "label": "Behaviour",
        "text": "Its striped texture rotates with placement direction, and crafting reverses it into nine bone meal."
      },
      {
        "kind": "world",
        "label": "Identity",
        "text": "Nine bone meal craft this pale block found in fossil structures."
      }
    ]
  },
  {
    "answer": "Hay Block",
    "texture": "hay_block_side",
    "clues": [
      {
        "kind": "data",
        "label": "Engine profile",
        "text": "Across its core engine values, this block makes a note block above it play banjo, has no block entity, and has luminance 0. It appears yellow on maps."
      },
      {
        "kind": "destruction",
        "label": "Mining profile",
        "text": "Within its destruction properties, this block uses a hoe as its intended tool, has hardness 0.5, and has blast resistance 0.5. Its harvesting rule does not require Silk Touch."
      },
      {
        "kind": "redstone",
        "label": "Signal rules",
        "text": "From a circuit's perspective, this block provides no comparator output, is conductive, and supports redstone dust. It does not emit redstone power by itself."
      },
      {
        "kind": "shape",
        "label": "Physical rules",
        "text": "Inside the block physics system, this block is flammable, is an opaque full cube, and can be moved by pistons."
      },
      {
        "kind": "behaviour",
        "label": "Behaviour",
        "text": "Landing on it greatly reduces fall damage, and llamas can eat it for breeding."
      },
      {
        "kind": "world",
        "label": "Identity",
        "text": "Nine wheat craft this bound golden bale."
      }
    ]
  },
  {
    "answer": "Dried Kelp Block",
    "texture": "dried_kelp_side_a",
    "clues": [
      {
        "kind": "data",
        "label": "Engine profile",
        "text": "Across its core engine values, this block makes a note block above it play harp, has luminance 0, and has no block entity. It appears green on maps."
      },
      {
        "kind": "destruction",
        "label": "Mining profile",
        "text": "Within its destruction properties, this block uses a hoe as its intended tool, has blast resistance 2.5, and has hardness 0.5. Its harvesting rule does not require Silk Touch."
      },
      {
        "kind": "redstone",
        "label": "Signal rules",
        "text": "From a circuit's perspective, this block provides no comparator output, supports redstone dust, and is conductive. It does not emit redstone power by itself."
      },
      {
        "kind": "shape",
        "label": "Physical rules",
        "text": "Inside the block physics system, this block is flammable, can be moved by pistons, and is an opaque full cube."
      },
      {
        "kind": "behaviour",
        "label": "Behaviour",
        "text": "One block can fuel the smelting of twenty items, making it stronger than the pieces used to craft it."
      },
      {
        "kind": "world",
        "label": "Identity",
        "text": "Nine dried kelp craft this dark green food and fuel block."
      }
    ]
  },
  {
    "answer": "Honeycomb Block",
    "texture": "honeycomb",
    "clues": [
      {
        "kind": "data",
        "label": "Engine profile",
        "text": "In its underlying data profile, this block has no block entity, has luminance 0, and makes a note block above it play harp. It appears orange on maps."
      },
      {
        "kind": "destruction",
        "label": "Mining profile",
        "text": "When a player tries to collect it, this block has hardness 0.6, has blast resistance 0.6, and has no intended harvesting tool. Its harvesting rule does not require Silk Touch."
      },
      {
        "kind": "redstone",
        "label": "Signal rules",
        "text": "For redstone connectivity, this block is conductive, supports redstone dust, and provides no comparator output. It does not emit redstone power by itself."
      },
      {
        "kind": "shape",
        "label": "Physical rules",
        "text": "Across its placement properties, this block is an opaque full cube, can be moved by pistons, and is not flammable."
      },
      {
        "kind": "behaviour",
        "label": "Behaviour",
        "text": "It is decorative and does not share the sticky movement effects of its bottled-honey counterpart."
      },
      {
        "kind": "world",
        "label": "Identity",
        "text": "Four honeycomb pieces craft this orange hexagonal block."
      }
    ]
  },
  {
    "answer": "Shroomlight",
    "texture": "shroomlight",
    "clues": [
      {
        "kind": "data",
        "label": "Engine profile",
        "text": "In its underlying data profile, this block has no block entity, makes a note block above it play harp, and has luminance 15. It appears red on maps."
      },
      {
        "kind": "destruction",
        "label": "Mining profile",
        "text": "When a player tries to collect it, this block has hardness 1, uses a hoe as its intended tool, and has blast resistance 1. Its harvesting rule does not require Silk Touch."
      },
      {
        "kind": "redstone",
        "label": "Signal rules",
        "text": "For redstone connectivity, this block is conductive, provides no comparator output, and supports redstone dust. It does not emit redstone power by itself."
      },
      {
        "kind": "shape",
        "label": "Physical rules",
        "text": "Across its placement properties, this block is an opaque full cube, is not flammable, and can be moved by pistons."
      },
      {
        "kind": "behaviour",
        "label": "Behaviour",
        "text": "It emits maximum block light and can be composted despite behaving like a solid block."
      },
      {
        "kind": "world",
        "label": "Identity",
        "text": "This glowing block grows inside the huge fungi of crimson and warped forests."
      }
    ]
  },
  {
    "answer": "Nether Wart Block",
    "texture": "nether_wart_block",
    "clues": [
      {
        "kind": "data",
        "label": "Engine profile",
        "text": "In its underlying data profile, this block has luminance 0, has no block entity, and makes a note block above it play harp. It appears red on maps."
      },
      {
        "kind": "destruction",
        "label": "Mining profile",
        "text": "When a player tries to collect it, this block has blast resistance 1, has hardness 1, and uses a hoe as its intended tool. Its harvesting rule does not require Silk Touch."
      },
      {
        "kind": "redstone",
        "label": "Signal rules",
        "text": "For redstone connectivity, this block supports redstone dust, is conductive, and provides no comparator output. It does not emit redstone power by itself."
      },
      {
        "kind": "shape",
        "label": "Physical rules",
        "text": "Across its placement properties, this block can be moved by pistons, is an opaque full cube, and is not flammable."
      },
      {
        "kind": "behaviour",
        "label": "Behaviour",
        "text": "Nine Nether wart craft it, but the block cannot be converted back into those nine items."
      },
      {
        "kind": "world",
        "label": "Identity",
        "text": "This dense red canopy makes up the crowns of huge crimson fungi."
      }
    ]
  },
  {
    "answer": "Warped Wart Block",
    "texture": "warped_wart_block",
    "clues": [
      {
        "kind": "data",
        "label": "Engine profile",
        "text": "In its underlying data profile, this block has luminance 0, makes a note block above it play harp, and has no block entity. It appears warped wart block on maps."
      },
      {
        "kind": "destruction",
        "label": "Mining profile",
        "text": "When a player tries to collect it, this block has blast resistance 1, uses a hoe as its intended tool, and has hardness 1. Its harvesting rule does not require Silk Touch."
      },
      {
        "kind": "redstone",
        "label": "Signal rules",
        "text": "For redstone connectivity, this block supports redstone dust, provides no comparator output, and is conductive. It does not emit redstone power by itself."
      },
      {
        "kind": "shape",
        "label": "Physical rules",
        "text": "Across its placement properties, this block can be moved by pistons, is not flammable, and is an opaque full cube."
      },
      {
        "kind": "behaviour",
        "label": "Behaviour",
        "text": "Unlike its red counterpart, it cannot be crafted directly from a crop item."
      },
      {
        "kind": "world",
        "label": "Identity",
        "text": "This dense teal canopy makes up the crowns of huge warped fungi."
      }
    ]
  },
  {
    "answer": "Crimson Nylium",
    "texture": "crimson_nylium_side",
    "clues": [
      {
        "kind": "data",
        "label": "Engine profile",
        "text": "In its underlying data profile, this block makes a note block above it play bass drum, has no block entity, and has luminance 0. It appears crimson nylium on maps."
      },
      {
        "kind": "destruction",
        "label": "Mining profile",
        "text": "When a player tries to collect it, this block uses a pickaxe as its intended tool, has hardness 0.4, and has blast resistance 0.4. Silk Touch is required to collect the block itself."
      },
      {
        "kind": "redstone",
        "label": "Signal rules",
        "text": "For redstone connectivity, this block provides no comparator output, is conductive, and supports redstone dust. It does not emit redstone power by itself."
      },
      {
        "kind": "shape",
        "label": "Physical rules",
        "text": "Across its placement properties, this block is not flammable, is an opaque full cube, and can be moved by pistons."
      },
      {
        "kind": "behaviour",
        "label": "Behaviour",
        "text": "Bonemeal grows crimson roots and fungi on it, while nearby netherrack can convert to match it."
      },
      {
        "kind": "world",
        "label": "Identity",
        "text": "This red fungal ground covers crimson forests in the Nether."
      }
    ]
  },
  {
    "answer": "Warped Nylium",
    "texture": "warped_nylium_side",
    "clues": [
      {
        "kind": "data",
        "label": "Engine profile",
        "text": "In its underlying data profile, this block makes a note block above it play bass drum, has luminance 0, and has no block entity. It appears warped nylium on maps."
      },
      {
        "kind": "destruction",
        "label": "Mining profile",
        "text": "When a player tries to collect it, this block uses a pickaxe as its intended tool, has blast resistance 0.4, and has hardness 0.4. Silk Touch is required to collect the block itself."
      },
      {
        "kind": "redstone",
        "label": "Signal rules",
        "text": "For redstone connectivity, this block provides no comparator output, supports redstone dust, and is conductive. It does not emit redstone power by itself."
      },
      {
        "kind": "shape",
        "label": "Physical rules",
        "text": "Across its placement properties, this block is not flammable, can be moved by pistons, and is an opaque full cube."
      },
      {
        "kind": "behaviour",
        "label": "Behaviour",
        "text": "Bonemeal grows warped roots and fungi on it, while nearby netherrack can convert to match it."
      },
      {
        "kind": "world",
        "label": "Identity",
        "text": "This teal fungal ground covers warped forests in the Nether."
      }
    ]
  },
  {
    "answer": "Netherrack",
    "texture": "netherrack",
    "clues": [
      {
        "kind": "data",
        "label": "Engine profile",
        "text": "For internal block handling, this block has no block entity, has luminance 0, and makes a note block above it play bass drum. It appears nether on maps."
      },
      {
        "kind": "destruction",
        "label": "Mining profile",
        "text": "In Minecraft's internal mining table, this block has hardness 0.4, has blast resistance 0.4, and uses a pickaxe as its intended tool. Its harvesting rule does not require Silk Touch."
      },
      {
        "kind": "redstone",
        "label": "Signal rules",
        "text": "Within redstone machinery, this block is conductive, supports redstone dust, and provides no comparator output. It does not emit redstone power by itself."
      },
      {
        "kind": "shape",
        "label": "Physical rules",
        "text": "For piston, fire, and shape rules, this block is an opaque full cube, can be moved by pistons, and is not flammable."
      },
      {
        "kind": "behaviour",
        "label": "Behaviour",
        "text": "It breaks extremely quickly, and fire placed on top burns indefinitely."
      },
      {
        "kind": "world",
        "label": "Identity",
        "text": "This soft red stone forms most of the Nether's terrain."
      }
    ]
  },
  {
    "answer": "End Stone",
    "texture": "end_stone",
    "clues": [
      {
        "kind": "data",
        "label": "Engine profile",
        "text": "For internal block handling, this block has no block entity, makes a note block above it play bass drum, and has luminance 0. It appears sand on maps."
      },
      {
        "kind": "destruction",
        "label": "Mining profile",
        "text": "In Minecraft's internal mining table, this block has hardness 3, uses a pickaxe as its intended tool, and has blast resistance 9. Its harvesting rule does not require Silk Touch."
      },
      {
        "kind": "redstone",
        "label": "Signal rules",
        "text": "Within redstone machinery, this block is conductive, provides no comparator output, and supports redstone dust. It does not emit redstone power by itself."
      },
      {
        "kind": "shape",
        "label": "Physical rules",
        "text": "For piston, fire, and shape rules, this block is an opaque full cube, is not flammable, and can be moved by pistons."
      },
      {
        "kind": "behaviour",
        "label": "Behaviour",
        "text": "It resists explosions better than ordinary stone and forms beneath most Endermen."
      },
      {
        "kind": "world",
        "label": "Identity",
        "text": "This pale, pockmarked block makes up the End's main island terrain."
      }
    ]
  },
  {
    "answer": "Purpur Block",
    "texture": "purpur_block",
    "clues": [
      {
        "kind": "data",
        "label": "Engine profile",
        "text": "For internal block handling, this block has luminance 0, has no block entity, and makes a note block above it play bass drum. It appears magenta on maps."
      },
      {
        "kind": "destruction",
        "label": "Mining profile",
        "text": "In Minecraft's internal mining table, this block has blast resistance 6, has hardness 1.5, and uses a pickaxe as its intended tool. Its harvesting rule does not require Silk Touch."
      },
      {
        "kind": "redstone",
        "label": "Signal rules",
        "text": "Within redstone machinery, this block supports redstone dust, is conductive, and provides no comparator output. It does not emit redstone power by itself."
      },
      {
        "kind": "shape",
        "label": "Physical rules",
        "text": "For piston, fire, and shape rules, this block can be moved by pistons, is an opaque full cube, and is not flammable."
      },
      {
        "kind": "behaviour",
        "label": "Behaviour",
        "text": "Popped chorus fruit crafts it and can also turn it into pillars, slabs, and stairs."
      },
      {
        "kind": "world",
        "label": "Identity",
        "text": "This smooth purple block is the signature building material of End cities."
      }
    ]
  },
  {
    "answer": "Quartz Block",
    "texture": "quartz_block_side",
    "clues": [
      {
        "kind": "data",
        "label": "Engine profile",
        "text": "For internal block handling, this block has luminance 0, makes a note block above it play bass drum, and has no block entity. It appears quartz on maps."
      },
      {
        "kind": "destruction",
        "label": "Mining profile",
        "text": "In Minecraft's internal mining table, this block has blast resistance 0.8, uses a pickaxe as its intended tool, and has hardness 0.8. Its harvesting rule does not require Silk Touch."
      },
      {
        "kind": "redstone",
        "label": "Signal rules",
        "text": "Within redstone machinery, this block supports redstone dust, provides no comparator output, and is conductive. It does not emit redstone power by itself."
      },
      {
        "kind": "shape",
        "label": "Physical rules",
        "text": "For piston, fire, and shape rules, this block can be moved by pistons, is not flammable, and is an opaque full cube."
      },
      {
        "kind": "behaviour",
        "label": "Behaviour",
        "text": "Four Nether quartz craft it, and it belongs to a white decorative family with pillars and chiseling."
      },
      {
        "kind": "world",
        "label": "Identity",
        "text": "This bright white mineral block is strongly associated with elegant Nether-derived builds."
      }
    ]
  },
  {
    "answer": "End Stone Bricks",
    "texture": "end_bricks",
    "clues": [
      {
        "kind": "data",
        "label": "Engine profile",
        "text": "For internal block handling, this block makes a note block above it play bass drum, has no block entity, and has luminance 0. It appears sand on maps."
      },
      {
        "kind": "destruction",
        "label": "Mining profile",
        "text": "In Minecraft's internal mining table, this block uses a pickaxe as its intended tool, has hardness 3, and has blast resistance 9. Its harvesting rule does not require Silk Touch."
      },
      {
        "kind": "redstone",
        "label": "Signal rules",
        "text": "Within redstone machinery, this block provides no comparator output, is conductive, and supports redstone dust. It does not emit redstone power by itself."
      },
      {
        "kind": "shape",
        "label": "Physical rules",
        "text": "For piston, fire, and shape rules, this block is not flammable, is an opaque full cube, and can be moved by pistons."
      },
      {
        "kind": "behaviour",
        "label": "Behaviour",
        "text": "Four pieces of its base stone craft this brick without requiring a furnace or stonecutter."
      },
      {
        "kind": "world",
        "label": "Identity",
        "text": "These pale yellow bricks are used throughout End city structures."
      }
    ]
  },
  {
    "answer": "Prismarine",
    "texture": "prismarine_rough",
    "clues": [
      {
        "kind": "data",
        "label": "Engine profile",
        "text": "For internal block handling, this block makes a note block above it play bass drum, has luminance 0, and has no block entity. It appears cyan on maps."
      },
      {
        "kind": "destruction",
        "label": "Mining profile",
        "text": "In Minecraft's internal mining table, this block uses a pickaxe as its intended tool, has blast resistance 6, and has hardness 1.5. Its harvesting rule does not require Silk Touch."
      },
      {
        "kind": "redstone",
        "label": "Signal rules",
        "text": "Within redstone machinery, this block provides no comparator output, supports redstone dust, and is conductive. It does not emit redstone power by itself."
      },
      {
        "kind": "shape",
        "label": "Physical rules",
        "text": "For piston, fire, and shape rules, this block is not flammable, can be moved by pistons, and is an opaque full cube."
      },
      {
        "kind": "behaviour",
        "label": "Behaviour",
        "text": "Its texture slowly cycles between blue, green, and purple tones."
      },
      {
        "kind": "world",
        "label": "Identity",
        "text": "This shifting cyan stone forms much of an ocean monument."
      }
    ]
  },
  {
    "answer": "Dark Prismarine",
    "texture": "prismarine_dark",
    "clues": [
      {
        "kind": "data",
        "label": "Engine profile",
        "text": "When Minecraft evaluates its properties, this block has no block entity, has luminance 0, and makes a note block above it play bass drum. It appears diamond on maps."
      },
      {
        "kind": "destruction",
        "label": "Mining profile",
        "text": "For hardness and harvesting, this block has hardness 1.5, has blast resistance 6, and uses a pickaxe as its intended tool. Its harvesting rule does not require Silk Touch."
      },
      {
        "kind": "redstone",
        "label": "Signal rules",
        "text": "Under signal-behaviour checks, this block is conductive, supports redstone dust, and provides no comparator output. It does not emit redstone power by itself."
      },
      {
        "kind": "shape",
        "label": "Physical rules",
        "text": "When Minecraft evaluates its form, this block is an opaque full cube, can be moved by pistons, and is not flammable."
      },
      {
        "kind": "behaviour",
        "label": "Behaviour",
        "text": "Its recipe combines prismarine shards with black dye."
      },
      {
        "kind": "world",
        "label": "Identity",
        "text": "This dark teal block creates the strongest accents in ocean monuments."
      }
    ]
  },
  {
    "answer": "Prismarine Bricks",
    "texture": "prismarine_bricks",
    "clues": [
      {
        "kind": "data",
        "label": "Engine profile",
        "text": "When Minecraft evaluates its properties, this block has no block entity, makes a note block above it play bass drum, and has luminance 0. It appears diamond on maps."
      },
      {
        "kind": "destruction",
        "label": "Mining profile",
        "text": "For hardness and harvesting, this block has hardness 1.5, uses a pickaxe as its intended tool, and has blast resistance 6. Its harvesting rule does not require Silk Touch."
      },
      {
        "kind": "redstone",
        "label": "Signal rules",
        "text": "Under signal-behaviour checks, this block is conductive, provides no comparator output, and supports redstone dust. It does not emit redstone power by itself."
      },
      {
        "kind": "shape",
        "label": "Physical rules",
        "text": "When Minecraft evaluates its form, this block is an opaque full cube, is not flammable, and can be moved by pistons."
      },
      {
        "kind": "behaviour",
        "label": "Behaviour",
        "text": "Nine prismarine shards craft its regular tiled pattern."
      },
      {
        "kind": "world",
        "label": "Identity",
        "text": "These pale cyan bricks form patterned sections of ocean monuments."
      }
    ]
  },
  {
    "answer": "Gilded Blackstone",
    "texture": "gilded_blackstone",
    "clues": [
      {
        "kind": "data",
        "label": "Engine profile",
        "text": "When Minecraft evaluates its properties, this block has luminance 0, has no block entity, and makes a note block above it play bass drum. It appears black on maps."
      },
      {
        "kind": "destruction",
        "label": "Mining profile",
        "text": "For hardness and harvesting, this block has blast resistance 6, has hardness 1.5, and uses a pickaxe as its intended tool. Its harvesting rule does not require Silk Touch."
      },
      {
        "kind": "redstone",
        "label": "Signal rules",
        "text": "Under signal-behaviour checks, this block supports redstone dust, is conductive, and provides no comparator output. It does not emit redstone power by itself."
      },
      {
        "kind": "shape",
        "label": "Physical rules",
        "text": "When Minecraft evaluates its form, this block can be moved by pistons, is an opaque full cube, and is not flammable."
      },
      {
        "kind": "behaviour",
        "label": "Behaviour",
        "text": "Mining it can drop gold nuggets instead of the block, and nearby piglins become angry."
      },
      {
        "kind": "world",
        "label": "Identity",
        "text": "This black-and-gold block is hidden among the walls and treasure areas of bastion remnants."
      }
    ]
  },
  {
    "answer": "Blackstone",
    "texture": "blackstone",
    "clues": [
      {
        "kind": "data",
        "label": "Engine profile",
        "text": "When Minecraft evaluates its properties, this block has luminance 0, makes a note block above it play bass drum, and has no block entity. It appears black on maps."
      },
      {
        "kind": "destruction",
        "label": "Mining profile",
        "text": "For hardness and harvesting, this block has blast resistance 6, uses a pickaxe as its intended tool, and has hardness 1.5. Its harvesting rule does not require Silk Touch."
      },
      {
        "kind": "redstone",
        "label": "Signal rules",
        "text": "Under signal-behaviour checks, this block supports redstone dust, provides no comparator output, and is conductive. It does not emit redstone power by itself."
      },
      {
        "kind": "shape",
        "label": "Physical rules",
        "text": "When Minecraft evaluates its form, this block can be moved by pistons, is not flammable, and is an opaque full cube."
      },
      {
        "kind": "behaviour",
        "label": "Behaviour",
        "text": "It can replace cobblestone in many recipes and has polished and brick variants."
      },
      {
        "kind": "world",
        "label": "Identity",
        "text": "This dark Nether stone is abundant around basalt deltas and bastion remnants."
      }
    ]
  },
  {
    "answer": "Deepslate",
    "texture": "deepslate/deepslate",
    "clues": [
      {
        "kind": "data",
        "label": "Engine profile",
        "text": "When Minecraft evaluates its properties, this block makes a note block above it play bass drum, has no block entity, and has luminance 0. It appears deepslate on maps."
      },
      {
        "kind": "destruction",
        "label": "Mining profile",
        "text": "For hardness and harvesting, this block uses a pickaxe as its intended tool, has hardness 3, and has blast resistance 6. Its harvesting rule does not require Silk Touch."
      },
      {
        "kind": "redstone",
        "label": "Signal rules",
        "text": "Under signal-behaviour checks, this block provides no comparator output, is conductive, and supports redstone dust. It does not emit redstone power by itself."
      },
      {
        "kind": "shape",
        "label": "Physical rules",
        "text": "When Minecraft evaluates its form, this block is not flammable, is an opaque full cube, and can be moved by pistons."
      },
      {
        "kind": "behaviour",
        "label": "Behaviour",
        "text": "Its top and side textures differ, and normal mining turns it into a cobbled form."
      },
      {
        "kind": "world",
        "label": "Identity",
        "text": "This dense dark stone replaces ordinary stone deep in the Overworld."
      }
    ]
  },
  {
    "answer": "Smooth Basalt",
    "texture": "smooth_basalt",
    "clues": [
      {
        "kind": "data",
        "label": "Engine profile",
        "text": "When Minecraft evaluates its properties, this block makes a note block above it play bass drum, has luminance 0, and has no block entity. It appears black on maps."
      },
      {
        "kind": "destruction",
        "label": "Mining profile",
        "text": "For hardness and harvesting, this block uses a pickaxe as its intended tool, has blast resistance 4.2, and has hardness 1.25. Its harvesting rule does not require Silk Touch."
      },
      {
        "kind": "redstone",
        "label": "Signal rules",
        "text": "Under signal-behaviour checks, this block provides no comparator output, supports redstone dust, and is conductive. It does not emit redstone power by itself."
      },
      {
        "kind": "shape",
        "label": "Physical rules",
        "text": "When Minecraft evaluates its form, this block is not flammable, can be moved by pistons, and is an opaque full cube."
      },
      {
        "kind": "behaviour",
        "label": "Behaviour",
        "text": "Smelting basalt creates it, despite the name suggesting a stonecutter recipe."
      },
      {
        "kind": "world",
        "label": "Identity",
        "text": "This smooth grey block forms the outer shell of amethyst geodes."
      }
    ]
  }
] as const satisfies readonly BlockHuntPuzzle[];
