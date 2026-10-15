---
id: "local-llm-feasibility"
title: "Local Small LLM Feasibility (Qwen3-class, 4-bit, Apple M3 Pro)"
category: "reference"
created: "2026-10-13"
updated: "2026-10-13"
related_initiatives: ["local-llm-feasibility--2026-10-13"]
tags: ["llm","local-llm","qwen","apple-silicon","capabilities"]
lifecycle: "draft"
knowledge_type: "reference"
confidence: "medium"
source_initiatives: ["local-llm-feasibility--2026-10-13"]
---

> **Verification caveat (2026-10-13):** compiled from training knowledge — no web
> access in the agent environment. Model sizes, quant sizes, and tok/s figures are
> reasonable estimates, not benchmarks. Verify before committing to numbers.

Companion page to `reference/llm-minecraft-landscape` (integration patterns still
apply). This page answers: *what can a locally-run small LLM realistically power in
siasworkshop on a dev machine (Apple M3 Pro)?*

## The ask, interpreted

- "Qwen 3 small / 4-bit quant" → the **Qwen3 dense family** (0.6B, 1.7B, 4B, 8B
  params) at 4-bit quantization (GGUF `Q4_K_M` via llama.cpp/Ollama, or MLX 4-bit),
  plus the **Qwen3-30B-A3B MoE** (only ~3B active params → small-model speed,
  bigger-model quality) as the upper bound.
- Target hardware: **Apple M3 Pro** (18 GB or 36 GB unified memory, ~150 GB/s
  memory bandwidth).

## Hardware budget on M3 Pro (estimates)

| Model (4-bit) | Weights in RAM | Est. gen speed (Metal) | Fit alongside Minecraft (JVM ~4–6 GB) |
| --- | --- | --- | --- |
| Qwen3-0.6B Q4 | ~0.5 GB | 100+ tok/s | trivial, even on 18 GB |
| Qwen3-1.7B Q4 | ~1.2 GB | 60–90 tok/s | trivial |
| Qwen3-4B Q4_K_M | ~2.5–3 GB | 30–45 tok/s | comfortable on 18 GB |
| Qwen3-8B Q4_K_M | ~5–6 GB | 15–25 tok/s | OK on 18 GB, easy on 36 GB |
| Qwen3-30B-A3B Q4 (MoE) | ~18–19 GB | 20–35 tok/s (3B active) | 36 GB machine only |
| Qwen3-14B Q4 | ~9 GB | 10–15 tok/s | tight on 18 GB, fine on 36 GB |

Rule of thumb on Apple Silicon: inference is memory-bandwidth-bound, so tok/s
scales inversely with *active* params. Prompt ingestion (prefill) on M3 Pro is
fast enough that prompts of a few hundred tokens are a non-issue.

## Runtime options — all expose the same OpenAI-compatible HTTP API

| Runtime | Endpoint | Notes |
| --- | --- | --- |
| Ollama | `http://localhost:11434/v1` | easiest install; `ollama pull qwen3:4b`; supports structured outputs (JSON schema) |
| LM Studio | `http://localhost:1234/v1` | nice GUI for trying quants; MLX + GGUF |
| llama.cpp `llama-server` | `http://localhost:8080/v1` | minimal; GBNF grammar constraints |
| MLX-LM server | configurable | Apple-native MLX quants, often fastest on M-series |

**Design consequence:** the mod only needs a configurable base URL + model name
speaking the OpenAI chat-completions subset. One client, any runtime — same
codepath also works with hosted APIs later.

## Capability tiers — what each size can actually do

- **0.6B** — template rephrasing with persona flavor, villager names, short
  classification (pick 1 of N tags/moods), one-line gossip. Freeform prose is
  unreliable; keep outputs ≤1 sentence and heavily templated.
- **1.7B–4B** — the sweet spot for us: short *grounded* dialogue (1–3 sentences
  from facts we inject), village chronicle entries from structured event lists,
  mood/event tagging, and **JSON output when grammar/schema-constrained**
  (Ollama `format` / llama.cpp GBNF). Constrained "choose from these options"
  works; open-ended function calling does not.
- **8B / 30B-A3B** — noticeably better chronicle prose, light Q&A over injected
  recipe/wiki snippets (RAG-lite), multi-choice villager "decisions". Still not
  trustworthy for autonomous multi-step tool use.

## Design principles for small local models

1. **LLM as flavor layer, never game logic.** All state changes stay in
   deterministic Java; the model only writes text (names, gossip, chronicles) or
   picks from enumerated options we validate.
2. **Constrain outputs.** JSON schema / GBNF grammars wherever structure matters;
   parse defensively; on parse failure → template fallback.
