---
id: "local-llm-feasibility--2026-10-13"
title: "Local small LLM feasibility (Qwen3-class, 4-bit, M3 Pro)"
status: "done"
created: "2026-10-13"
updated: "2026-10-13"
owner: ""
tags: ["research","llm","local-llm","qwen","apple-silicon","capabilities"]
related_wiki: ["reference/local-llm-feasibility"]
priority: "medium"
---

## Objective
Assess which siasworkshop capabilities are feasible with a locally-run small LLM (Qwen3 0.6B-8B class, 4-bit quant) on an Apple M3 Pro, covering model sizing, runtime options (Ollama/LM Studio/MLX/llama.cpp), capability tiers, integration design, and licensing. Output: wiki reference page + recommended feature scope.

## Plan
- [ ] Compile sizing/performance/capability assessment from coordinator knowledge (no web access)
- [ ] Write mdocs/wiki/reference/local-llm-feasibility.md
- [ ] Validate, link, recommend scope

## Progress Log
- [2026-10-13T23:54:27.184Z] Created initiative via mdocs command
- Feasibility brief written to mdocs/wiki/reference/local-llm-feasibility.md (from coordinator knowledge; no web access, caveat on page). Covers M3 Pro sizing table (0.6B-30B-A3B at 4-bit), runtime options (Ollama/LM Studio/llama.cpp/MLX all OpenAI-compatible), capability tiers per model size, design principles (flavor-layer only, constrained outputs, template fallback), config sketch, licensing (Qwen3 Apache-2.0, no bundled weights), and recommended Phase 1 scope: village flavor pack on Qwen3-4B Q4.
- [2026-10-13T23:56:40.724Z] Marked done via mdocs command

## Artifacts
