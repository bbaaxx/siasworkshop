---
id: "mcp-model-behavior-research--2026-10-15"
title: "Research: dedicated MCP layer for model-stable agent decisions"
status: "active"
created: "2026-10-15"
updated: "2026-10-16"
owner: "coordinator"
tags: ["research","llm","mcp","agents","architecture"]
related_wiki: ["reference/local-llm-feasibility", "setup/agent-shared-ledger", "setup/agent-ux-feedback-2026-10-16", "setup/session-handoff-2026-10-16", "setup/session-handoff-2026-10-17", "reference/mcp-pattern-tree-design", "reference/minecraft-mcp-servers", "reference/decision-layer-jev-jeva"]
priority: "medium"
---

## Objective

Build a dedicated **MCP (Model Context Protocol) sidecar** between the mod and
LLM providers so that swapping models (local sizes/families, cloud) changes
latency/cost/quality but never the interface between model and mod — a stable,
growable protocol for agent behavior.

## Direction (user decisions, 2026-10-15 + 2026-10-16)

- **Normalization lives in the MCP sidecar**, not in an OpenAI-shim/proxy. The mod
  keeps a single dumb MCP client; per-model quirks (prompt templates, token budgets,
  response-format negotiation, tool-call format conversion, reasoning-model
  detection) are absorbed by the sidecar.
- **Per-model optimization profiles are allowed for the most-used models** on top of
  the generic path — the sidecar hosts profiles (prompt variants, budgets,
  capability flags) keyed by model id/family.
- **The mod exposes its capability catalog to the sidecar as MCP tools.** The mod owns
  the registry; the tool catalog **is** the stable interface: models can be switched
  and optimized, but the protocol by which they interact with the mod never moves.
  New mod capabilities ship by registering a tool, without touching model
  integration; new models onboard by adding a sidecar profile, without touching mod.
- **Tools are composable pattern trees, not mapped verbs** (user feedback
  2026-10-16, `setup/agent-ux-feedback-2026-10-16`): the catalog is leveled and
  tree-structured — category `building` holds patterns like "think through a build
  request" → "analyze terrain / find where" → "design a build" → "select materials"
  → "create blueprint" → "apply blueprint". The world is a **state machine**:
  observe state via MCP, request state changes via patterns, observe convergence.
  LLM intelligence plans at a high level; the mod executes the leaf effects (place,
  build, farm, mine). MCP sessions are **multi-step workflows**, not one-shot
  decision calls.
- **North star (user, 2026-10-16): progressive transfer of decisions to the LLM and
  actions to the mod.** Each iteration moves some decisions up (higher abstraction,
  bigger plans) and some execution down (into mod code). Concrete mechanism en route:
  the LLM issues well-defined plans (sequential JSON); the mod queues and executes
  them over long horizons (multi-agent, report-on-completion). Furthest horizon — the
  model progressively redefines how actions are implemented from usage-derived
  learning — is explicitly long-term research, not committed scope.
- **MCP path is feature-flagged, default OFF** (user, 2026-10-16): CurseForge installs
  must not require Python; the sidecar client activates only via config opt-in. Dev
  builds run the full toolset.

## Motivation (pain observed in the live test, SEQ-011)

Every model behaves differently in the same pipeline, and v1 absorbed the variance
ad hoc inside `LlmClient`:

- Reasoning models burn 500–900 hidden tokens before answering (empty `content` at
  512 `max_tokens`); non-reasoning models answer in ~20 tokens. Budget is per-model
  tuning, not contract.
- Output hygiene varies: markdown code fences despite "JSON only", prose wrappers,
  occasionally unparseable bodies (`bad_json`).
- Wire compat varies: llama.cpp rejects `response_format: json_object`; OpenAI
  accepts it; some servers support `json_schema` constrained decoding, some don't.
- Prompt compliance varies by family (Qwen vs Llama vs GPT): currently one fixed
  `LlmPrompts` system line, one-size-fits-all.

