---
id: "session-handoff-2026-10-17"
title: "Session Handoff — 2026-10-19 (research initiative COMPLETE; SUPERSEDED by session-handoff-2026-10-26)"
category: "setup"
created: "2026-10-17"
updated: "2026-10-20"
related_initiatives: ["mcp-model-behavior-research--2026-10-15", "mcp-hotplug-debug-access--2026-10-17", "research-digest-player-1-synthetic-game-agents--2026-10-17", "research-digest-jev-jeva-decision-layer--2026-10-17", "executor-wart-polish-impossible-task-timeout-no-progress-watchdog-multi-chest-withdrawonce-self-collision-guard"]
tags: ["handoff","agents","llm","mcp","session-state"]
lifecycle: "stable"
knowledge_type: "reference"
confidence: "high"
source_initiatives: []
---

# Session handoff — 2026-10-19 revision (superseded; current: session-handoff-2026-10-26)

> Superseded by `session-handoff-2026-10-26` (2026-10-22 build-executor fixes
> + knowledge ledger; 2026-10-25 break primitive; active roadmap: agents
> action-surface expansion). Kept for the research-era record.

Canonical handoff as of 2026-10-19 (fourth revision; v3 was 2026-10-17 late
evening). Revision history: v1 "S1 live-verified; dev pause for research"
(pre-pause story now in `session-handoff-2026-10-16` + ledger SEQ-020..026) →
v2 "research digests done; hot-plug built; next: corpus Q4" → v3 "corpus Q4
shipped; next: migration writeup, then Tier-1" → **v4 (this): research
initiative COMPLETE — corpus Q4 built, Tier-1 typed endpoints measured, the
migration recommendation is written (`reference/mcp-migration-recommendation`,
verdict ADOPT-with-retention).**

**STATUS: the `mcp-model-behavior-research` initiative is COMPLETE (all 24
boxes; 2026-10-19).** Next candidates, in no fixed order: the
integration/distribution discussion (user's "bundle a tiny decision model"
idea — now unblocked by Tier-1 numbers), the executor wart polish
(multi-chest `withdrawOnce`, no-progress watchdog), the kev recalibration
round (`kev.calibrate` on corpus labels), and the bonsai tier0 on/off live
run. None is a standing initiative yet — user picks.

Reading order for a fresh agent:
1. `AGENTS.md`, then this file.
2. `setup/agent-shared-ledger` — SEQ-028 (digests), SEQ-029 (hot-plug),
   SEQ-030 (v2 wrap), SEQ-031 (corpus Q4), SEQ-032 (Tier-1 spike),
   SEQ-033 (migration recommendation), **SEQ-034 (2026-10-20 live test-run:
   kev verdict reproduced + FIRST bonsai tier-0 numbers — bonsai has NO null
   sense, tier0 stays experimental/OFF)**.
3. The three digest pages: `reference/player-1-game-agents`,
   `reference/minecraft-mcp-servers`, `reference/decision-layer-jev-jeva`.
4. **`reference/mcp-migration-recommendation` — the closing decision record:**
   MCP sidecar = canonical decision path for opted-in installs; direct
   `llm.*` retained as fallback tier; no code change required.
5. Initiative `mcp-model-behavior-research--2026-10-15` — read-only history
   (COMPLETE); its Progress Log is the as-built record.
6. `reference/mcp-pattern-tree-design` — includes the "Debug access channel
   (hot-plug)" section.
7. `sidecar/README.md` — the "Q4: the scenario corpus" section has the run
   commands (corpus runner, tier0, tap import, decision probe).

## Where things stand

- **Research pause: COMPLETE** (three digests; see SEQ-028 for the one-paragraph
  summary of all three + the tiered T0/T1/T2 recommendation).
- **Hot-plug debug access: DONE** (SEQ-029): `--tap` wire tap +
  `agents.mcp.debugPort` read-only attach; 38/38 gametests.
