---
id: "mcp-model-behavior-research--2026-10-15"
title: "Research: dedicated MCP layer for model-stable agent decisions"
status: "proposed"
created: "2026-10-15"
updated: "2026-10-15"
owner: ""
tags: ["research","llm","mcp","agents","architecture"]
related_wiki: ["reference/local-llm-feasibility", "setup/agent-shared-ledger"]
priority: "medium"
---

## Objective

Build a dedicated **MCP (Model Context Protocol) sidecar** between the mod and LLM
providers so that swapping models (local sizes/families, cloud) changes
latency/cost/quality but never the interface between model and mod — a stable,
growable protocol for agent decisions.

## Direction (user decision, 2026-10-15)

- **Normalization lives in the MCP sidecar**, not in an OpenAI-shim/proxy. The mod
  keeps a single dumb MCP client; per-model quirks (prompt templates, token budgets,
  response-format negotiation, tool-call format conversion, reasoning-model
  detection) are absorbed by the sidecar.
- **Per-model optimization profiles are allowed for the most-used models** on top of
  the generic path — the sidecar hosts profiles (prompt variants, budgets,
  capability flags) keyed by model id/family.
- **The mod exposes its action catalog to the sidecar as MCP tools.** The mod owns a
  registry of programmed actions and patterns (starting with the decision trio
  `claim(task_key)` / `rest()` / `say(player,msg)`, growing as the mod's configured
  actions and components grow — e.g. build patterns, deposit/withdraw, chronicle
  writes). Each capability added to the mod becomes a tool in the MCP layer. The
  tool catalog **is** the stable interface: models can be switched and optimized,
  but the protocol by which they interact with the mod never moves.
- **Decoupling is a feature, not just hygiene**: new mod capabilities ship by
  registering a tool, without touching model integration code; new models onboard by
  adding a sidecar profile, without touching the mod.

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
3. **Tool-catalog evolution**: how do we version/schema the tool registry as the
   mod grows — mod ships tool descriptors, sidecar reflects them, models see them;
   how to keep prompts small when the catalog is large (retrieve-and-inject per
   situation, like build cards today)?
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
- **Status quo (in-mod normalization)**: the v1 fallback path stays for offline, but
  is not the integration strategy.

## Plan (not started — parked)

- [x] Finish the cloud-model test round (current track; its failure modes become
      requirements input here) — DONE 2026-10-16 (SEQ-014: trailing-slash 404,
      seconds-fast decisions, shelter ping-pong)
- [ ] Spike: minimal sidecar exposing the 3 decision tools to one local model and
      one cloud model; measure decision latency + malformed-decision rate vs v1
- [ ] Design the mod-side tool registry (descriptor format, registration, growth
      path) — starts as a reflection of `LlmActions`, decouples from it
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
- [2026-10-16] Cloud test round done (SEQ-014) — requirements input collected:
      per-model reasoning budgets, wire quirks (double-slash 404), task-claim
      semantics. Awaiting user's gameplay feedback batch before prioritizing.
