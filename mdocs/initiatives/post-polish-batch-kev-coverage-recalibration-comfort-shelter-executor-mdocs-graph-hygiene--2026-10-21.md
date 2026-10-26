---
id: "post-polish-batch-kev-coverage-recalibration-comfort-shelter-executor-mdocs-graph-hygiene"
title: "Post-polish batch: kev coverage recalibration, COMFORT_SHELTER executor, mdocs graph hygiene"
status: "done"
created: "2026-10-21"
updated: "2026-10-21"
owner: "coordinator"
tags: ["agents","sidecar","kev","comfort","mdocs","hygiene"]
related_wiki: ["setup/test-run-protocol-2026-10-20","reference/mcp-migration-recommendation","reference/local-llm-feasibility","setup/agent-shared-ledger","setup/gametest-notes-1-21-1", "setup/session-handoff-2026-10-26"]
---

## Objective

Three follow-ups agreed 2026-10-21 after the executor-wart polish shipped:

1. **kev coverage recalibration** (SEQ-034 verdict: kev's choice signal is real, its
   coverage gate sits at OOD indifference P(yes)~0.45–0.56). Fit a temperature +
   decision threshold on OUR labeled corpus against the live kev server, bake the
   artifact into the repo, and wire it into probe_decision as `--calibration`.
   kev.calibrate itself cannot be used (it requires logits; the server only serves
   probabilities) — sidecar-local stdlib fit on p_yes instead.
2. **COMFORT_SHELTER executor** — the only task type nobody executes; the board
   hides it from all claimants to avoid ping-pong. Hand-rolled canopy executor
   modeled on executeLight (places-only): 3×3 opaque roof over the player's column
   (clears `exposed`) + one bed nearby (clears `no bed`); detector stays the
   completion oracle (auto-complete CLAIMED → DONE). Live model run is NOT a
   prerequisite — config-gated Java + gametest work.
3. **mdocs graph hygiene** — 4 done initiatives flagged "no stable wiki learning"
   (agent-embodiment-observation, llm-integration-research, local-llm-feasibility,
   publish-curseforge-1-0-2). Satisfy the graph rule (stable-lifecycle wiki page
   with source_initiatives backlink, or related_wiki→stable) using existing pages
   where content genuinely covers the initiative.

## Plan

- [x] Task 5: read the 4 initiatives + candidate wiki pages; set lifecycle/source
      links; mdocs validate warnings → 0
- [x] Task 3: executeShelter in AgentDecisionLoop (canopy+bed placements, reuse
      navigate/withdrawOnce/guards; player-bbox entombment generalization;
      ComfortDetectors.isSheltered helper); remove openTasks/highestPriority
      exclusions; rewrite mcpShelterHiddenFromSurface to visible+claimable;
      3 gametests (canopy+auto-complete, player-offline release, never-entomb)
- [x] Task 1: collect live kev rows via probe_decision (server warming meanwhile);
      calibrate_coverage.py (temperature + FPR-budget threshold, stdlib only);
      probe_decision --calibration + p_yes_cal row key; test_calibrate_coverage.py
      incl. SEQ-034 indifference fixture; fit → sidecar/calibration/ artifact
- [x] Verify: gametests green, python suite green, mdocs valid; commit

## Progress Log

- [2026-10-21] Initiative created; scope pinned from user direction "1, 3 and 5"
      with the Phase-2 live test explicitly deferred by the user (not a
      prerequisite for the shelter executor).
- [2026-10-21] Task 5 done: mdocs validate clean=true warnings=0. Three durable
      deliverable pages graduated draft→stable (agent-embodiment-observation,
      llm-minecraft-landscape, local-llm-feasibility); curseforge-publishing
      backlinked to publish-curseforge-1-0-2 both directions; the umbrella
      initiative's five related_wiki pages backlink it.
- [2026-10-21] Task 1 done (live): 112 fresh rows from kev-4b-ternary via
      probe_decision (kev served locally on :8010). FINDING: the coverage signal
      is cleanly separated but INVERTED — claimable boards answer P(yes)≈0.4735
      (0.453–0.499), the null board answers a constant 0.5631 across all 8 runs.
      SEQ-034's "indifference" is a systematic inversion, not noise. Fix:
      calibrate_coverage.py fits temperature (121-pt log grid, same as
      kev.metrics.TEMPERATURE_FIT) + FPR-budget threshold, detecting the
      direction ("below": claim iff p <= threshold). Artifact baked at
      sidecar/calibration/coverage-kev-4b-ternary.json (T=4.0, threshold
      0.49975, TPR 0.0→1.0); probe_decision gained --calibration + p_yes_cal
      rows; live calibrated probe confirms: null_board_cooling 0.000→+1.000,
      station_radius_edge / unroutable_anchor 0.000→+1.000. Caveat recorded in
      the artifact: fit from served probabilities (kev.calibrate needs logits
      and cannot consume these rows).
- [2026-10-21] Task 3 done: executeShelter (canopy 3×3 at +2 + one bed at a
      free supported neighbor cell, bed level scanned dy∈{0,-1} because players
      rest a block above the surface they stand on; shelter arrival 3.5 blocks
      because pathing cannot end inside the player; zero-progress → transient
      release mirroring the light executor). Exclusions removed from openTasks/
      highestPriority (comfort now priority over builds; player-online filter
      covers shelter too); mcpShelterHiddenFromSurface rewritten to its inverse
      (visible + claimable, payload contract); tests: canopy+auto-complete,
      player-offline release, occupied-cell refusal. Traps burned en route,
      added to wiki gametest-notes: the empty template is a 3×3×3 air cube
      wrapped in a BARRIER shell (rel ≈ -1..3) — never anchor builds inside it;
      mock players land in level.players() a tick after join (raise comfort
      tasks in the delayed phase or the orphan cleaner eats them); test-timeout
      fail paths must clean up strays or later batches inherit them.

- [2026-10-21] VERIFY PASS: all 48 gametests pass (3 new shelter tests + the
      rewritten MCP surface test); python suite fully green (bridge/tap/
      tap-import/spike/corpus/probe/calibration); live calibrated probe vs kev
      confirms the artifact end-to-end; mdocs valid=true clean=true warnings=0,
      index consistent. kev server stopped after data collection.
- [2026-10-21T05:39:26.728Z] Marked done via mdocs command

## Artifacts

- kev server warming on 127.0.0.1:8010 (kev-4b-ternary-mlx, task bash-1lp7u4oe)
