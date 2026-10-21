---
id: "mcp-model-behavior-research--2026-10-15"
title: "Research: dedicated MCP layer for model-stable agent decisions"
status: "done"
created: "2026-10-15"
updated: "2026-10-20"
owner: "coordinator"
tags: ["research","llm","mcp","agents","architecture"]
related_wiki: ["reference/local-llm-feasibility", "setup/agent-shared-ledger", "setup/agent-ux-feedback-2026-10-16", "setup/session-handoff-2026-10-16", "setup/session-handoff-2026-10-17", "reference/mcp-pattern-tree-design", "reference/minecraft-mcp-servers", "reference/decision-layer-jev-jeva", "reference/mcp-migration-recommendation", "setup/test-run-protocol-2026-10-20"]
priority: "medium"
graduated: "2026-10-20"
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
- [x] S2: first real pattern tree against the building walk (three patterns against
      real mod machinery, same scenario as a v1 build task)
      → DONE in the sidecar harness (2026-10-16, results SEQ-021): six-step building
      walk end-to-end, D5 card gating + convergence polling, cloud 2/2 and local
      1/1 converged
- [x] Scenario corpus + assertions for question 4 (done 2026-10-17 — corpus Q4 plan
      below; ledger SEQ-031)
- [x] Recommendation → `reference/` wiki; if adopted, plan the LlmClient→MCP-client
      migration (config: sidecar address instead of endpoint/model/apiKey)
      → DONE (2026-10-19): `reference/mcp-migration-recommendation` — verdict
      ADOPT-with-retention (sidecar canonical for opted-in installs, direct
      llm.* retained as fallback tier, template fallback permanent, no code
      change required; phase-2 cleanup optional). Ledger SEQ-033.

### Corpus Q4 plan (2026-10-17)

Research question 4 made concrete: measurable "stable behavior" — a scenario
corpus (recorded observations + expected action classes) replayable offline
against any endpoint, hosted in the sidecar (model-behavior CI). Templates:
Player-1 verifiable-reward recipe (dense score increments; invalid-call penalty
+ wait fallback; reasoning-length penalty; no terminal penalties; coverage
over repeated trials with CIs) and JEVA Tier-0 factorization (seeded board
shuffle; coverage/null asked as a separate question, never a list option; flip
/ all-order / null-detection metrics). Diversity axes target the three live
failure classes (ledger SEQ-024/026): unroutable anchor, wrong/missing material,
station-radius ignorance.

Design decisions (grounded in the 2026-10-17 recon of `sidecar/`):
- Sidecar-only work (Python, stdlib-only, repo test conventions). The corpus
  measures the one-shot `decide` surface — the S0 5 tools, i.e. exactly the
  live mod surface (`McpTools`); walk tools stay mock-only and out of scope.
- Ground truth lives in scenario metadata (the "privileged world state" —
  Player-1 finding 3); the scorer computes assertions harness-side, never
  exposed to the model. Claiming a forbidden task still succeeds mock-side —
  the DECISION is judged by the scorer, mirroring live behavior (failures
  surface post-claim: stuck spin, missing_material, radius fetch). Minimal
  mock change: honor `claimable:false` at claim time (reject), so
  null-ground-truth scenarios can model "listed but not claimable" (cooldown).
- Tier-0 ships as an experimental, profile-gated decide path; the production
  decide flow and the mod-side wire contract stay untouched.
- Tap → corpus linkage is design intent today (README P1) with no code; the
  importer closes it but ground truth is always hand-annotated post-import.

Deliverables:
- [x] `sidecar/corpus_score.py` — Player-1 scoring: correct decision class
      = +1; wrong = 0; invalid call (malformed/unknown tool/bad args/no
      tool_call) = −1 with the decision recorded as `wait_fallback`; minus
      reasoning-length penalty 0.005/token capped 1.0 beyond the profile
      budget; no terminal penalties. Aggregation: mean reward, coverage
      ≥1/2/4-of-N over trials, percentile bootstrap CIs. JEVA metrics: flip
      rate + all-order accuracy across seeded board permutations; null
      detection as a confusion matrix at the binary coverage answer (a binary
      answer gives no AUROC — record any optional confidence field so AUROC
      becomes computable at Tier 1; document this).
