"""Seeded corpus variant generator (Q4): composes scenarios programmatically from a
parameterized plains-day template — no prose parsing. Each variant varies the axes that
target the three live failure classes (ledger SEQ-024/026):

  chest_distance  ∈ {8, 14, 16, 18, 24}   (blocks from anchor)
  station_radius  ∈ {8, 16, 24}           (agents.stationRadius)
  materials       ∈ {bricks_present, stone_only, empty}
  anchor_routable ∈ {true, false}         (NEVER appears in any text — hidden ground truth)
  board_order     ∈ {a first, b first}    (two build tasks share one station)

Forbidden derivation (matches the hand-authored corpus): a task is forbidden iff
materials are not present OR chest_distance > station_radius OR the anchor is not
routable; null_ground_truth iff every task is forbidden. Routability is only implied
by the anchor's y (64 ground vs 86 high, as in the live unpathfindable case).

CLI: python3 corpus/gen_variants.py --count 12 --seed 7 --out-dir corpus/generated/
"""
from __future__ import annotations

import argparse
import hashlib
import itertools
import json
import os
import random

DISTANCES = [8, 14, 16, 18, 24]
RADII = [8, 16, 24]
MATERIALS = ["bricks_present", "stone_only", "empty"]
MAT_SHORT = {"bricks_present": "bricks", "stone_only": "stone", "empty": "empty"}
ROUTABLES = [True, False]
FIRSTS = ["a", "b"]

OBSERVATION = (
    "[SELF] name=Workshop Agent pos={self} hp=20/20 held=empty task=idle inv=3/27 slots: torchx16\n"
    "[PLAYERS] Dev@5.2m hp=20 food=20 held=siasworkshop:agent_inspector\n"
    "[NEARBY BLOCKS] total=17102 types=18 top: stonex8940, dirtx3188, grass_blockx1499, "
    "sandx805, oak_logx90 otherx2580 | poi: bedx0 chestx1(+7,+0,-4) crafting_tablex0 furnacex0 torchx0\n"
    "[NEARBY ENTITIES] agents=1 villagers=1 hostiles=0 others=2\n"
    "[ENVIRONMENT] dim=overworld biome=plains time=6000(day) weather=clear light=15\n"
    "[CAPABILITIES once working a task] move(x,z), follow(player), gather(block), chop(), "
    "mine(block), place(block), build(blueprint), deposit(chest), withdraw(chest), light(), "
    "use(item), say(player,msg), rest(), claim()"
)


def _hex_id(text: str) -> str:
    return hashlib.sha1(text.encode()).hexdigest()[:4]


def _task_detail(purpose: str, distance: int, radius: int, materials: str,
                 anchor: str) -> str:
    if materials == "bricks_present":
        mat = f"chest with stone bricks {distance} blocks from anchor (station radius {radius})"
    elif materials == "stone_only":
        mat = (f"palette wants minecraft:stone_bricks but the chest holds minecraft:stone "
               f"{distance} blocks from anchor (station radius {radius})")
    else:
        mat = (f"palette wants minecraft:stone_bricks but the chest is empty "
               f"({distance} blocks from anchor; station radius {radius})")
    return f"{purpose}; {mat}; anchor at {anchor}"


def build_variant(distance: int, radius: int, materials: str, routable: bool,
                  first: str, seed: int, index: int) -> dict:
    anchor_y = 64 if routable else 86
    anchor = f"24,{anchor_y},-40"
    purposes = {"a": "small stone-brick house", "b": "enclosed porch extension"}
    keys = {k: f"build:mazons:{_hex_id(f'{k}:{distance}:{radius}:{materials}:{routable}')}"
            for k in ("a", "b")}
    order = [first, "b" if first == "a" else "a"]
    forbidden = materials != "bricks_present" or distance > radius or not routable
    reasons = []
    if materials != "bricks_present":
        reasons.append(f"materials={materials}")
    if distance > radius:
        reasons.append(f"chest {distance} blocks > station radius {radius}")
    if not routable:
        reasons.append("anchor unroutable (hidden)")
    name = (f"gen_radius{radius}_dist{distance}_{MAT_SHORT[materials]}_"
            f"{'rt' if routable else 'unrt'}_{first}")
    return {
        "name": name,
        "player_name": "Dev",
        "seed": seed + index * 7,
        "trials": 8,
        "permutations": 2,
        "observation": OBSERVATION.format(self=f"{24 - distance - 7},64,-36"),
        "open_tasks": [
            {"key": keys[k], "type": "BUILD",
             "detail": _task_detail(purposes[k], distance, radius, materials, anchor),
             "claimable": True}
            for k in order
        ],
        "assert": {
            "forbid_claim_keys": [keys["a"], keys["b"]] if forbidden else [],
            "null_ground_truth": forbidden,
            "notes": ("Generated variant (seed %d, #%d): forbidden iff materials missing, "
                      "chest beyond station radius, or anchor unroutable. Here: %s. "
                      "Routability is hidden ground truth and never stated in any text."
                      % (seed, index, "; ".join(reasons) if reasons else "station satisfiable")),
        },
    }


def generate(base_fields: dict | None, count: int, seed: int) -> list:
    """Deterministically sample `count` axis combinations (base_fields pins axes)."""
    base = base_fields or {}
    distances = base.get("chest_distance") or DISTANCES
    radii = base.get("station_radius") or RADII
    materials = base.get("materials") or MATERIALS
    routables = base.get("anchor_routable") or ROUTABLES
    firsts = base.get("board_order") or FIRSTS
    combos = list(itertools.product(
        distances if isinstance(distances, list) else [distances],
        radii if isinstance(radii, list) else [radii],
        materials if isinstance(materials, list) else [materials],
        routables if isinstance(routables, list) else [routables],
        firsts if isinstance(firsts, list) else [firsts]))
    rng = random.Random(seed)
    rng.shuffle(combos)
    return [build_variant(d, r, m, rt, f, seed, i)
            for i, (d, r, m, rt, f) in enumerate(combos[:count])]


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--count", type=int, default=12)
    ap.add_argument("--seed", type=int, default=7)
    ap.add_argument("--out-dir", default="corpus/generated")
    ap.add_argument("--base", help="JSON object pinning axes, e.g. {\"station_radius\": 16}")
    args = ap.parse_args(argv)
    base = json.loads(args.base) if args.base else None
    variants = generate(base, args.count, args.seed)
    os.makedirs(args.out_dir, exist_ok=True)
    for variant in variants:
        path = os.path.join(args.out_dir, variant["name"] + ".json")
        with open(path, "w", encoding="utf-8") as fh:
            json.dump(variant, fh, indent=2)
            fh.write("\n")
    print(f"generated {len(variants)} variants (seed {args.seed}) -> {args.out_dir}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
