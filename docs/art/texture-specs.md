# Texture Specs — Sia's Workshop v1.0.0

Two textures need final art. Stub files are already wired into the mod (models, blockstates,
and lang all reference these paths) — **replace the stub PNGs file-for-file, keeping the exact
file names and locations**. Nothing else in the mod needs to change when the real art lands.

Conventions for both:

- PNG, 16×16 pixels, indexed or true color both fine.
- Minecraft renders these tiny — bold silhouettes, 1–2 px outlines, limited palettes.
- Keep the visual center at pixel (8, 8); items render centered on that pivot.

---

## 1. Village Foundation — block texture

- **File:** `src/main/resources/assets/siasworkshop/textures/block/village_foundation.png`
- **Size:** 16×16, **fully opaque** (no transparency — it skins all six faces of a full cube).
- **What the block is:** a placed marker block that *generates an entire village* on top of
  itself when the player confirms in its GUI (flavor choice: cherry blossom or vanilla).
  It should read as a "foundation" or cornerstone — something you'd ceremonially place to
  found a settlement.
- **Current stub:** warm-gray border (stone footing) around a cherry-pink plank center.
- **Direction:** a cornerstone/foundation slab look. Cherry blossom accents are on-brand
  (the mod's signature material). Should sit nicely next to vanilla planks/stone bricks.
  Avoid anything that reads as ore, chest, or utility machine.

## 2. Wilderness Compass — item texture, 32 frames

- **Files:** `src/main/resources/assets/siasworkshop/textures/item/wilderness_compass_00.png`
  through `wilderness_compass_31.png` — **32 separate 16×16 PNGs**, numbered exactly like that.
- **Size:** 16×16 per frame, **transparent background** (item `layer0` rendering).
- **What the item is:** a compass that scans for the *nearest village* and whose needle points
  **away** from it — it tells you "you need at least this much separation to found a village
  here." Cherry blossom themed; the mod's other signature item.
- **Frame semantics:** each frame is the **full compass face** (dial + needle), needle rotated
  per frame. Frame `16` points straight up. Frame numbers increase clockwise in
  11.25° steps (1/32 of a full turn): `_00` points 180° (down), `_08` points left,
  `_16` up, `_24` right. The dial must be pixel-identical across all 32 frames; only the
  needle rotates around the center pivot (8, 8). Vanilla's `minecraft:item/compass_00..31`
  set is the reference for layout and readability.
- **Wobble note:** the game animates between frames with slight wobble, exactly like the
  vanilla compass — adjacent frames must be rotation-consistent or the needle will jump.
- **Fallback option:** if 32 rotated frames are too costly, deliver frame `_16` (needle up)
  as a single static texture and tell us — we can point all overrides at one frame for v1
  and ship the animated set later.

## Handoff checklist

- [ ] `village_foundation.png` — 16×16, opaque
- [ ] `wilderness_compass_00.png` … `wilderness_compass_31.png` — 32 × 16×16, alpha
- [ ] All file names exactly as listed above
- [ ] Files dropped into the two directories above, committed — done (no code changes needed)