- [x] `sidecar/run_corpus.py` — runner: loads `corpus/*.json`, runs N seeded
      trials per scenario against any endpoint (`--replay` FakeModel for CI,
      `--endpoint/--model` live, cloud config like run_spike), permutation
      runs when the scenario opts in, rows appended to
      `metrics/corpus-<tag>-<ts>.jsonl`, per-scenario×mode summary to stdout.
- [x] `sidecar/corpus/` — three hand-authored scenarios (one per failure
      class, from the SEQ-024/026 facts) + `gen_variants.py` (seeded generator
      across the randomization axes: chest distance, station radius, materials
      present/missing, anchor routability, board order) so assertions test
      invariance, not one memorized layout. (As built: four scenarios — the
      null-board-cooling case was needed to exercise `claimable:false`.)
- [x] Tier-0 experimental decide path (`session.py`,
      off by default): seeded per-call shuffle of board lines
      (seed recorded in the metrics row); coverage asked as a separate
      question — prompt mode gains a `coverage` boolean pair in the JSON
      contract, native mode gains a required `any_claimable` bool arg on the
      tier0 decide surface — answers recorded for null-detection metrics.
      (As built: session.py only; wiring the live bridge `sidecar.py` to the
      profile flag is deferred — nothing exercises it today.)
- [x] `sidecar/tap_import.py` (+ test) — convert a recorded `--tap` wire.jsonl
      into a corpus scenario skeleton (observation + open_tasks from the tap's
      observe_self/observe_board traffic), `assert` block left for hand
      annotation.
- [x] `sidecar/test_corpus.py` (+ `test_tap_import.py`) — scorer unit cases +
      small-corpus FakeModel regression: a position-biased script (always
      claims first-listed) must fail the unroutable-anchor scenario and flip
      across permutations; a good script must pass; repo convention — plain
      `python3 <file>.py`, exit non-zero on failure.
- [x] Fix in the corpus path: `sidecar.py --replay` passes an empty scenario
      dict to FakeModel (`{{build_key}}`/`{{player}}` substitute to "") —
      load the scenario file properly. Leave the dead `open_keys` line
      (sidecar.py:136) for a later cleanup pass.
- [x] Verification: all six python entry points green (bridge, tap, S0 replay,
      walk replay, corpus, tap import); `./gradlew build` (no Java changes);
      `mdocs validate`.

Out of scope for Q4: Gradle/CI wiring for the runner (ops question 5), live
endpoint corpus runs (manual, later), Tier-1 self-hosted typed-decision
evaluation, the migration writeup (the remaining checkbox).

### Tier-1 typed-decision spike plan (2026-10-17 night)