## Research questions (given the direction above)

1. Which local sizes (4B/14B/27B on M3 Pro) and cloud models support tool/function
   calling reliably enough for a growing tool catalog? Failure modes when
   unsupported — and does prompt-constrained JSON remain the fallback inside the
   sidecar for those?
2. Does tool-calling measurably reduce malformed/unusable decisions vs the current
   prompt-constrained JSON path? (Metric target: `bad_json`/fallback rate.)
3. **Pattern-tree design**: descriptor format for categories → patterns →
   sub-tools/effects; how sub-tool discovery works across turns; how prompts stay
   small when the catalog grows (retrieve-and-inject per situation, like build
   cards today); how the multi-step session state is held (in the sidecar? in the
   mod against the task board?).
4. What does "stable behavior" mean measurably — a scenario corpus (recorded
   observations + expected action classes) replayable offline against any endpoint;
   can the sidecar host this model-behavior CI (A/B profiles, regression runs)?
5. Ops: sidecar lifecycle (dev loop: process manager? Gradle task?), extra-hop
   latency budget, offline-first preserved (template fallback when sidecar/endpoint
   down — the current in-mod template path stays as the last resort).

## Considered and set aside (rationale recorded)

- **OpenAI-shim/proxy normalization (LiteLLM-style)**: rejected — user wants
  normalization in the MCP sidecar; a shim would still leave tool semantics
  model-specific.
- **Per-model profiles inside the mod**: demoted — profiles move into the sidecar;
  the mod stays model-agnostic.
- **Low-level mapped tools** ("open chest", "place block T at X,Y"): rejected by
  user (2026-10-16) — the catalog exposes composable patterns; leaf effects are
  executed by the mod, not driven step-by-step by the model.
- **Status quo (in-mod normalization)**: the v1 fallback path stays for offline, but
  is not the integration strategy.

## Plan

- [x] Finish the cloud-model test round (SEQ-014: trailing-slash 404, seconds-fast
      decisions, shelter ping-pong) — requirements input collected
- [x] Collect + prioritize the user's playtest feedback batch (SEQ-015)
- [x] Prerequisite feature (separate initiative): agent state visibility — nameplate
      states + working say() + config-gated DEBUG activity log (DONE, f1e1c71)
- [x] Design the pattern-tree descriptor format (category → patterns → effects),
      building-workflow example as the reference walk
      → `reference/mcp-pattern-tree-design` (2026-10-16)
- [x] Spike: minimal sidecar exposing the 3 decision tools to one local model and
      one cloud model; measure decision latency + malformed-decision rate vs v1
      → S0 done under `sidecar/` (2026-10-16, results SEQ-020): replay 20/20,
      cloud 9/9 ok, local v1 failed while both MCP modes passed
- [x] Feature-flag wiring: MCP client path default-OFF, opt-in via config (no Python
      dependency for CurseForge installs) — S1, with in-mod gametest
      → DONE (2026-10-16, results SEQ-022): Config agents.mcp.* block (default OFF),
      McpSidecar transport + McpTools catalog/dispatch in content/mcp/, decision loop
      routing with template fallback, real sidecar.py bridge, 4 gametests (34/34 green)
- [ ] S2: first real pattern tree against the building walk (three patterns against
      real mod machinery, same scenario as a v1 build task)
      → DONE in the sidecar harness (2026-10-16, results SEQ-021): six-step building
      walk end-to-end, D5 card gating + convergence polling, cloud 2/2 and local
      1/1 converged
- [ ] Scenario corpus + assertions for question 4
- [ ] Recommendation → `reference/` wiki; if adopted, plan the LlmClient→MCP-client
      migration (config: sidecar address instead of endpoint/model/apiKey)

## Progress Log

- [2026-10-15] Initiative created from user note after LLM live-test session
      (commits f974a47, c9ebf8d, 212f147, 2daa823; ledger SEQ-011).