- **Corpus Q4: DONE** (SEQ-031, evening build; sidecar-only, no Java):
  - `corpus_score.py` — pure-function Player-1 scoring (+1/0/−1-wait-fallback,
    reasoning-length penalty capped 1.0, no terminal penalties, seeded bootstrap
    CIs, coverage ≥1/2/4) + JEVA metrics (flip rate, all-order accuracy,
    null-detection matrix; binary coverage gives no AUROC — `coverage_confidence`
    recorded so Tier-1 typed endpoints can compute it).
  - `run_corpus.py` — seeded trials × board permutations against `--replay`
    FakeModel (CI) or any live endpoint; 19-key JSONL rows incl. `board_order`.
  - `corpus/` — 4 hand-authored scenarios encoding the three live failure
    classes (unroutable anchor first-listed, stone-vs-bricks material mismatch,
    station-radius edge, null board w/ cooling task) + `gen_variants.py`
    (5 randomization axes; forbid/null derived from materials × radius ×
    routability).
  - `tap_import.py` — a recorded `--tap` wire.jsonl becomes a corpus scenario
    skeleton (README-P1 design intent now real; ground truth hand-annotated).
  - Tier-0 experimental decide path: `session.run_decision(..., tier0=False,
    rng=None)` — seeded board shuffle + coverage as a SEPARATE question (JEVA
    Prop 2); OFF by default, byte-identical when off (20/20 replay proof).
  - Tests: `test_corpus.py` 56 checks (incl. position-bias-stub flips across
    permutations / good-stub invariant integration) + `test_tap_import.py`.
  - En-route fixes: `catalog.resolve()` list aliasing (corrupted shared
    `Tool.inputs`); `sidecar.py --replay --scenario` (FakeModel placeholders
    were silently empty).
- **Tier-1 typed endpoints: MEASURED** (SEQ-032, 2026-10-17 night): kev-4b-ternary
  (real choice signal, uncalibrated coverage gate) + jev-style-0.8B (stably
  inverted) — no drop-in win; LLM tier0 path stays production; kev +
  recalibration is the only future typed candidate. LM Studio = download
  mechanism only (the models need their own runtimes). Models in `~/.cache`,
  venv `~/.venvs/kev`, both servers STOPPED.
- **Migration recommendation: WRITTEN** (SEQ-033, 2026-10-19): `reference/
  mcp-migration-recommendation` — ADOPT-with-retention; research initiative
  COMPLETE, no code change required. Tier-1 follow-ups (kev recalibration,
  JevK5-GGUF) continue as sidecar profiles if they pan out — the mod is
  untouched either way.
- **Known executor warts** (unchanged, queued polish): `withdrawOnce` visits
  only the FIRST chest in scan order; arrival-circling evades the stuck
  detector (no-progress watchdog is the real fix — adoption candidate from the
  MCP-landscape digest).
- **Open workstream**: NONE for this initiative (COMPLETE). Candidates queued
  for user pick: integration/distribution discussion (numbers now landed),
  executor wart polish, kev recalibration round, bonsai tier0 live run, ops Q5.

## Next moves (no fixed order — user picks; none is a standing initiative yet)

1. **Integration/distribution discussion** — the "bundle a tiny decision
   model" idea, now with Tier-1 numbers: in-mod Java inference of 0.8–4B is
   unrealistic; shapes = sidecar-bundled loopback / external runtime /
   ONNX export. kev recalibration would feed this decision.
2. **Executor wart polish** — NOW A STANDING INITIATIVE (created 2026-10-20,
   user-requested): `executor-wart-polish-impossible-task-timeout-no-progress-
   watchdog-multi-chest-withdrawonce-self-collision-guard` — impossible-task
   timeout (the mid-air `-20,86,0` burns a lap per cooldown forever), the
   no-progress watchdog, multi-chest `withdrawOnce`, and the NEW
   self-collision guard (agent entombed itself and suffocated mid-"placing"
   in the 2026-10-20 live session, SEQ-035).
3. **kev recalibration round**: `kev.calibrate` on corpus labels (models +
   venv remain on disk; restart commands in the initiative Progress Log).
