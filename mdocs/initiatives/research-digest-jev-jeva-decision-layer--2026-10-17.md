---
id: "research-digest-jev-jeva-decision-layer--2026-10-17"
title: "Research digest: JEV + JEVA decision layer + local (non-cloud, non-usage-priced) alternative"
status: "complete"
created: "2026-10-17"
updated: "2026-10-17"
owner: "coordinator"
tags: ["research","llm","mcp","agents","paper-digest","decision-layer","local-llm"]
related_wiki: ["reference/player-1-game-agents", "reference/minecraft-mcp-servers", "reference/local-llm-feasibility", "reference/mcp-pattern-tree-design", "setup/session-handoff-2026-10-17", "reference/decision-layer-jev-jeva"]
priority: "medium"
graduated: "2026-10-17"
---

## Objective

Third source of the 2026-10-17 research pause, user-directed after the
Player-1 + Minecraft-MCP digests completed: study the **decision layer** —
both JEV and JEVA — and, critically, find an alternative that is **not
cloud/usage-based** (no per-token vendor APIs): a locally runnable,
self-hostable path to decision-native selection for our task board.

Sources:
1. **JEVA** — [arXiv 2610.18773](https://arxiv.org/abs/2610.18773) (v1
   2026-10-13, Qi Li): decision-native architecture for system-one models —
   null-aware / permutation-invariant / option-scalable / cacheable decisions
   over candidate sets; unifies Choice, Noul, Score outputs.
2. **JEV** — TypeSafe's system-one model announcement ("Introducing System
   One Models & Jev", typesafe.ai blog, Sep 2026) — the production framing of
   the same decision-layer idea; already cited in Player-1's related work as
   the structured-interface low-latency sibling.

## Why the decision layer, for us

Our task-board decision pathology is decision-layer shaped: the S1 live test
(SEQ-024/026) saw the model latch onto the first-listed unroutable task —
candidate-order sensitivity, no explicit "no valid option" outcome, candidate
set changes requiring prompt changes. That is exactly what JEVA's four
structural requirements address (permutation-invariance, null-aware absence
vs uncertainty, option-scalability, cacheability).

## Hard requirement (user, 2026-10-17)

The adopted path must run **locally / self-hosted** — no cloud endpoint, no
usage-based pricing. Evaluate against our existing local stack (LM Studio,
`prism-ml/bonsai-27b` on M3 Max; llama.cpp-class constrained decoding; our
Python-stdlib sidecar). See `reference/local-llm-feasibility` for baseline
feasibility facts.

## Plan

- [x] Fetch and fully read the JEVA paper (HTML full text)
- [x] Fetch and digest the TypeSafe JEV / system-one announcement
- [x] Write digest wiki page `reference/decision-layer-jev-jeva` — the four
      structural requirements, architecture (shared encoder + decision stack
      + symmetric aggregation + coverage estimate), evaluation claims, and
      the JEV vs JEVA relationship
- [x] Survey the local / non-usage-based alternative space: open weights or
      open implementations of decision-native selection; llama.cpp-style
      grammar/constrained decoding for Choice/Noul/Score; whether a
      Jeva-style head can run on our local models; self-host options for
      TypeSafe's stack if any
      → DONE (2026-10-17): JevK5-4B GGUF (Apache-2.0, llama.cpp, ~0.6s short
      decisions on Apple Silicon; weak null handling; 26% flip rate) and
      Kev-4B (Apache-2.0, MLX on 32GB Macs, 1-255 options, state caching;
      option-order caveat) are the viable self-hosted endpoints; JevK5-9B
      regressed vs 4B; JEVA-4B itself unreleased (recipe only)
- [x] Map onto our decision loop: task-board choice, null = no routable task,
      permutation-invariance vs listing order, cacheability of task/option
      embeddings across decide calls; concrete integration shape with the
      sidecar (decision pre-filter before the LLM, or replacing part of it)
- [x] Record recommendation with the local-path constraint front and center;
      cross-link mcp-model-behavior-research + hot-plug initiatives; validate

## Progress Log

- [2026-10-17] Initiative opened per user direction ("not just JEVA also JEV
      and we want an alternative that is not could[cloud]/usage-based").
      Both source links already in hand (JEVA arXiv 2610.18773 from the
      earlier mispaste; JEV blog URL from Player-1's references).
- [2026-10-17] Digest DONE. JEV = TypeSafe's hosted system-one product
      (typed noul/choice/score decisions, 70-500ms, calibrated, but
      cloud/usage-priced — fails the hard constraint, kept as reference).
      JEVA = open architecture paper: four structural requirements
      (null-aware, permutation-invariant, option-scalable, cacheable);
      zero flips in 600-order tests vs Jev 17%/InternDecision 22.7%/JevK5
      26.2%; 98.66% null AUROC; K to 2048; 27.98x cache speedup with
      unchanged decisions; no weights released. Key insights recorded: null
      must be a SEPARATE coverage question, not a list option (flat-softmax
      "None" suffers compulsory -log K drift, Prop 2); position identifiers
      must not be encoded. Local alternatives surveyed (all Apache-2.0):
      JevK5-4B GGUF via llama.cpp (~0.6s/short decision on Apple Silicon,
      TypeSafe-shaped endpoint; weak null/out-of-scope recall 0.27-0.46);
      Kev-4B via MLX (validated on 32GB Macs, 1-255 options native, state
      caching across questions, excellent OOD calibration ECE 0.017-0.029;
      option order can change answers); JevK5-9B regressed vs 4B on hard
      tier + calibration — do not prefer. Recommendation (tiered, local):
      T0 now = shuffle + separate coverage noul + adopt JEVA metrics (flip
      rate, all-order accuracy, null AUROC@5%FPR) into corpus Q4 on the
      existing bonsai sidecar path; T1 = self-host JevK5-4B-GGUF or Kev-4B
      as localhost typed-decision endpoint for task-claim choice + D5-style
      gate noul + routability coverage, measured on our corpus; T2 = Jeva-
      style head only if catalog grows past ~50-100 tools. Wiki:
      reference/decision-layer-jev-jeva.
