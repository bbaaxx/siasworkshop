---
id: "executor-wart-polish-impossible-task-timeout-no-progress-watchdog-multi-chest-withdrawonce-self-collision-guard"
title: "Executor wart polish: impossible-task timeout, no-progress watchdog, multi-chest withdrawOnce, self-collision guard"
status: "done"
created: "2026-10-20"
updated: "2026-10-20"
owner: "coordinator"
tags: ["agents","executor","polish"]
related_wiki: ["setup/agent-shared-ledger", "setup/session-handoff-2026-10-17", "setup/test-run-protocol-2026-10-20", "setup/session-handoff-2026-10-26"]
graduated: "2026-10-20"
---

## Objective

Fix the four executor warts surfaced by live play (SEQ-024/026, SEQ-034, and
the 2026-10-20 live session). These are mod-side machinery bugs — the decision
layer (MCP sidecar) is explicitly OUT of scope; models did nothing wrong in
any of these episodes.

## The warts (evidence-linked)

1. **Impossible-task timeout (user-requested 2026-10-20).** The standing
   `build:mazons:-20,86,0` on TestSidecar01 floats mid-air at y=86 — no path
   exists. Lifecycle today: claim → unroutable → stuck → release → 5-min
   cooldown (hidden from the releasing agent's board view) → re-claim → one
   burned model lap per window, forever. Tasks have no terminal state for
   "the world makes this impossible". (It took the session's first lap at
   12:09:08 today.)
2. **No-progress watchdog.** The stuck detector counts UNMOVING ticks, so
   arrival-circling (constant motion, zero progress) evades it. Adoption
   candidate from the minecraft-mcp-servers digest (SEQ-028).
3. **Multi-chest `withdrawOnce`.** Visits only the FIRST chest in scan order
   per cycle; a stocked chest behind an unstocked one is invisible (SEQ-026).
4. **Self-collision guard / self-preservation (NEW, 2026-10-20).** The
   executor places blocks without checking whether the agent occupies the
   target cell. Live outcome: the agent entombed itself between leaves and
   its own blocks and SUFFOCATED mid-"placing" — log line: `Workshop Agent ·
   building mazons · placing ... died: ... suffocated in a wall`
   (12:06:57, latest.log). Related: the place loop has no give-up against
   non-replaceable obstruction (retried leaves forever — the same episode;
   user-destroyed blocks were re-placed by design, which deepened the trap).

## Direction (decide properly in UNDERSTAND)

- **Timeout shape**: per-task-key counter of CONSECUTIVE releases with the
  SAME failure class (unroutable/pathfail only — NOT missing_material, which
  is restockable and today resolved by the user restocking + re-issuing);
  after N (suggest 3), transition to terminal-failed with reason `unreachable`
  (mirrors missing_material terminal semantics; a user re-issue still mints a
  fresh key via raiseIfAbsent). Candidate alternatives: pre-claim routability
  probe (cheap pathfind before committing the claim), or a user cancel
  command/item. The cooldown-march anti-spin (SEQ-024) must keep working
  during the window.
- **Watchdog**: track position-vs-goal progress over ticks, not just motion.
- **Self-preservation**: never place into the agent's own cell; bail/replan
  on suffocation; pairs with a per-move give-up timeout.
- **Corpus check**: the `unroutable_anchor_first_listed` scenario ground truth
  assumes the task STAYS LISTED (single-decision scoring). Terminal-failing
  after N claims must not change claim-time semantics — verify the scenario
  still passes before/after.

## Plan

- [x] UNDERSTAND: pin each wart's exact repro; pick the timeout design
- [x] No-progress watchdog (position-vs-goal)
- [x] Per-move/place give-up vs non-replaceable obstruction
- [x] Multi-chest `withdrawOnce`
- [x] Self-collision guard + suffocation bail
- [x] Impossible-task terminal (consecutive same-class release counter)
- [x] Gametests for each (production tick loop + shared batch world traps apply)
- [x] Verify: gametests green, python suite green, `unroutable` corpus
      scenario semantics unchanged, mdocs valid

## Pinned design (2026-10-20, UNDERSTAND pass)

- **Timeout shape**: `TaskBoard.release` gains a `failureClass` param (nullable;
  "unroutable" from the two `isStuck` paths in `AgentDecisionLoop`; null from the
  transient comfort releases — those are restockable/may-change, never counted).
  TaskBoard keeps a PERSISTED per-key map `key -> (lastClass, consecutiveCount)`;
  on release with a non-null class: same class → count++, different/reset →
  count=1. Count ≥ 3 (`UNREACHABLE_RELEASE_THRESHOLD`) → terminal FAILED with
  reason `unreachable` + pool anti-pattern, instead of OPEN. Re-issued builds
  mint a fresh key (raiseIfAbsent) so the user recipe keeps working. Counter
  entries reaped when their task leaves the board. Cooldown anti-spin
  (SEQ-024) untouched — it runs inside the window.
- **Watchdog**: replace position-identical counting in `trackStuck` with
  progress-vs-goal: per-agent (targetPos, bestDistSqr, cycles). Strict
  improvement (>0.5 block²) resets; else cycles++. `isStuck` threshold kept at
  40 cycles. Pure static seam `ProgressWatchdog` (public, no level access) so
  gametests drive it synthetically.
- **Multi-chest `withdrawOnce`**: keep scanning containers in the radius cube
  after each visit; stop when every `needed` entry is satisfied (or scan
  ends). "One chest visit per cycle" comment retired — the wart was the scan
  stopping at the first chest, not the per-cycle cadence.
- **Self-preservation**: `agentOccupies(agent, pos)` = entity AABB ∩ block
  cell AABB (feet/head cells included via bounding box, not just blockPosition).
  Both place sites skip occupied cells. Suffocation bail: executor detects
  suffocating eye-in-block state → release + WARN (model/user can recover; the
  guard prevents recurrence). Place give-up: per (task,placement) in-memory
  failed-`setBlock` counter; ≥ `PLACE_GIVE_UP_CYCLES` (20) consecutive →
  terminal FAILED `obstructed:<pos>` (v1 never breaks blocks, so a
  non-replaceable obstruction is genuinely terminal).
- **Corpus check**: `test_corpus.py` asserts single-decision semantics
  (claim-time listing). Terminal-fail fires only at the 3rd stuck release —
  after claim time — so the scenario's ground truth is untouched. Verify by
  running the python suite.

## Progress Log

- [2026-10-20] Initiative created from the live session's wrap-up. User
      direction: "-20,86,0 are mid air so we may need a way to timeout
      impossible tasks, add that to the initiative before wrap up so we can
      tackle it on the next round". Evidence: SEQ-024/026 (warts 2–3 first
      observed), SEQ-034 + today's tap (`run/wire.jsonl`, 43 lines, 5
      decides) and `run/logs/latest.log` (suffocation death 12:06:57;
      fail→restock→reissue→reclaim recovery cycle). User-recovery recipe
      recorded (restock chests ≤16 blocks of anchor → clear the dead task's
      partial structure → re-issue; mints a fresh key, one clean re-claim).
