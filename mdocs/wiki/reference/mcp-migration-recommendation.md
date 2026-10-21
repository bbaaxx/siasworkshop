---
id: "mcp-migration-recommendation"
title: "Migration recommendation: adopt the MCP sidecar as the canonical agent decision path (retain direct LLM as fallback)"
category: "reference"
created: "2026-10-19"
updated: "2026-10-19"
related_initiatives: ["post-polish-batch-kev-coverage-recalibration-comfort-shelter-executor-mdocs-graph-hygiene", "mcp-model-behavior-research--2026-10-15"]
tags: ["research","llm","mcp","agents","architecture","decision-record"]
lifecycle: "stable"
knowledge_type: "reference"
confidence: "high"
source_initiatives: ["mcp-model-behavior-research--2026-10-15"]
---

# Migration recommendation: LlmClient → MCP sidecar

Decision record closing the `mcp-model-behavior-research` initiative
(2026-10-19). Question: should the mod's agent decision layer migrate from the
direct v1 path — in-mod HTTP client (`LlmClient`) to an OpenAI-compatible
endpoint, configured by `llm.endpoint` / `llm.model` / `llm.apiKey` — to the
MCP sidecar path (`agents.mcp.*`: mod spawns the sidecar subprocess, both
speak MCP over the child's stdio, the sidecar hosts the model session)?

## Recommendation

**ADOPT the sidecar as the canonical decision path for any install that opts
into a model; RETAIN the direct `llm.*` path as the fallback tier. No forced
migration, no code change required today — the measured evidence says the
migration has, functionally, already happened for opted-in installs.**

- For development and local-model play the question is settled: the MCP path
  is strictly better where it matters most (local reasoning models — see
  evidence). It is the integration surface going forward: new model onboarded
  = sidecar profile; new mod capability = registered MCP tool. The mod never
  learns endpoint/model/apiKey when MCP is on.
- For CurseForge distribution nothing changes: `agents.mcp.enabled` stays
  default OFF (hard constraint: no Python dependency), and the direct path +
  template fallback remain the no-model answers.
- The template fallback is permanent infrastructure, not a shim: sidecar
  death, malformed decisions, and rejects collapse into it today and must keep
  doing so.

## Evidence (measured 2026-10-16 → 2026-10-17; ledger `setup/agent-shared-ledger`)

| Round | Result | Ledger |
| --- | --- | --- |
| S0 spike (sidecar harness) | Cloud deepseek (n=3/mode): 9/9 valid, +0.1–0.3 s and +27–47 completion tokens vs v1 — negligible. Local bonsai-27b: v1 FAILED both runs (120 s timeout; empty_content at maxTokens 1024) while BOTH MCP modes succeeded (~39 s, ~350–390 tok, exact-key claims). The structured tool contract contains a reasoning model's deliberation where the v1 freeform prompt burns the whole budget. | SEQ-020 |
| S1 in-mod, behind flag | Routing live: `route(mcpEnabled, sidecarAvailable, llmEnabled)` pure fn gametest-pinned (mcp-alive beats everything; dead sidecar → direct llm if enabled else template). 4 gametests incl. full decide cycle on the real board and dead-sidecar fallback. Gate-order class of bug found and fixed (MCP branch must sit above the `!llm.enabled` early return). | SEQ-022, SEQ-023 |
| S1 live (TestSidecar01) | 13 decisions over ~18 min, ALL via MCP, zero transport failures; LM Studio showed matching traffic. Four real bugs found live and fixed (shelter-key leak into the MCP surface, reject-reason surfacing, STUCK_CYCLES 4→40, release cooldown — including its MCP bypass). | SEQ-024 |
| S1 retest | Full cycle eyewitnessed: decide → claim → walk → withdraw → place → auto-complete; cooldown march let the agent move past the unroutable anchor to reachable work. Vindication: when walkability + materials line up, the whole S1 chain closes. | SEQ-026 |
| S2 walk (sidecar harness) | Six-step building pattern walk converges end-to-end: cloud native 2/2 on the exact reference chain, local bonsai prompt-mode 1/1 (a 27B reasoning model completes a 6-step MCP workflow in prompt-constrained JSON). | SEQ-021 |
| Corpus Q4 | The decision surface is now a replayable, seeded instrument (4 failure-class scenarios, Player-1 scoring + JEVA metrics) — model-behavior CI exists for either path. | SEQ-031 |
| Tier-1 typed endpoints | kev-4b-ternary + jev-style-0.8B do NOT drop in for task-claim (jev-style stably inverted 96/96; kev's choice signal real but its coverage gate uncalibrated). Verdict: the LLM tier0 path — i.e. the sidecar/bonsai route — stays the production answer; typed endpoints continue only as kev + domain recalibration *inside the sidecar*. | SEQ-032 |

The pattern: v1's weakness is not speed or cost — on cloud it is parity within
noise. The weakness is robustness of the contract. Every live failure class
the game produced (unroutable anchor latch, material mismatch, station radius,
cooldown spins) was caught, measured, and pinned **because** the MCP surface
carries structure: typed tools, schema validation with reasons, and a wire
that can be tapped (`--tap`) and replayed (corpus). The freeform v1 prompt
hides the same failures as silent budget burnouts.

## What this decides

1. **The sidecar is the model-integration surface.** Per-model quirks stay in
   sidecar profiles; the mod's stable interface is the tool catalog
   (`content/mcp/McpTools` + the registry). This confirms the 2026-10-15
   direction with measurement.
2. **The config migration is already the runtime state when MCP is on**: the
   mod knows only `agents.mcp.command` (+ decision timeout, debug port);
   endpoint/model/apiKey live in the sidecar invocation (`sidecar.py`
   defaults target LM Studio bonsai-27b). The `llm.*` block's remaining role
   is the fallback tier.
3. **New behavior work goes into the MCP catalog first**, with the template
   fallback as the always-available no-model answer.

## Migration plan

- **Phase 0 — done (S1, 2026-10-16/17).** Precedence, fallback, structured
  rejects, live verification. No further code change is required to adopt.
- **Phase 1 — declarative (this page, 2026-10-19).** MCP path declared
  canonical for opted-in installs. Config defaults unchanged.
- **Phase 2 — optional cleanup, future cycles:**
  - Mark the `llm.*` config comments explicitly as "fallback tier" (they
    still read as primary).
  - If kev recalibration (`kev.calibrate` on corpus labels, models remain in
    `~/.cache` + `~/.venvs/kev`) produces a viable typed claim-choice
    endpoint, add it as a **sidecar profile** — the mod is untouched, which is
    the architectural point.
  - Dev-quality-of-life only: the two live-test traps stay documented (per-world
    `serverconfig/`; absolute sidecar path) — see `setup/session-handoff-2026-10-17`.

## Non-goals

- Removing the direct `llm.*` path. It is the fallback tier and the
  zero-Python-dependency configuration; deletion would buy nothing.
- Flipping any distribution default. CurseForge installs must not require
  Python.
- Executor warts (multi-chest `withdrawOnce`, no-progress watchdog) — separate
  polish initiative; they are executor bugs, not decision-layer questions.

## What would change this decision

- **kev + recalibration measured ≥ the LLM tier0 path on the corpus** (≥3/4
  failure-class scenarios correct with a calibrated threshold and clean null
  AUROC) → the typed endpoint takes the claim choice *inside the sidecar*;
  the mod-side story is unchanged. Tracked as the Tier-1 follow-up.
- **Catalog outgrows LLM tier0** (pattern tree past ~50–100 tools, or latency /
  calibration demands exceed it) → the decision-layer digest's Tier 2
  (JEVA-style head, ~13M-param LoRA project) becomes a separate initiative.
- **A no-Python typed runtime ships** (e.g. ONNX-exportable decision head with
  a Java-friendly runtime) → the distribution discussion reopens; the
  integration/distribution constraint is recorded in the initiative.

## Relation to the rest of the stack

- Design + D-decisions + open questions: [mcp-pattern-tree-design](mcp-pattern-tree-design.md)
- Tiered decision-layer plan (T0/T1/T2) this folds into: [decision-layer-jev-jeva](decision-layer-jev-jeva.md)
- Verifiable-reward recipe behind the corpus: [player-1-game-agents](player-1-game-agents.md)
- Harness runbook: `sidecar/README.md`; live evidence: `setup/agent-shared-ledger`
  SEQ-020..024, SEQ-026, SEQ-031, SEQ-032.