- [2026-10-15] User direction recorded: MCP-sidecar normalization (not shim), the
      mod exposes its action/pattern catalog as MCP tools — the tool catalog is the
      stable model↔mod interface, growing with mod capabilities; per-model
      optimization profiles allowed for hot models. Priority low→medium.
- [2026-10-16] Cloud test round done (SEQ-014) — requirements input collected.
- [2026-10-16] User feedback batch (wiki: setup/agent-ux-feedback-2026-10-16):
      pattern-tree tool catalog (world = observable state machine, LLM plans, mod
      executes leaf effects); in-game agent state visibility is the agreed
      pre-sidecar feature. Research questions refocused; low-level mapped tools
      recorded as rejected.
- [2026-10-16] Resumed per handoff session-handoff-2026-10-16. Pattern-tree
      descriptor design written: wiki reference/mcp-pattern-tree-design (roles+stdio
      transport, 4 tool kinds, descriptor format, pattern-card expansion,
      session-state split, safety, flag, prompt economy, building-walk reference
      trace, S0-S2 spike plan, open questions).
- [2026-10-16] S1 done (first Java slice): Config `agents.mcp.*` block (enabled default
      OFF, command, decision timeout); `content/mcp/McpSidecar` (spawn/lifecycle,
      symmetric newline JSON-RPC over child stdio, both peers client+server, failure
      collapse into CallResult); `content/mcp/McpTools` (the mod's S0 catalog +
      server-thread dispatch + structured rejects); decision loop routes through the
      sidecar ahead of the direct llm.* path with template fallback on any failure;
      entrypoint starts/stops the sidecar with the server. Python: real `sidecar.py`
      bridge (client role, serves `decide(agent)`, pulls observation/board via mod
      tools, prompt/native model surface, --replay offline mode) + `test_sidecar_bridge.py`
      (sidecar.py vs mockmod.py over crossed pipes = production topology, PASS).
      Gametests AgentsMcpGameTest (4): handshake + catalog, full decide cycle claiming
      on the real board, malformed-call rejects, dead-sidecar fallback — 34/34 green.
      Gametest war stories: production tick loop resolves intentionally-malformed test
      tasks mid-assert (assert board RECORD + claimant, not live claim state); the
      shared batch world + board ordering means never claim "whatever is listed first".
- [2026-10-16] S2 done in the sidecar harness: the six-step building walk runs
      end-to-end (think → analyze_terrain → design → select_materials →
      create_blueprint → apply_blueprint → observe_task convergence), D5 situational
      card gating enforced session-side, prompt + native modes, mock simulating the
      mod-side machinery. Walk replay 3/3 (incl. a gate-reject recovery case), S0
      regression 20/20. Cloud deepseek native 2/2 converged on the reference chain
      (21-26s, 0.8-1.1k completion tokens; one run self-recovered from 3 exec
      rejects); local bonsai prompt-mode 1/1 converged (127s, 1.5k tokens, 2 rejects
      recovered). Details: ledger SEQ-021; step-level reject forensics added to
      WalkResult for the corpus phase.
- [2026-10-16] S0 spike done under `sidecar/` (stdlib-only Python: JSON-RPC/MCP
      client, mock mod in the server role, 3-mode A/B runner, asserted replay
      self-test 20/20). Cloud (deepseek-pro, n=3/mode): 9/9 ok, 0 malformed —
      v1 1.1s / mcp-prompt 1.2s / mcp-native 1.4s, 21/48/68 completion tokens.
      Local (bonsai-27b, n=1/mode): v1 FAILED (120s timeout; previous run
      empty_content:length at 1024 — 2/2 budget burnouts on the v1 prompt) while
      BOTH MCP modes succeeded (~39s, ~350-390 tokens). LM Studio accepted native
      tool-calling for bonsai. Details + findings: ledger SEQ-020; sidecar/README.md;
      metrics in sidecar/metrics-*.jsonl.