- [2026-10-20] EXECUTE pass complete. Wart 1: `TaskBoard.release` takes a
      failure class; persisted per-key streak (`release_failures` NBT),
      `UNREACHABLE_RELEASE_THRESHOLD = 3` unroutable releases → terminal
      FAILED("unreachable"); transient (null-class) releases reset the
      streak; the loop records the pool anti-pattern on the terminal
      transition. Wart 2: `ProgressWatchdog` (public, level-free seam)
      replaces position-identical counting — strict improvement
      (>0.5 blocks²) or a new goal resets, else cycles++; threshold still
      40 cycles. Wart 3: `withdrawOnce` scans every chest in the station
      cube until `needed` is satisfied. Wart 4: suffocating blocks never
      placed into the agent's own bounding box (`placementEntombsAgent`;
      torches exempt — standing on your torch cell is the normal case);
      eye-in-suffocating-block → bail release; per (task,placement)
      failed/obstructed counter, 20 consecutive cycles → terminal FAILED
      `obstructed:<pos>`. New `AgentsExecutorGameTest` (7 tests + per-class
      `agentsexecutorgametest.empty.nbt` structure) — all four warts
      covered board-level and loop-level.
- [2026-10-20] Test-infrastructure traps found and fixed en route:
      (a) `test_corpus.py` integration hard-coded the 4-scenario corpus;
      the session's `live-2026-10-20.json` tap-import skeleton (ground
      truth pending) broke row counts and the StubGood allow-list — the
      integration drive now stages the canonical four scenarios into a
      temp dir (hermetic against future skeleton imports). (b) Gametest
      world sharing: `agents.executionLoopEnabled = true` in the test
      world makes the PRODUCTION loop decide UNFILTERED every interval
      across batch boundaries — leftover idle agents/tasks from the new
      executor tests hijacked later batches (mcpDeadSidecarFallsBack,
      lightTaskEndToEnd, buildTaskPlacesBlocks all red from my strays;
      clean-HEAD baseline worktree run proved zero failures before my
      changes). Every new test now discards its agent and releases+clears
      its task via a `cleanup()` helper (same pattern as the existing
      `agent.discard()` in AgentsMcpGameTest).