3. **Ground everything.** Feed real event facts ("librarian born, 2 trades, raid
   repelled") rather than asking the model to know the world — small models
   hallucinate aggressively when under-informed.
4. **Short prompts, short outputs.** Latency = prefill + generation; keep both
   small. Batch similar requests (e.g., name 5 villagers in one call).
5. **Offline-first.** Endpoint absent/unreachable → built-in template tables, no
   errors shown to players. Config-gated feature, off by default.
6. **Cache aggressively.** Names/gossip/chronicle lines are cacheable by prompt
   hash; local inference still costs battery/heat on a laptop.

## Config sketch (maps to `config/Config.java` / ModConfigSpec)

```
llm.enabled            = false          # master toggle
llm.endpoint           = "http://localhost:11434/v1"
llm.model              = "qwen3:4b"
llm.timeoutSeconds     = 20
llm.maxTokens          = 128
llm.temperature        = 0.8
llm.cacheResponses     = true
```

Threading per the landscape page: dedicated executor, `server.execute(...)` on
completion, never block the server thread. Server-side only on dedicated servers.

## Licensing

- **Qwen3 dense weights (0.6B–32B): Apache-2.0** — permissive. The **MoE variants
  (30B-A3B, 235B-A22B) are recalled as being under the more restrictive Qwen
  Research license, not Apache-2.0** — verify current terms before recommending
  30B-A3B for Phase 2. Regardless, the mod never bundles weights (user installs
  Ollama + pulls the model themselves).
- Ollama/LM Studio/llama.cpp are external user-installed tools — no linking
  concern. Mod talks plain HTTP.
- Llama 3.2 (1B/3B; Llama Community License — mostly permissive but has
  naming/acceptable-use terms, verify) and Phi-4-mini (MIT) are viable alternates if Qwen proves
  weak at English flavor text; Gemma 3 has its own license — check before
  recommending it.
- ARR mod unaffected: no model code or weights copied into the repo.

## Recommended scope for siasworkshop

**Phase 1 — "Village flavor pack" (targets Qwen3-4B Q4, degrades to 0.6B):**
- Villager name generation (batched) on village foundation.
- Gossip/ambient lines from villager profession + recent village events.
- Chronicle entries: structured event list → 1–3 sentence prose, stored on the
  foundation block, written into books.
- All three degrade to bundled template tables when the endpoint is down.

**Phase 2 (needs 8B / 30B-A3B):** guide/build advisor with recipe-JSON grounding;
optional local embeddings (e.g., nomic-embed-text via the same Ollama instance).

**Explicitly out of scope at this model size:** autonomous villager agents,
open-ended function calling, unmoderated player-facing free chat.

## Suggested next step

Spike Phase 1 outside the mod first: a tiny script hitting Ollama with our event
JSON → chronicle prose, to calibrate which model size gives acceptable quality on
the M3 Pro. Then implement as `content/llm/` + `init/` holder, config-gated.

## Wire compatibility notes (verified against LM Studio, 2026-10-15)

Findings from the live smoke test of `content/llm/LlmClient` against LM Studio
(local llama.cpp backend, prism-ml/bonsai-27b on M3 Pro):

- **`response_format` is not portable.** LM Studio's llama.cpp server rejects
  `{"type":"json_object"}` with HTTP 400 ("must be 'json_schema' or 'text'") and
  closes the connection abruptly enough that java.net.http surfaces it as
  `ClosedChannelException`, hiding the 400. Constrain JSON via the system prompt
  and parse defensively instead (what the client does now); send
  `response_format` only when targeting OpenAI proper.
- **Reasoning models spend most of max_tokens on hidden thinking.** bonsai-27b
  used 440 of 461 completion tokens on `reasoning_content`, leaving `content`
  empty at max_tokens=64 (finish_reason=length). Default 512, not 64.
- **Models wrap JSON in markdown code fences despite "JSON only" instructions.**
  The parser must extract the first `{...}` span, not require the body to start
  with `{`.
- **LM Studio binds 127.0.0.1 only (IPv4)**; `localhost` may resolve to ::1
  first. The endpoint default uses localhost and worked on this machine, but if
  a setup ever fails to connect at all, `127.0.0.1` is the first thing to try.
- **Failures must be visible in latest.log at INFO/WARN, not DEBUG.** Silent
  fallback made every issue above look like "the LLM was never called". The
  decision loop now logs LLM decisions and task claims at INFO, fallback
  reasons at WARN, and `/siasworkshop board` dumps the task board in-game.

## Referenced By

*Auto-generated by mdocs*

- local-llm-feasibility--2026-10-13
