---
id: "test-run-protocol-2026-10-20"
title: "Test-run protocol: experience the MCP decision layer (4 phases, zero → full game)"
category: "setup"
created: "2026-10-20"
updated: "2026-10-20"
related_initiatives: ["post-polish-batch-kev-coverage-recalibration-comfort-shelter-executor-mdocs-graph-hygiene", "mcp-model-behavior-research--2026-10-15", "mcp-hotplug-debug-access--2026-10-17", "executor-wart-polish-impossible-task-timeout-no-progress-watchdog-multi-chest-withdrawonce-self-collision-guard"]
tags: ["handoff","agents","llm","mcp","test-run","protocol"]
lifecycle: "evolving"
knowledge_type: "reference"
confidence: "high"
source_initiatives: []
---

# Test-run protocol — experience the decision-layer work

A tiered hands-on protocol (2026-10-20). Each phase stands alone; stop wherever
the novelty runs out. Phase 0 needs nothing but the repo; Phase 1 needs the
kev model already on disk (`~/.cache`, venv `~/.venvs/kev`); Phase 2 is the
real in-game experience and needs LM Studio + the game client; Phase 3 is
forensics. All commands run from `sidecar/` unless noted.

## Phase 0 — the instrument, zero setup (≈1 min, no network)

What you experience: the model-behavior CI that every claim in the research
initiative rests on — seeded trials × board permutations, Player-1 scoring,
JEVA metrics — running against a scripted position-bias stub.

```bash
python3 test_sidecar_bridge.py && python3 test_tap.py && \
python3 test_tap_import.py && python3 run_spike.py --replay replay/script.json \
  --scenario scenarios/stone_bricks_chest.json && \
python3 run_spike.py --walk --replay replay/walk-script.json \
  --scenario scenarios/walk_build.json && \
python3 test_corpus.py && python3 test_decision_probe.py
```

Expected: 7/7 green — bridge PASS, tap PASS (12 rows), tap-import PASS, S0
replay 20/20, walk replay 3/3, corpus 56 checks, probe 52 checks.

```bash
python3 run_corpus.py --corpus corpus --replay replay/script.json \
    --trials 2 --permutations 2 --tag demo
```

What to look at in the summary: the stub always claims the FIRST-listed task,
and the scorer says so precisely — `unroutable_anchor_first_listed`
mean_reward 0.000 across orders, `station_radius_edge` flip_rate 1.00 with a
per-order accuracy split, bootstrap CIs, null matrix idle. This is the
instrument catching exactly the live failure class (first-listed latch) that
bonsai exhibited in SEQ-024. Rows land in `metrics/corpus-demo-*.jsonl`.

Status: **run 2026-10-20, all green** (this protocol was proofed as written).

## Phase 1 — a typed decision endpoint live (≈10 min, no game, no bonsai)

What you experience: Tier-1 as measured — ONE SystemOne request with TWO
isolated questions (claim choice over the seeded option order + coverage
noul), raw probabilities back, the scorer computing null AUROC. kev-4b-ternary
is the interesting one: its choice signal was real but its coverage gate sat
at indifference until recalibrated — you can watch that with your own eyes.

```bash
# terminal 1 — serve kev from the on-disk model (v2.0, 1.7GB, MLX):
HF_HUB_OFFLINE=1 HF_HOME=/tmp/kev-empty-hf-cache KEV_BACKEND=mlx \
KEV_PREFIX_CACHE=1 KEV_PREFIX_MAX_TOKENS=4096 \
~/.venvs/kev/bin/python -m kev.serve --run ~/.cache/kev-4b-ternary-mlx \
  --host 127.0.0.1 --port 8010
# (first load takes a minute; system python is 3.14 — the venv is 3.13, do
#  NOT substitute python3 here; uv venvs ship no pip, installs go through
#  `uv pip install --python ~/.venvs/kev/bin/python`)

# terminal 2 — the corpus as SystemOne requests (104 seeded runs at default):
python3 probe_decision.py --corpus corpus --endpoint http://127.0.0.1:8010 \
    --tag kev-live
```

What to look for: per-row `option_probs` + `p_yes` in
`metrics/corpus-kev-live-*.jsonl`; the summary's cross-scenario null AUC.
Expected from the 2026-10-17 measurement: argmax permitted on
unroutable/station-radius, coin-flip on stone-vs-bricks, and P(yes) hovering
near 0.45–0.56 regardless of ground truth — the OOD indifference that
`kev.calibrate` on our labels is meant to fix. Stop the server when done
(Ctrl-C; it holds ~2GB RAM).

Status: **run 2026-10-20 — verdict reproduced exactly** (argmax 32/32 + 32/32,
18/32 coin-flip; coverage 0.45–0.49 claimable vs 0.563 null → all-rests +
null leak; `metrics/corpus-kev-live-20261020-…jsonl`; ledger SEQ-034).

## Phase 1b — the production model on the same corpus (≈35 min, hands-off)