- [2026-10-20] Round-2 traps (all evidenced by red full-suite runs, then
      fixed and re-verified): (a) `runDecisions(filter)` applies its filter
      to EVERY agent in the level, so a decide pass in a test body can hand
      a just-released OPEN task to a sibling test's idle agent — the
      executor tests now claim directly via `board.claim` and drive only
      `runExecutionPass`. (b) `mcpDeadSidecarFallsBack` picked
      `highestPriority` over the GLOBAL open list; scoped to its own key
      (robustness fix, same shared-world rationale). (c) The empty template
      is a 3×3×3 air cube with no isolation — per-tick "cell stays air"
      asserts race neighbor tests' world writes; the own-cell test is now
      atomic (direct guard assertions + one execution pass in one body).
      (d) Watchdog v1 measured distance in INTEGER block cells, whose
      quantization cannot distinguish slow walking (0.05 blocks/tick) from
      standing still — `lightTaskEndToEnd` stalled when its agent tripped
      stuck mid-walk; `ProgressWatchdog` now measures double positions
      against the target center with a 1e-4 epsilon, and the seam test pins
      slow-walk = progress. Baseline attribution method throughout: a
      clean-HEAD git worktree run of the full suite (green) before
      accepting any regression as mine.
- [2026-10-20] Round-3: the new suffocation bail exposed abusive geometry in
      `lightTaskEndToEnd` — its 1-high "room" (stone cap at y=2, agent spawned
      inside at (0,1,0)) kept the agent's head permanently inside stone; the
      old loop ignored that (remote torch placement), the bail correctly
      refuses. A 2-high interior was WRONG too (cap-top dark cells leave the
      arrival radius). Fix: punch a one-cell headroom hole at (0,2,0) —
      original geometry, agent's eye in air, remote placement intact.
- [2026-10-20] VERIFY PASS: full gametest suite green — "All 45 required
      tests passed" (`runGameTestServer`, 2026-10-20 12:59) including the 7
      new executor tests; sidecar Phase-0 suite green (bridge/tap/tap-import/
      spike/corpus 56 checks/probe 52 checks, `corpus tests: PASS`); mdocs
      validate clean (4 pre-existing unrelated graph warnings).
- [2026-10-20T19:00:57.378Z] Marked done via mdocs command

## Artifacts

- Live session tap: `run/wire.jsonl` (2026-10-20); log: `run/logs/latest.log`
- Corpus skeleton from the session (post-import): `sidecar/corpus/live-2026-10-20.json`