Trigger: user direction — test decision models locally ("test on lm studio, then
think about integrating into the sidecar or the mod; so small that maybe it can
ship with distribution"). Corrections established from the model cards:
- **LM Studio can download these models but cannot serve them.** Kev is a
  prefill-only decision scorer with a pointer head + custom MLX runtime — the
  card states stock llama.cpp/transformers/mlx-lm cannot load it, and chat is an
  explicit out-of-scope use. LM Studio's kev download also lacks `runtime/`.
  LM Studio stays a fine *download mechanism*; serving uses each repo's runtime.
- **Candidates** (both Apache-2.0, both out-of-repo under `~/.cache` + a py3.13
  uv venv — system python is 3.14, runtimes want 3.12/3.13):
  - `Jakevin/kev-4b-ternary-mlx` v2.0 — 1.7GB, decision-v7 dev 0.8647 vs bf16
    0.8718; `python -m kev.serve --run . --port 8010` → `/v1/systemone`.
  - `chaoliangUNSW/Jev-Style-0.8B-Decision-v3-MLX` — 8-bit 0.80GB, 25.6K-token
    inputs, systemone-compatible API; 1/5th Kev's size → the interesting
    distribution datapoint. Caveat noted on card: some training rows are
    OpenAI/Anthropic outputs (license diligence if ever bundled).

Deliverables:
- [x] Model setup: full snapshots (kev incl. `runtime/`), venv, runtime deps,
      both served on loopback ports; curl smoke against `/v1/systemone`.
- [x] `sidecar/probe_decision.py` — corpus → SystemOne renderer: state =
      observation + board; ONE request per run with TWO isolated questions
      (claim = choice over the run's seeded option order; coverage = noul —
      JEVA factorization native to the API; no position identifiers). Derived
      decision: coverage-no → rest, else argmax option. Rows extend the corpus
      19-key schema: mode `systemone:<tag>`, P(yes) as `coverage_confidence`
      (soft score — makes null AUROC computable), full `option_probs`, measured
      latency. Same seed scheme as run_corpus. Tolerant single-function parser
      (wire shape verified against the live server; one small edit if keys
      differ). (As built: parser needed the `noul` key + `input/output_tokens`
      aliases — added after the live curl.)
- [x] `corpus_score.py` extension: null AUROC + TPR at ~5% FPR from soft
      `coverage_confidence` scores (the Tier-1 payoff the binary LLM path
      couldn't compute). Pure, stdlib, existing 56 checks stay green.
- [x] `sidecar/test_decision_probe.py` — in-thread stub `/v1/systemone` server;
      happy path, scripted option-order flip (measures the caveat directly),
      null-board rest + AUROC, wire-mismatch error surfacing raw keys.
- [x] Live runs: 4 hand-authored scenarios × seeded trials/perms against both
      endpoints → `metrics/corpus-kev-*.jsonl` / `metrics/corpus-jevstyle-*.jsonl`;
      compare against each other + published JEVA-class numbers; result and
      adopt/shape recommendation recorded in the initiative + ledger.
- [ ] Integration discussion DEFERRED until numbers land (user's distribution
      idea recorded here): constraint — in-mod Java inference of a 0.8–4B model
      is not realistic; plausible shapes are sidecar-bundled loopback endpoint,
      external-runtime dependency, or ONNX export; decide with measurements.
- [x] Verification: stub tests green, live runs complete, full python suite
      green, `mdocs validate`.

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
- [2026-10-17] Corpus Q4 done (evening; two parallel builder agents, coordinator
      re-verified on the combined tree). Delivered: `corpus_score.py` (pure-function
      Player-1 scoring + seeded bootstrap CIs + JEVA flip/all-order/null-matrix;
      binary coverage answer gives no AUROC — `coverage_confidence` recorded for
      Tier 1), `run_corpus.py` (seeded trials × board permutations vs `--replay`
      FakeModel or any live endpoint; 19-key JSONL rows incl. `board_order`),
      `corpus/` 4 hand-authored failure-class scenarios + `gen_variants.py`
      (5 randomization axes; forbid/null derived from materials × radius ×
      routability), `tap_import.py` (wire.jsonl → corpus skeleton; closes the
      README-P1 intent), `test_corpus.py` 56 checks + `test_tap_import.py`.
      Tier-0 experimental decide path: `session.run_decision(..., tier0=False,
      rng=None)` — caller-seeded board shuffle + coverage as a SEPARATE question
      (prompt JSON `coverage` object / native required `any_claimable` + optional
      `confidence`, stripped pre-validation); tier0=False byte-identical (S0 replay
      20/20 proof). mockmod: `claimable:false` tasks listed, claim rejects with the
      real mod's cooldown string. Fixed en route: `catalog.resolve()` list aliasing
      (mutated shared `Tool.inputs`); `sidecar.py --replay` gained `--scenario`
      (FakeModel placeholders were silently empty). Verification: python suite all
      green (bridge/tap/tap-import/S0 20-20/walk 3-3/corpus 56), `./gradlew build`
      SUCCESSFUL, mdocs valid. Live corpus runs are manual (next: bonsai tier0
      on/off, then Tier-1 JevK5/Kev-4B). Ledger SEQ-031. Remaining checkbox: the
      migration recommendation writeup.
- [2026-10-17] Tier-1 typed-decision spike MEASURED (same night, user-triggered).
      Setup findings: LM Studio ruled out as a SERVER by the model cards (Kev =
      prefill-only decision scorer + pointer head + custom MLX runtime — "stock
      llama.cpp/transformers/mlx-lm cannot load this artifact"; chat is
      out-of-scope; LM Studio's own kev download even lacked `runtime/`) — it
      served as download mechanism only. Served out-of-repo: kev-4b-ternary v2.0
      on :8010 (included runtime, 1.7GB) + Jev-Style-0.8B-v3 8bit on :8011
      (`jev-style[mlx]` pkg, 0.80GB); uv py3.13 venv (`~/.venvs/kev`). Wire shapes
      identical; probe parser needed one fix (`noul` key + `input/output_tokens`).
      `probe_decision.py` (52 checks) renders the corpus as SystemOne requests —
      ONE request, TWO isolated questions (claim choice over the seeded option
      order + coverage noul — JEVA factorization native to the API), derived
      decision gates the claim on P(yes) ≥ 0.5; rows extend the 19-key corpus
      schema with `option_probs` + `p_yes`. `corpus_score` gained `null_auc`
      (Mann-Whitney AUC + TPR@~5%FPR) — the soft-score metric the binary LLM path
      couldn't compute. LIVE RESULTS (104 seeded runs each; metrics/corpus-kev-
      ternary-v2-20261017-224454.jsonl + corpus-jevstyle-0.8b-v3-8bit-20261017-
      224552.jsonl): (1) jev-style-0.8B — argmax picks the FORBIDDEN task 96/96
      non-null runs (all three failure classes stably inverted; probability mass
      swings 0.22↔0.78 with order but argmax doesn't), coverage polarity inverted
      (cross-scenario null AUC 0.0); its single +1 (null board) was a 0.5-gate
      artifact. Not viable without fine-tuning. (2) kev-ternary — choice content
      signal REAL: argmax permitted 32/32 on unroutable_anchor, 32/32 on
      station_radius_edge, 18/32 on missing_material (the stone-vs-bricks nuance
      flips its coin; argmax-flip 6/8 trials); BUT the coverage gate sits at OOD
      indifference (P(yes) ≈ 0.47 claimable vs 0.563 null), so unroutable+station
      derived "rest" (missed opportunity, reward 0 despite the right argmax) and
      the null board derived "claim" (0). A domain-recalibrated threshold (~0.44)
      flips 3 of 4 scenarios correct — exactly the card's own "calibrate on your
      labels" prescription (`kev.calibrate` exists). VERDICT: typed endpoints do
      NOT drop in for embodied task-claim out of the box; Tier-1 continues only
      as kev + recalibration/fine-tune on corpus labels; jev-style's 5× size
      advantage doesn't offset inversion; the LLM tier0 path stays the production
      answer. Suite: 7 python entry points green. Servers stopped; models remain
      in `~/.cache` + `~/.venvs/kev` for the recalibration round. Ledger SEQ-032.
      Integration/distribution discussion deferred (constraint recorded: in-mod
      Java inference of a 0.8–4B model is not realistic).
- [2026-10-19] Migration recommendation written → `reference/mcp-migration-recommendation`
      (the initiative's last open item; user picked it from the focus menu). Verdict:
      ADOPT-with-retention — the MCP sidecar is declared the canonical decision path
      for opted-in installs (config migration already the runtime state: the mod knows
      only agents.mcp.command when MCP is on; endpoint/model/apiKey live in the
      sidecar invocation); the direct llm.* path is retained as the fallback tier and
      the zero-Python distribution config; template fallback permanent; NO code change
      required (phase-0 S1 already delivered precedence+fallback+rejects, live-verified
      SEQ-024/026). Evidence table: S0 cloud parity + local v1 budget burnouts
      (SEQ-020), S1 live + fixes (SEQ-022..024), retest vindication (SEQ-026), S2 walk
      convergence (SEQ-021), corpus Q4 instrument (SEQ-031), Tier-1 no-drop-in verdict
      (SEQ-032). Phase-2 cleanup optional: recomment llm.* as fallback tier; kev
      recalibration lands as a SIDECAR PROFILE (mod untouched) if it beats tier0 on
      the corpus. Decision-change triggers recorded (kev recalibrated ≥ tier0; catalog
      >50–100 tools → T2; no-Python typed runtime → distribution discussion reopens).
      Also ticked the S2 checkbox (was done 2026-10-16, box never checked). Backlinks
      reciprocal (initiative ↔ page); INDEX updated. Initiative COMPLETE after this
      entry — all 24 boxes ticked. Ledger SEQ-033.
- [2026-10-20T02:11:30.307Z] Marked done via mdocs command
