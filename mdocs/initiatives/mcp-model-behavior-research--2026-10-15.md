---
id: "mcp-model-behavior-research--2026-10-15"
title: "Research: dedicated MCP layer for model-stable agent decisions"
status: "proposed"
created: "2026-10-15"
updated: "2026-10-15"
owner: ""
tags: ["research","llm","agents","mcp","architecture"]
related_wiki: ["reference/local-llm-feasibility", "setup/agent-shared-ledger"]
priority: "low"
---

## Objective

Investigate whether a dedicated MCP (Model Context Protocol) layer between the mod and
LLM providers would give **stable decision behavior across models** — so swapping
bonsai-27b ↔ a 14b local ↔ a cloud model changes latency/cost but not correctness or
output shape. Parked idea from the user (2026-10-15), to explore after the cloud-model
test round.

## Motivation (pain observed in the live test, SEQ-011)

Every model behaves differently in the same pipeline, and the mod absorbs the variance
ad hoc:

- Reasoning models burn 500–900 hidden tokens before answering (empty `content` at
  512 `max_tokens`); non-reasoning models answer in ~20 tokens. Budget is per-model
  tuning, not contract.
- Output hygiene varies: markdown code fences despite "JSON only", prose wrappers,
  occasionally unparseable bodies (`bad_json`).
- Wire compat varies: llama.cpp rejects `response_format: json_object`; OpenAI
  accepts it; some servers support `json_schema` constrained decoding, some don't.
- Prompt compliance varies by family (Qwen vs Llama vs GPT): the same system prompt
  nudges different styles — currently fixed in `LlmPrompts`, one-size-fits-all.

The v1 answer was: normalize in the client (defensive parsing, prompt-constrained
JSON, generous token budget). That works but pushes per-model complexity into the mod.

## Core hypothesis

If the **action contract is exposed as MCP tools** (e.g. `claim(task_key: string)`,
`rest()`, `say(player, msg)`) with input schemas, then models with tool/function
calling emit structured calls instead of prose-JSON — removing the entire parse
failure class. Model differences get absorbed by the MCP layer: per-model adapters
(prompt templates, token budgets, response-format negotiation, tool-call format
conversion), so the mod speaks one dialect and the layer translates.

## Research questions

1. Which local sizes (4B/14B/27B on M3 Pro) and cloud models support tool/function
   calling reliably enough for a 3-tool contract? Failure modes when unsupported?
2. Does tool-calling measurably reduce malformed/unusable decisions vs
   prompt-constrained JSON (current `LlmPrompts` + `LlmActions` path)?
3. Where should normalization live: in-mod (current `LlmClient`), an MCP server the
   mod talks to, or an OpenAI-compatible proxy/shim (e.g. LiteLLM-style) that
   normalizes wire + tools while keeping the mod's HTTP client dumb?
4. What does "stable behavior" mean measurably — a scenario corpus (recorded
   observations + expected action classes) with assertions, replayable offline
   against any endpoint; can we build a model-behavior CI around it?
5. Ops: extra hop latency, offline-first fallback preserved (template path when the
   layer/endpoint is down), and what runs the layer (sidecar process? embedded?).

## Candidate approaches to compare

- **A. Status quo**: in-mod normalization (current), per-model config knobs.
- **B. MCP server sidecar**: mod connects as MCP client; tools = decision contract;
  per-model adapters inside the server; mod's HTTP surface shrinks to one client.
- **C. OpenAI-compatible proxy/shim**: mod keeps current client; proxy adds
  response_format/tool translation, per-model routing, caching, observability.
- **D. Per-model profiles in config**: no new process; prompt templates + budgets +
  capability flags per model family in the mod. Cheapest, least isolation.

## Plan (not started — parked)

- [ ] Finish cloud-model test round (user's current track) and capture its failure
      modes as requirements input
- [ ] Spike B vs C with one local model + one cloud model on the 3-tool contract
- [ ] Define the scenario corpus + assertions for question 4
- [ ] Write recommendation to `reference/` wiki; decide adopt/reject per approach

## Progress Log

- [2026-10-15] Initiative created from user note after LLM live-test session
      (commits f974a47, c9ebf8d, 212f147, 2daa823; ledger SEQ-011).
