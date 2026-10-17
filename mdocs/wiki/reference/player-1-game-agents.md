---
id: "player-1-game-agents"
title: "Player-1: Game-Control Post-Training via Synthetic Experience (paper digest)"
category: "reference"
created: "2026-10-17"
updated: "2026-10-17"
related_initiatives: ["research-digest-player-1-synthetic-game-agents--2026-10-17", "research-digest-jev-jeva-decision-layer--2026-10-17"]
tags: ["research","llm","game-agents","rl","paper-digest","control","verifiable-rewards"]
lifecycle: "evolving"
knowledge_type: "reference"
confidence: "high"
source_initiatives: ["research-digest-player-1-synthetic-game-agents--2026-10-17"]
---

# Player-1 digest

Paper: Ouyang et al. (NUS / Tencent ARC Lab / NTU), "Player-1: Learning Game
Control Beyond Demonstrations through Synthetic Experience,"
[arXiv 2610.20584](https://arxiv.org/abs/2610.20584) v1, 2026-10-14.
Read in full (HTML) 2026-10-17 as digest source #1 of the research pause.

## What it is

A post-training framework that takes computer-use agent (CUA) models
(screenshot in, keyboard/mouse out) and turns them into game controllers via
**SFT on curated gameplay demonstrations → PPO RL in synthetic environments**
("Player-1-Gym", 16 self-built 2D/3D browser environments with
programmatically verifiable rewards). Pure low-level **control** learning —
no tool catalogs, no language interface to the game, no MCP. The model sees
pixels and emits raw inputs.

Headline results (MineDojo-300, Minecraft 1.16.5 via MineStudio):

| Model | Base | SFT | +RL (200 steps) |
| --- | --- | --- | --- |
| Player-1-9B | 9.21% | 10.54% | **38.33%** |
| Player-1-35B-A3B | 7.79% | 23.38% | **41.04%** |
| Best external baseline (GameWAM) | | | 12.63% |

Gains transfer off-suite: ViZDoom-10 scaled return 0.4361→0.6922 (9B),
GameWorld 170-task progress 31.43%→34.45%, plus a qualitative Watch Dogs 2
demo (unseen commercial game).

## Pipeline details that matter

**1. Extended action space** (shared by SFT, RL, eval):
- Relative pointer motion (Δx,Δy normalized ±1000) alongside absolute CUA
  coordinates — needed for camera control without a visible cursor.
- Sustained holds (key/button held across a 200 ms control step; repeating
  holds = continued movement).
- **Composite actions**: ≥2 compatible inputs concurrent in one step
  (W-hold + Space-tap = running jump; aim-while-firing).
- 200 ms fixed decision grid; source recordings re-aligned onto it.

**2. SFT data curation (6,044 trajectories / 295,097 turns / ~16.4 sampled
control-hours / 172 games):**
- Heterogeneous public + in-house recordings; temporal alignment onto the
  200 ms grid; per-source mouse-gain calibration to pixel-equivalent
  displacements (least-squares / Huber fits from GUI pointer sessions;
  out-of-range projections rejected, not clipped).
- A VLM (Gemini 3.8 Flash) annotates task instructions + per-turn visual
  reasoning; reasoning conditioned on instruction, current+past frames, and
  the action target.
- **Action-balanced sampling**: 9 overlapping action pools (keyboard, mouse
  move, click, hold, composites…) weighted 1.0, "other" 0.1 — because e.g.
  left-clicks are <1% of recorded inputs but critical. Rare-but-important
  actions must be oversampled explicitly.

**3. Player-1-Gym RL design:**
- 16 environments organized by recurring **control demands** (aiming,
  combat, driving, navigation, UI), implemented on a shared Three.js runtime;
  screenshot observations only; privileged state used for reward, never
  shown to the policy.
- Rewards = **cumulative game-score increments** (dense, step-level,
  verifiable, no learned judge). Penalties: invalid action = 1.0 (executes
  wait fallback); reasoning-length penalty beyond 64 tokens (0.005/token,
  capped 1.0). No terminal-failure penalty, no reward rescaling, γ=λ=1
  (return-to-go over full episodes).
- **4-axis randomization**: appearance, camera, scene layout, and
  control-response gains (camera sensitivity etc. sampled per episode).
- Multi-env PPO with **per-environment advantage normalization** (envs have
  wildly different reward scales).
- Training cost: SFT 32×H200 ≈ 24 h; RL 200 steps 64×H200 ≈ 36 h; rollout
  fleet 512 concurrent browser envs (32 Playwright workers × 16 contexts).

**4. Evaluation methodology:**
- Fixed trial budgets (8/task MineDojo, 50/map ViZDoom, 3/task GameWorld),
  95% task-cluster bootstrap CIs, coverage metrics (tasks with ≥1/2/4 of 8
  successes). Full eval ≈ 3.14 M decision turns ≈ $110k at GPT-6-Astra rates.

## Findings with direct weight for our stack

1. **SFT (or profile) initialization dominates the RL loop.** Base→RL on 9B
   crawls 12.67%→16.67% MineDojo success in 100 steps; SFT→RL jumps
   17.67%→51.67% — a 35 pp gap at equal compute. Translation: an LLM given
   only interaction feedback barely improves; the grounded starting point
   (for us: sidecar per-model profiles + prompt templates + annotated
   examples) is where the value is. If we ever add usage-derived learning
   (the explicit long-term north star), invest in the initialization/profile
   layer first, not the feedback loop.
2. **Composite actions emerge when the task demands them** — share of turns
   with composite actions climbs 64.7%→93.2% over 100 RL steps with no
   explicit composition reward. We get this by construction: the mod
   executes compound leaf effects, and the model plans in composed pattern
   trees. Confirmation that composition belongs at the *semantics* level,
   not the input level, for our architecture.
3. **Verifiable-reward recipe = our scenario corpus (Q4) template.** Their
   reward design maps almost 1:1 onto corpus assertions: dense score
   increments over privileged world state; assertions computed by the
   harness, never exposed to the model; invalid calls penalized with a
   wait-fallback (our structured rejects + template fallback path);
   reasoning-length penalty (our bonsai-27b budget burnouts — capping or
   penalizing reasoning tokens is externally validated here). Adopt for
   corpus Q4: per-step assertion functions over world state, malformed-call
   rate as a tracked metric, no terminal penalties.
4. **Randomization axes = corpus diversity axes.** Appearance/camera/scene/
   control randomization ⇒ our corpus should vary world setup, observation
   rendering, and dynamics parameters (chest distance, station radius,
   available materials, anchor routability) so assertions test *invariance*,
   not one memorized layout. Directly targets our three known failure
   classes (unroutable anchor, wrong/missing material, station-radius
   ignorance).
5. **History discipline**: 2 recent visual-action turns + 2 older
   action-only turns, reasoning stripped from history — compact context for
   multi-step sessions. Matches our prompt-economy concern in the
   pattern-tree design (retrieve-and-inject, small catalogs).
6. **Metrics**: success rate alone is anecdotal; report coverage over
   repeated trials with CIs. Adopt for corpus regression runs (A/B sidecar
   profiles).

## What Player-1 does NOT do (boundary of applicability)

- **No semantic interface.** The model never sees or requests structured
  state; everything goes through pixels. Our whole bet (mod owns the
  registry, MCP tool catalog is the stable interface) is the other school —
  the paper itself cites the split: "Jev demonstrates low-latency game
  control through a text-only interface, relying on structured game-state
  representations rather than screenshot observations."
- **Not reproducible at our scale.** 64 H200s × 36 h per RL run is a lab
  budget; nothing here is borrowable as code or as a dev loop for a mod
  side project.
- **No relevance to our executor.** Our leaf effects are executed by mod
  code (pathfinding, chest withdrawal, block placement). A Player-1-style
  pixel policy would only matter if we drove fake players through raw
  input — strictly worse than semantic control for our use cases. Verdict:
  stay semantic; treat Player-1 as evaluation-methodology and
  reward-design inspiration, not architecture.

## Where this leaves the MCP-server half of the digest

The paper contains zero MCP/structured-interface content, which sharpens
the second digest's questions rather than answering them:

- An existing Minecraft MCP server is a **structured observation/action
  channel** — the same school as our sidecar. Digest it to compare: tool
  catalog shape (flat vs our pattern tree), transport, auth model,
  multi-client attach (our hot-plug P2), and session state handling.
- Player-1's MineDojo-300 harness (MineStudio, 1.16.5) is eval-only tooling
  — no overlap with our NeoForge 1.21.1 runtime.
- The agent loop cadence Player-1 uses (200 ms fixed decisions, 50-turn
  episode caps) is a useful reference point when comparing latency budgets
  of MCP decision loops (our cloud decisions run 1–2 s; local 30–127 s).

## Connection to the JEVA paper (recorded, out of scope here)

The user's first paste was [arXiv 2610.18773](https://arxiv.org/abs/2610.18773)
(Jeva, TypeSafe-style decision-native architecture: null-aware,
permutation-invariant, option-scalable, cacheable choice/score outputs).
Not coincidentally related: Player-1's related work cites TypeSafe's "Jev"
as the structured-interface, low-latency control sibling. The two papers are
complementary layers — **Jeva = the decision layer** (how to pick among
candidate options), **Player-1 = the control layer** (how to act through an
interface). JEVA remains parked as a candidate future digest against our
task-board decision layer (null = no routable task vs uncertainty;
permutation-invariance vs board listing order — the S1 live test saw the
model latch onto the first-listed unroutable task).