4. **kev recalibration round**: `kev.calibrate` on corpus labels (models +
   venv remain on disk; restart commands in the initiative Progress Log).
   SEQ-034 strengthened the case: bonsai's coverage answer is ALWAYS 'yes'
   (fpr 1.0) while kev's P(yes) carries separable signal (0.47 vs 0.563).
5. Ops (question 5, whenever): Gradle/CI wiring for the corpus runner.

## Traps that cost real time (accumulated; S1-era ones still apply)

- **Server configs are PER-WORLD** (`<world>/serverconfig/`): seed
  `run/defaultconfigs/siasworkshop-server.toml` AND copy into existing worlds.
- **Javadoc-vs-code drift is real**; **gate-order bugs hide in early-returns**
  (pin pure routing fns); **list-filter fixes are bypassable by second readers**
  (enumerate EVERY caller); **reject payloads must carry their reason**.
- **mdocs state: drive the CLI, not the session MCP tools.** Details:
  `setup/mdocs-kimi-plugin-fix`.
- **Gametest structure files resolve per class name**: a new `*GameTest` class
  needs `src/main/resources/data/siasworkshop/structure/<classlower>.empty.nbt`
  (copy an existing one) or the server crashes with "Missing test structure".
- **Validator reciprocal backlinks**: every `related_wiki` entry on an
  initiative needs the initiative id in the page's `related_initiatives`, and
  vice versa — validate before claiming done.
- Gametests are 38; same standing traps (no `@GameTest(timeout=)`, shared batch
  world, production tick loop runs during tests, claim your OWN key).
- **Python suite is now SEVEN entry points** (run from `sidecar/`):
  `test_sidecar_bridge.py`, `test_tap.py`, `test_tap_import.py`,
  `run_spike.py --replay replay/script.json`,
  `run_spike.py --walk --replay replay/walk-script.json`, `test_corpus.py`,
  `test_decision_probe.py`.
- **NEW (SEQ-032): decision-head models (kev/jev-style) need their OWN
  runtimes** — LM Studio/llama.cpp/transformers cannot load them (prefill-only
  scorer + pointer head + custom MLX runtime); LM Studio is a fine downloader
  only. Both runtimes want py3.12/3.13 (system python is 3.14) — the working
  setup is the uv venv `~/.venvs/kev`; install with `uv pip install --python
  $V/bin/python` (uv venvs ship no pip). Models in `~/.cache`, servers stopped.
- **NEW (SEQ-031): never extend `catalog.resolve()` output in place** — it now
  copies lists because appending to a resolved `required[]` mutated the shared
  `Tool.inputs` for every later run. Same class of bug as the list-aliasing
  trap: resolve-then-mutate is a corruption vector.
- **NEW (SEQ-031): `FakeModel` placeholders need the real scenario** —
  `sidecar.py --replay` now takes `--scenario` (default
  `scenarios/stone_bricks_chest.json`); without it `{{build_key}}`/`{{player}}`
  silently substitute to `""`/`"Dev"`.

## Environment facts

- M3 Max; LM Studio local (`http://127.0.0.1:1234/v1`, `prism-ml/bonsai-27b` —
  reasoning model, 30–90s per MCP decision under load). Game + model on one
  machine = constant "Can't keep up" bursts.
- Live-test recipe: full JVM restart, world TestSidecar01, `agents.mcp.enabled=true`
  with the ABSOLUTE sidecar path in its serverconfig; raise a build task with
  stone-bricks chest ≤16 blocks of the anchor; watch `sidecar started (pid …)`
  then `decided via MCP sidecar`. Add `--tap /abs/path/run/wire.jsonl` to the
  command for forensics-free rounds; convert the tap afterwards with
  `python3 sidecar/tap_import.py run/wire.jsonl --out sidecar/corpus/live-….json`
  (hand-annotate the `assert` block).
- The TestSidecar01 board still holds the unroutable `build:mazons:-20,86,0` —
  burns one agent lap per 5-min cooldown window; cosmetic. It is also the
  anchor of corpus scenario `unroutable_anchor_first_listed`.
- mdocs: after ANY hand-edit under `mdocs/`: `./node_modules/.bin/mdocs validate`.
