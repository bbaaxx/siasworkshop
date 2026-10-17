---
id: "research-digest-player-1-synthetic-game-agents--2026-10-17"
title: "Research digest: Player-1 synthetic-experience game agents + Minecraft MCP server landscape"
status: "complete"
created: "2026-10-17"
updated: "2026-10-17"
owner: "coordinator"
tags: ["research","llm","mcp","agents","paper-digest","game-agents","rl"]
related_wiki: ["reference/mcp-pattern-tree-design", "setup/agent-shared-ledger", "setup/session-handoff-2026-10-17", "reference/player-1-game-agents", "reference/minecraft-mcp-servers"]
priority: "medium"
graduated: "2026-10-17"
---

## Objective

Digest two research sources the user paused development for (handoff
2026-10-17) before any new agent/MCP code, and capture durable learnings in
the wiki under `reference/`:

1. **Player-1 paper** — "Player-1: Learning Game Control Beyond Demonstrations
   through Synthetic Experience" (arXiv 2610.20584, v1 2026-10-14, Ouyang et
   al.): a post-training framework extending computer-use agents to game
   control — extended action space (relative pointer motion, sustained inputs,
   composite actions), 6,044 curated SFT gameplay trajectories, Player-1-Gym
   (16 synthetic 2D/3D environments with programmatically verifiable rewards,
   randomized observations and control dynamics), multi-env PPO RL on top.
   Player-1-9B reaches 38.33% on MineDojo-300 vs 12.63% strongest baseline
   (+27.8pp over its own SFT policy); gains transfer to ViZDoom-10 and
   GameWorld.
2. **An existing Minecraft MCP server implementation** — link pending from the
   user; to be digested after the paper, against the axes the handoff lists
   (tool catalog shape, transport, auth/security model, multi-step session
   state, single-player vs dedicated server, mod-loader ecosystem relevance).

## Relationship to our work

- Our stack: LLM plans at a high level via MCP pattern trees; the mod executes
  leaf effects. Player-1 is the complementary axis — low-level *control*
  (pixel/keyboard-mouse-level policies) trained with RL in synthetic
  environments. The digest must map where (if anywhere) a trained control
  policy layer fits under our "LLM plans, mod executes" north star, and what
  the verifiable-reward / synthetic-environment methodology suggests for our
  scenario corpus (Q4 of mcp-model-behavior-research).
- The Minecraft MCP server digest positions our sidecar design against an
  existing implementation before we invest in hot-plug P2 (debugPort attach).

## Out of scope (recorded)

- JEVA (arXiv 2610.18773, decision-native architecture: null-aware,
  permutation-invariant, option-scalable, cacheable decisions) — user noted it
  is relevant but **not** for this initiative; parked for possible future
  digest against the task-board decision layer.

## Plan

- [x] Fetch and fully read the Player-1 paper (HTML full text)
- [x] Write digest wiki page `reference/player-1-game-agents` — architecture,
      action-space extensions, SFT data curation, Player-1-Gym reward design,
      RL results, transfer findings; what applies to us and what does not
- [x] Map onto our stack: verifiable-reward environments vs our scenario
      corpus; composite actions vs our pattern-tree leaf effects; where a
      control policy could sit under the MCP pattern tree (or why not)
- [x] Receive the Minecraft MCP server link from the user; digest against the
      handoff's six axes; locate where it fits relative to our sidecar design
      (normalization layer, tool-registry ownership, debug access)
      → DONE (2026-10-17): yuniko-software/minecraft-mcp-server v2.0.4 studied
      via local clone; wiki `reference/minecraft-mcp-servers`; landscape scan
      confirms all open MC-MCP servers are Mineflayer protocol-bots (client
      side, no server code) — ours is the only mod-side registry + sidecar
      design; multi-client attach (hot-plug P2) unclaimed by anyone
- [x] Cross-link both open workstreams (mcp-model-behavior-research,
      mcp-hotplug-debug-access); run `mdocs validate`

## Progress Log

- [2026-10-17] Initiative opened per user direction ("we want this well
      documented in the wiki"). User started us on the paper to gain full
      understanding and tune in to where the MCP server may come into the
      picture. First link pasted was JEVA (2610.18773) — user corrected to
      Player-1 (2610.20584); JEVA recorded as out-of-scope-but-relevant.
      Note: the session MCP `initiative.create` reported success but never
      persisted to disk (in-memory state divergence) — initiative file
      hand-written instead, per the ledger's documented trap.
- [2026-10-17] Paper digest DONE: full HTML read; wiki page
      `reference/player-1-game-agents` written (pipeline, action-space
      extensions, SFT curation, Player-1-Gym reward/randomization design,
      results, transfer, cost). Key exports for our stack: (1) SFT/profile
      init dominates RL — Base→RL +4pp vs SFT→RL +34pp at equal compute,
      so sidecar profiles/prompts are the value layer for any future
      usage-derived learning; (2) verifiable-reward recipe (dense score
      increments over privileged state, invalid-call penalty with wait
      fallback, reasoning-length penalty, no terminal penalties) = template
      for corpus Q4 assertions; (3) randomization axes → corpus diversity
      axes (targets our three failure classes); (4) coverage-over-trials
      metrics with CIs for regression runs. Boundary recorded: no semantic
      interface in the paper, 64×H200×36h RL — inspiration for eval/reward
      design only, not architecture. Sharpens the MCP-server half: a MC MCP
      server is the structured-channel school (same as our sidecar); digest
      compares catalog/transport/auth/multi-client/session-state. Noted:
      Player-1 related work cites TypeSafe "Jev" — the user's parked JEVA
      paste is the decision-layer sibling of this control-layer paper.
- [2026-10-17] MCP server digest DONE per user direction ("use what we are
      learning to boost our own development by reusing aspects"): studied
      yuniko minecraft-mcp-server v2.0.4 (Apache-2.0, ~1.8k LOC TS) via local
      clone; wiki `reference/minecraft-mcp-servers`. Architecture: Mineflayer
      protocol-bot, stdio MCP, 17 flat verb tools, no server-side code — works
      on any Java server incl. LAN-opened SP. Landscape scan: every open
      MC-MCP server is the same client-side school (G0Osey99 123-tool variant,
      structure-builder, prototypes); none owns an in-process mod-side
      registry; multi-client attach unclaimed → validates our design and
      hot-plug P2. Adoption candidates recorded against existing workstreams:
      executor watchdog/per-move timeout (fixes arrival-circling),
      hard-reject vs soft-world-no taxonomy + per-field validation messages
      (corpus Q4 metrics), sidecar stdio hygiene check (feeds P1 tap),
      observation ring buffer. Not adopted: flat catalogs, bot-as-player
      embodiment, LAN no-auth trust model. User confirmed Jev/JEVA study is
      next after these two items.
