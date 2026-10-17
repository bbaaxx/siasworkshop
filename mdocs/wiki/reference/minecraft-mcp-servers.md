---
id: "minecraft-mcp-servers"
title: "Minecraft MCP Server Landscape + yuniko server deep digest (adoption candidates)"
category: "reference"
created: "2026-10-17"
updated: "2026-10-17"
related_initiatives: ["research-digest-player-1-synthetic-game-agents--2026-10-17", "mcp-hotplug-debug-access--2026-10-17", "mcp-model-behavior-research--2026-10-15", "research-digest-jev-jeva-decision-layer--2026-10-17"]
tags: ["mcp","minecraft","agents","reference","landscape","tool-catalog"]
lifecycle: "evolving"
knowledge_type: "reference"
confidence: "high"
source_initiatives: ["research-digest-player-1-synthetic-game-agents--2026-10-17"]
---

# Minecraft MCP servers digest

Second source of the 2026-10-17 research pause. Primary subject:
[yuniko-software/minecraft-mcp-server](https://github.com/yuniko-software/minecraft-mcp-server)
v2.0.4, studied 2026-10-17 via local clone (repo removed after study; ~1,800
LOC TypeScript). License Apache-2.0 — copying is legally permitted with
attribution, but per repo licensing stance we **imitate patterns, never copy
code**.

## What it is

A Node/TypeScript MCP server (official MCP SDK, stdio transport) that spawns a
**Mineflayer bot** — a protocol-level Minecraft *client* — and exposes 17 flat
verb tools so an LLM client (Claude Desktop is the documented target) can drive
a player character: move, look, jump, fly, inventory, place/dig blocks, find
blocks/entities, furnace smelting, chat read/send, gamemode detection.

**Architectural essence: zero server-side code.** It connects to any Java
server — singleplayer opened to LAN, or a dedicated server — because it speaks
the client protocol like a human player. The game needs no mod, no plugin, no
config. Everything the bot can see or do is exactly what a player could.

## The handoff's six axes

| Axis | yuniko server | Ours (sidecar + mod) |
| --- | --- | --- |
| Tool catalog | Flat, 17 imperative verbs, zod schemas, no tree, no versioning | Leveled pattern trees; catalog = stable interface; mod owns registry |
| Transport | stdio only (JSON-RPC MCP), stdout filtered to keep channel clean | Symmetric newline-JSON-RPC over child stdio; same filtering concern |
| Auth/security | None. LAN/offline-mode bot; trust = whoever launched the client | Localhost-only, config-gated (`agents.mcp.*`, default OFF), dev-only |
| Multi-step session state | None server-side. Continuity lives in the LLM client chat + a 100-message chat ring buffer + reconnecting connection | Sessions vs task board; convergence polling; sidecar holds profile/prompt state |
| SP vs dedicated | Both — protocol level is agnostic | In-process NeoForge mod; server-side only by nature |
| Loader ecosystem | None — vanilla game, no loader | NeoForge 1.21.1 mod (Java 21), Python stdlib sidecar |

## Implementation details worth stealing (patterns, not code)

1. **Uniform tool-call wrapper** (`tool-factory.ts`): every call first runs a
   connection pre-check with **auto-reconnect** (2 s backoff, poll up to 5 s),
   and returns an actionable multi-line error ("server running? version
   compatible?") instead of a bare failure. Arg validation via zod with
   per-field, human/model-readable messages. → Adopt: our `McpTools` rejects
   should name the bad field + expected shape; sidecar-down errors should stay
   actionable (we already fall back to template; keep the reason in the log).
2. **Soft-fail vs hard-error contract**: domain misses ("no chest within 16
   blocks", "already a block there") return normal text responses; only
   exceptions and connection loss set `isError`. The LLM reads the soft
   failure and adapts its next call. → Adopt for our reject taxonomy:
   distinguish *invalid call* (hard reject) from *world said no* (structured
   soft result). S1 already showed reject-recovery works; making the
   distinction explicit improves it.
3. **Forgiving tools hide multi-step physical work**: `place-block` walks into
   view range, then tries all six placement faces before giving up;
   `move-to-position` wraps pathfinding with an optional `timeoutMs` and
   explicit cancel (pathfinder.stop()). `dig-block` auto-approaches. Tool
   semantics are "**make it so**", not "attempt exactly one thing". → Adopt
   for our executor warts: the arrival-circling stuck-detector gap and
   `withdrawOnce` first-chest-only limitation are exactly this class — per-move
   timeout + no-progress watchdog + alternate-approach retry before fail.
4. **Chat ring buffer** (`message-store.ts`, last 100) backing `read-chat`. →
   Mirrors our config-gated DEBUG activity log + `say()`; adopt bounded ring
   semantics for agent↔player observation exposure.
5. **stdio hygiene** (`stdio-filter.ts`): only JSON lines reach stdout,
   console.error silenced — protects the stdio JSON-RPC channel from log
   corruption. → Check `sidecar.py`: logs must go to stderr/file only. Fold
   into hot-plug P1 (the tap file then reads a clean wire).
6. **Reconnect lifecycle** (`bot-connection.ts`): kicked/error/end → clean
   teardown → single reconnect timer → new bot instance. → Our `McpSidecar`
   spawn/lifecycle already tracks this; keep parity when adding P2's debug
   transport (debug attach must not double-spawn).

## Landscape (others, per user's "there may be others")

All surveyed open MC-MCP servers are the **same school** — Mineflayer
protocol bots, client-side, no server code:

| Server | Notes |
| --- | --- |
| [yuniko-software/minecraft-mcp-server](https://github.com/yuniko-software/minecraft-mcp-server) | The assigned digest subject; 17 tools; Claude Desktop target |
| [G0Osey99/awesome-mineflayer-mcp](https://glama.ai/mcp/servers/G0Osey99/awesome-mineflayer-mcp) | Scaled-up sibling: 123 tools / 26 groups, first-person vision, event streaming, build/dig macros, waypoints |
| [kaonkrasniqi1/minecraftpoweredbyai](https://github.com/kaonkrasniqi1/minecraftpoweredbyai) | Natural-language structure generation (castle/temple → placed block-by-block) |
| DennisTraub's minecraft-mcp-server, mcpmc | Early prototypes, same Mineflayer approach |
| [InventivetalentDev/minecraft-mcp](https://glama.ai/mcp/servers/InventivetalentDev/minecraft-mcp) | Different purpose: MCP for *modding/coding agents*, not in-world agents |

**Positioning takeaway:** nobody in the surveyed set runs an in-process,
server-side, mod-owned tool registry with a normalization sidecar. Our
architecture (mod owns semantics, sidecar absorbs model quirks, protocol is
the stable interface) is unrepresented — independent validation of the
2026-10-15/16 design decisions. Also: every one of them is stdio-single-client;
**multi-client attach (hot-plug P2) remains unclaimed territory**.

## What this boosts in our development (adoption plan)

Mapped to existing workstreams; no new initiatives needed:

- **Executor robustness** (queued warts, no initiative): per-move timeout +
  no-progress watchdog + alternate-approach retry — from patterns 3 and the
  yuniko pathfinding wrapper. Highest direct value (fixes live-observed
  arrival-circling).
- **McpTools reject taxonomy** (mcp-model-behavior-research): hard-reject vs
  soft-world-no distinction + per-field validation messages — from patterns 1
  and 2. Cheap; improves model self-repair; feeds the corpus Q4 metric
  (reject/recovery rate becomes cleanly measurable).
- **Sidecar stdio hygiene check** (mcp-hotplug-debug-access P1): verify
  `sidecar.py` logs never touch stdout; align the P1 tap format with a clean
  wire — from pattern 5.
- **Observation ring buffer** (agents v1 visibility): bounded recent-activity
  ring for agent say()/debug log — from pattern 4.
- **Not adopted:** flat verb catalogs (we keep pattern trees), bot-as-player
  embodiment (we use mod entities — no protocol/client dependency, works on
  dedicated servers without LAN tricks), no-auth LAN trust model (we stay
  localhost + config-gated).

## Relation to Player-1 (first source)

Player-1 (see [player-1-game-agents](player-1-game-agents.md)) is pixels→RL
control; the yuniko ecosystem is text-protocol→LLM verbs. Both sit on the
*outside* of the game. Our stack owns the *inside*: mod-side registry,
semantic observation, executor leaf effects. The three-layer picture for the
JEVA phase (decision layer, parked): Jeva-style decision-native selection
could sit between our pattern-tree session state and the task board — the
S1-seen "latch onto first-listed unroutable task" failure is a
decision-layer pathology, not a catalog or control one.
