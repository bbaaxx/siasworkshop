---
id: "llm-minecraft-landscape"
title: "Minecraft × LLM Integration Landscape"
category: "reference"
created: "2026-10-13"
updated: "2026-10-13"
related_initiatives: ["llm-integration-research--2026-10-13"]
tags: ["llm","ai","research","ecosystem","capabilities"]
lifecycle: "stable"
knowledge_type: "reference"
confidence: "medium"
source_initiatives: ["llm-integration-research--2026-10-13"]
---

> **Verification caveat (2026-10-13):** this page was compiled from the agent's
> training knowledge — the coordinator environment has no web access, and sub-agents
> have none either. Projects, licenses, and repo names below are reliable as of the
> knowledge cutoff, but versions/URLs/current activity were **not** verified online.
> Spot-check anything before depending on it.

## Landscape at a glance

| Project | What it is | Integration style | License (as recalled) | Relevance to us |
| --- | --- | --- | --- | --- |
| Voyager (NVIDIA/Caltech, 2023) | GPT-4 open-ended agent; auto-curriculum, skill library, self-verification | Out-of-process: drives Mineflayer bot | MIT (code) | Architecture patterns: skill library, curriculum loop |
| MineDojo + MineCLIP (NVIDIA) | Agent framework + video-text contrastive model | Research platform on Malmo-style harness | MIT (framework); model weights separate | Benchmarks; not needed in-mod |
| VPT / STEVE-1 (OpenAI / community) | Video-pretrained policy; instruction-following variant | RL policy, not LLM | MIT | Background context only |
| GITM ("Ghost in the Minecraft") | LLM planner + RL sub-policies; full tech-tree unlocks | LLM decomposes goals, trained controllers act | research code | Shows LLM-as-planner vs LLM-as-controller split |
| MineRL / BASALT | Human-demo datasets + NeurIPS competitions | Offline training data | Dataset terms vary (check!) | Only if we ever train models — unlikely |
| Project Malmo (Microsoft) | Original MC AI experimentation platform | Mod + agent host | MIT | Historical; superseded |
| Mineflayer (PrismarineJS) | Node.js bot framework — the de facto bridge | JS bot client connects to server | MIT | Most LLM demos drive MC through this, not a mod |
| Mindcraft (kolbytn et al., 2024) | LLM agents in-game via Mineflayer; pluggable backends (OpenAI, Anthropic, Gemini, Ollama/local) | Out-of-process bot | MIT (check current) | Reference for backend abstraction + prompting |
| Altera "Project Sid" (2024) | 1000-agent Minecraft civilization sim | Proprietary stack | Proprietary | Design inspiration for villager societies |
| MCP Minecraft servers (several, 2024–25) | Model Context Protocol servers exposing a Mineflayer bot to Claude etc. | LLM host ↔ MCP ↔ Mineflayer | Mostly MIT | Cheapest way to prototype LLM control without mod code |
| LLM chat/NPC mods & Bukkit plugins (various) | In-game chat with LLM-backed NPCs | In-process mod/plugin → HTTP to LLM API | Mixed — check each | Closest precedent to what we'd build; mostly small/young projects |

## The two fundamental integration styles

1. **Out-of-process bot (dominant in research).** A Node.js Mineflayer bot (or
   Python via Malmo-style harness) joins a server as a fake player; the LLM loop
   lives outside Java entirely. Pros: zero mod code, language-agnostic, easy
   iteration. Cons: needs a bot slot + server access, no access to modded registries,
   no first-class server-side authority.
2. **In-process mod (what we'd build).** The NeoForge mod itself calls an LLM API
   over HTTP from the server (or integrated client). Pros: full game state,
   villager/worldgen integration, works with our existing blocks/BEs/GUIs. Cons:
   we own async/threading, key management, cost control, and update compat.

A third, emerging style: **MCP server bridging** — expose game state/actions as MCP
tools so any MCP-capable LLM client drives the game. Good for prototyping;
not a shippable player feature.

## Technical patterns that matter for an in-process NeoForge mod

- **Never block the server thread.** LLM calls are 0.5–30 s. Dispatch on a
  dedicated executor (`CompletableFuture` + a small `ExecutorService`), hop back
  with `server.execute(...)` before touching world state.
- **Serialize world context compactly.** LLMs can't see; build a text/JSON summary:
  nearby blocks (limited radius), inventory, entity list, time/weather, our village
  metadata. Token budget discipline is the whole game.
- **Tool/function calling > free text.** Give the model structured actions
  (`place_block`, `give_item`, `open_menu`, `say`) validated against the real
  registry before execution — never let raw model output touch game state.
- **Grounding / RAG.** Recipe and mechanic questions hallucinate badly; inject
  datapack JSON (recipes, tags) or a trimmed wiki snapshot into the prompt.
- **Config and consent.** API key via `config/Config.java` (never shipped, never in
  saves), provider endpoint configurable, per-feature toggles, graceful offline
  fallback when no key is set. Our Config + `ModConfigSpec` pattern already fits.
- **Cost/rate control.** Debounce triggers, cache identical prompts, cap calls per
  player per minute, log usage.
- **Dist split.** Single-player: call from integrated server. Dedicated servers:
  server-side only, key held by the server admin — never send keys to clients.
- **Dependencies.** Plain Java HTTP (JDK `java.net.http`) or a tiny lib; avoid
  shading a heavy OpenAI SDK into an ARR mod without checking its license chain.

## Licensing notes (ARR mod — see ecosystem-map)

- Mineflayer, Voyager code, Malmo, Mindcraft are recalled as **MIT** — safe to
  study and to depend on (JS side) but re-verify before copying any code.
- Research **datasets** (MineRL, MineDojo knowledge base, YouTube corpora) have
  separate terms — treat as inspiration only.
- LLM **API terms** (OpenAI/Anthropic/Google) govern server-side usage; a mod that
  lets users supply their own key (BYOK) is the standard, clean arrangement.
- Never lift GPL mod code (e.g., MCA-style dialogue systems) even as a template.

## Candidate capabilities for Sia's Workshop

Ranked by fit with the village theme and implementation cost:

1. **Villager dialogue / naming (server-side, opt-in).** LLM-generated villager
   names, gossip, and "chronicle" entries stored on our village foundation block.
   Small prompts, cacheable, offline fallback to name lists. High thematic fit.
2. **Village chronicle / quest generation.** Periodic LLM-written summary of what
   happened in the village (deaths, builds, trades) into a writable book —
   grounded in real game events we feed it.
3. **Guide/build advisor item.** Right-click → answers "what can I build with
   these materials" using actual datapack recipes injected as context (RAG-lite).
4. **Structure blueprint generation (stretch).** Function-calling to place blocks
   / emit a template — high cost, high moderation risk; prototype via MCP first.
5. **Autonomous villager agents (research-grade).** Altera-style; out of scope for
   a shipping mod, but worth a dev-only experiment.

## Suggested next step

Prototype #1 or #2 as a config-gated feature (`content/llm/` package, registration
holder in `init/`, BYOK via Config). Optionally spike the experience first with a
Mindcraft/MCP bot on a dev server — no mod code required.

## Referenced By

*Auto-generated by mdocs*

- llm-integration-research--2026-10-13