Status: **run 2026-10-20 — first live Tier-0 numbers** (28 decisions/mode,
paired seeds, ~35 min both modes; ledger SEQ-034). Tier0 OFF: bonsai crushed
the claimable scenarios (+0.86…+1.00, flip_rate 0.00 — choices fully
order-invariant) but claimed the null board 8/8. Tier0 ON: choices stayed
order-invariant, but the coverage answer was ALWAYS 'claimable' (fpr 1.0 —
bonsai has no null sense) and station_radius regressed (−0.138, 2 parse
failures + reasoning-budget penalties). Verdict: tier0 stays experimental/OFF;
kev recalibration is strengthened (kev's coverage signal is the missing
component). Files: `metrics/corpus-bonsai-t0off-20261020-*.jsonl` +
`metrics/corpus-bonsai-t0on-20261020-*.jsonl`.

```bash
python3 run_corpus.py --corpus corpus --mode mcp-prompt --trials 4 \
    --permutations 2 --endpoint http://127.0.0.1:1234/v1 \
    --model prism-ml/bonsai-27b --tag bonsai-t0off
python3 run_corpus.py --corpus corpus --mode mcp-prompt --trials 4 \
    --permutations 2 --tier0 --endpoint http://127.0.0.1:1234/v1 \
    --model prism-ml/bonsai-27b --tag bonsai-t0on
```

## Phase 2 — the real thing: model decides in the game (≈30–60 min)

What you experience: the S1 chain end-to-end — the mod spawns the sidecar,
the model observes the live board over MCP, claims a task, and the agent
walks, withdraws, places, and the task auto-completes. This is the loop that
was live-verified in SEQ-026.

Setup (each is a documented trap — don't skip):

1. **LM Studio** serving `prism-ml/bonsai-27b` (http://127.0.0.1:1234/v1).
2. **Game client**: `./gradlew runClient`, world **TestSidecar01**.
3. **Per-world serverconfig**: in `run/saves/TestSidecar01/serverconfig/
   siasworkshop-server.toml` set `agents.mcp.enabled = true` and
   `agents.mcp.command` to the ABSOLUTE sidecar path, e.g.
   `python3 /Users/bbaaxx/Code/projects/siasworkshop/sidecar/sidecar.py
   --endpoint http://127.0.0.1:1234/v1 --model prism-ml/bonsai-27b
   --tap /Users/bbaaxx/Code/projects/siasworkshop/run/wire.jsonl`
   (the child inherits the game dir as cwd — relative paths miss; the
   defaultconfigs copy is already seeded, existing worlds need the toml
   copied in). Full JVM restart after editing.
4. Full restart, watch the log for `sidecar started (pid …)`.

The run:

1. Put **stone bricks** in a chest **≤16 blocks of the anchor** you are about
   to task (the fetch searches near the ANCHOR, not the player — plain stone
   fails the mazons palette; this exact mismatch is corpus scenario
   `missing_material_stone_vs_bricks`).
2. Raise the build task (the mazons build key; same scenario as SEQ-026:
   chest next to the anchor, agent nearby).
3. Watch: within one lap you should see `decided via MCP sidecar` → claim →
   the agent walks to the chest, withdraws, walks to the anchor, places, and
   the task completes and vanishes from the board.
4. Expect 30–90s per decision under load — game + 27B model on one machine
   produces constant "Can't keep up" bursts; that is documented behavior, not
   a fault. The unroutable `build:mazons:-20,86,0` still on the TestSidecar01
   board burns one lap per 5-min cooldown window (standing wart, cosmetic);
   the cooldown march should move the agent to reachable work after each
   release, exactly as in SEQ-026.

What it proves: every layer from the migration recommendation — mod-side dumb
client, sidecar-hosted session, structured rejects, template fallback under
failure — running against a real model in the real game.

## Phase 3 — forensics on your own session (≈5 min, optional)

What you experience: the hot-plug debug access + the tap→corpus pipeline.

```bash
# the --tap file from Phase 2, watched live:
tail -f ../run/wire.jsonl

# or attach read-only to the running server (when agents.mcp.debugPort > 0):
python3 tools/mcp_probe.py --port <debugPort> tools/list
python3 tools/mcp_probe.py --port <debugPort> observe_board --agent <uuid>

# your session becomes a corpus scenario skeleton (ground truth hand-added):
python3 tap_import.py ../run/wire.jsonl --out corpus/live-2026-10-20.json
```

## Stopping / cleanup

- Phase 1: Ctrl-C the kev server (releases ~2GB).
- Phase 2: exiting the world stops the sidecar (log shows the destroy line).
- Nothing in any phase writes outside `sidecar/metrics/`, `run/wire.jsonl`,
  and (Phase 2) the game world.

## Related

- The decision this all feeds: [mcp-migration-recommendation](../reference/mcp-migration-recommendation.md)
- Design + traps: [mcp-pattern-tree-design](../reference/mcp-pattern-tree-design.md)
- Full runbook: `sidecar/README.md`; evidence: [agent-shared-ledger](agent-shared-ledger.md) SEQ-020..024, SEQ-026, SEQ-031..033.
