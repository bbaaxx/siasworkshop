---
id: "mcp-pattern-tree-design"
title: "MCP pattern-tree design — descriptor format, session model, reference walk"
category: "reference"
created: "2026-10-16"
updated: "2026-10-16"
related_initiatives: ["mcp-model-behavior-research--2026-10-15"]
tags: ["mcp","llm","agents","architecture","design","pattern-tree"]
lifecycle: "evolving"
lifecycle: "evolving"
knowledge_type: "design"
confidence: "medium"
source_initiatives: ["mcp-model-behavior-research--2026-10-15"]
---

Design for the MCP sidecar workstream (initiative `mcp-model-behavior-research--2026-10-15`),
step "pattern-tree descriptor design". Grounded in the v1 code (`content/llm/*`,
`content/agents/*`) and the user direction in `setup/agent-ux-feedback-2026-10-16`.
Status: design ready to spike — nothing here is implemented yet.

## What v1 does today (the baseline this must beat)

- `LlmClient`: one-shot OpenAI-compatible chat completion; all failures collapse into
  `LlmResult` (usable/failure) so the loop can fall back to templates. Offline-first.
- `LlmPrompts`: one system line + user message = observation snapshot + open board
  tasks + exactly 3 decision actions (`claim`/`rest`/`say`) + top-K pool entries +
  build cards (retrieve-and-inject, only when a build task is open).
- `LlmActions`: 15-action validated space, `validate()` is the safety boundary;
  executors (`AgentDecisionLoop`) run hands automatically after a claim.
- Task board = the work substrate: keyed singleton tasks, claim/lease/steal, pool
  tags/plans per type. Build cards + `BlueprintLibrary` are the knowledge layer.

The MCP path replaces the *decision call* with a *session*, and grows the 3-action
decision surface into a leveled pattern catalog. Execution stays in the mod.

## Decisions

**D1 — Roles.** The mod is the MCP **server** and owns the tool registry — the
catalog is the stable model↔mod interface (user requirement, SEQ-013). The sidecar
is the MCP **client** plus the model host: it runs the conversation loop, translates
model output into tool calls against the mod, and absorbs all model variance
(prompt templates, budgets, tool-call format conversion, reasoning-model quirks,
per-model profiles).

**D2 — Transport: sidecar subprocess, MCP over its stdio.** Flag on → the mod
spawns `python sidecar.py` via ProcessBuilder and speaks MCP over the child's pipes,
with the *sidecar in the client role*: sidecar writes JSON-RPC requests to its own
stdout (mod reads), mod writes responses to sidecar's stdin (sidecar reads). This
inverts the usual spawned-server expectation but is pipe-symmetric, needs no ports,
and binds the sidecar lifecycle to the server process. Dev/spike mode: the sidecar
runs standalone with a recorded catalog (parity testing without the game).
Rejected: loopback HTTP (ports, prompts, dev reload friction) — kept as escape hatch.

**D3 — Four tool kinds**, all mod-owned, all validated in-mod:

- `observe.*` — read-only state-machine reads (`observe.self/world/board/task/...`).
  Params carry budget caps (900-token default carried over from v1). Return the same
  compact snapshot shapes `AgentObservation` already produces.
- Category / pattern tools — plan-level operations. Invoking one runs mod-side logic
  and returns a **pattern card** (D5). These are where "LLM intelligence plans" lives.
- Effect tools — leaves that change world state (place/build/farm/mine...). Strict
  input schema; the mod validates like `LlmActions.validate` today, executes via the
  board + executors, and may reject with a structured error the model can see and
  correct. Guardrails (guard radius, block whitelist, stuck-release) are unchanged.

**D4 — Descriptor format.** Catalog JSON, versioned, served by the mod
(`GET` equivalent = one MCP resource/read or the root list). Tool ids are stable dot
paths internally; **wire names replace dots with underscores** — the MCP tool-name
schema is `^[a-zA-Z0-9_-]{1,64}$` and forbids dots (`observe.self` → `observe_self`;
S0 finding). `inputs` is a JSON-Schema subset (the same subset `ArgShape` hand-rolls
today, so the validator carries over). Effects declare `convergence` — how to observe
the change completing (board task status).

```json
{
  "catalogVersion": 1,
  "category": {
    "id": "building",
    "title": "Building",
    "summary": "Turn a build request into placed blocks: site, design, materials, blueprint, apply."
  },
  "tools": [
    {
      "id": "building.think_through_request",
      "kind": "pattern",
      "title": "Think through a build request",
      "description": "Turns a free-form build wish into a concrete design intent and opens the next steps.",
      "inputs": { "type": "object",
        "properties": {
          "request": { "type": "string" },
          "near":    { "$ref": "#types/pos" } },
        "required": ["request"] },
      "children": ["building.analyze_terrain", "building.design"],
      "returns": "designIntent"
    },
    {
      "id": "building.apply_blueprint",
      "kind": "effect",
      "title": "Apply a blueprint at an anchor",
      "description": "Claims/enqueues the build; the mod places blocks over time.",
      "inputs": { "type": "object",
        "properties": {
          "blueprint": { "type": "string" },
          "anchor":    { "$ref": "#types/pos" } },
        "required": ["blueprint", "anchor"] },
      "convergence": "observe.task(taskId) until DONE/FAILED"
    }
  ]
}
```

`#types/pos` = `{x,y,z}` ints. Kinds: `category` | `pattern` | `effect`
(+ `observe` for the read-only tools, same shape, no side effects).

**D5 — Expansion = pattern cards in results, not tools/list_changed.** A pattern
result is `{"result": {...}, "children": [<descriptors>]}` — child tools enter the
model's context only when the parent opens them. This gives retrieve-and-inject per
situation for free, works on every model wire (the sidecar renders cards into
whatever the model supports), and lets the mod prune children situationally (a card
only offers children valid *right now* — e.g. no `apply_blueprint` without a
compiled blueprint). Catalog growth never inflates the root prompt.

**D6 — Session-state split.** Conversation + plan-scratch state lives in the
sidecar; world truth lives in the mod. The sidecar re-observes rather than caching
world state. Long-horizon execution rides the task board (claims + leases, exactly
as today), which also keeps multi-agent coordination underneath pattern sessions.
Convergence = the model polls `observe.task` / `observe.board` — the state machine
"request change, watch it converge" loop from the user's framing. The north-star
sequential-plan queue (LLM emits a plan, mod executes over a long horizon) lives
in-mod like `BuildQueue`; its schema is sketched below but implementation is
explicitly out of spike scope.

**D7 — Safety boundary unchanged.** Raw model output never touches the world: every
effect call passes schema validation in the mod, guardrails stay in the executors,
unknown tools / malformed args get a structured reject the model can recover from.
The template fallback fires only when the MCP *path* is down (sidecar dead, spawn
failed), preserving offline-first.

**D8 — Model variance absorbed in the sidecar, prompt-mode first-class.**
Per-model profiles keyed by id/family: prompt template variant, token budget,
tool-rendering mode, fence tolerance, reasoning detection. Critical evidence
(`reference/local-llm-feasibility`): local models handle *constrained schemas* fine
but *open-ended function calling* poorly — so the sidecar must render the catalog as
prompt-constrained JSON reliably, with native tool-calling an optimization for
profiles that support it, not the only path.

**D9 — Feature flag from day one.** `agents.mcp.enabled` default false + sidecar
command config. Off → v1 `LlmClient` path untouched. On + sidecar up → MCP session
path. On + sidecar dead → WARN + template fallback. CurseForge installs never spawn
Python (flag off = no spawn attempt).

**D10 — Prompt economy rules.** Session root = categories + `observe.*` only.
Cards carry children. Observation tools take budget params. Pool/build-card
retrieval stays mod-side and rides inside cards. Target: v1-comparable context
sizes per step (~1–2k tokens) even as the catalog grows.

## Reference walk — "build a small stone house by the station"

Traced through the design; every hop names the v1 machinery it reuses.

1. Entry: `/siasworkshop build stone_house` (or future direct chat ask) raises a
   BUILD board task — `BuildCommand`, unchanged.
2. Agent session opens; `observe.board` shows the open task + `observe.self/world`
   snapshot (`AgentObservation`, unchanged, budgeted).
3. Model enters `building` category → card for `building.think_through_request`.
4. `building.think_through_request(request)` → mod returns a normalized design
   intent (size, style, rough palette) + children `analyze_terrain`, `design`.
5. `building.analyze_terrain(near=station)` → mod scans for candidate sites
   (flat, in guard radius of a station chest — the station/POI scan executors
   already do this shape of work) → returns ranked sites.
6. Model picks a site; `building.design(site, intent)` → mod retrieves build cards
   (`BuildCards.retrieve`, today's retrieve-and-inject, now a pattern result) →
   returns design options with palettes and step summaries.
7. Model selects one; `building.select_materials(design)` → mod computes the palette
   bill against the nearby chest (`neededMaterials` logic, today inside the
   executor) → returns have/need/missing.
8. Missing materials? `building.apply_blueprint` stays out of the card (situational
   pruning, D5); the model may rest/say/adjust — or the mod-side design step
   already picked an affordable card.
9. `building.create_blueprint(design, anchor)` → mod compiles a `Blueprint` into
   `BlueprintLibrary` (`.schem`/`.nbt` parsers exist) → returns blueprint id.
10. `building.apply_blueprint(blueprint, anchor)` → effect validated, claims/enqueues
    the board task; the v1 build executor places blocks over ticks.
11. Model polls `observe.task` (or moves on to another task and lets the board +
    lease sweep handle it) → sees DONE; pool citation happens as today.
12. Session records the plan; pool entry written via the board's resolution hook —
    v1 citation machinery unchanged.

## S0 spike results (2026-10-16)

S0 ran the 3-tool parity surface (`claim`/`rest`/`say` + `observe.*`) against one
local and one cloud model, A/B against the v1 prompt — see ledger SEQ-020 and
`sidecar/` (replay self-test 20/20 asserted cases).

- **Cloud (deepseek-pro, n=3/mode): 9/9 valid, 0 malformed.** Latency v1 1.1s /
  mcp-prompt 1.2s / mcp-native 1.4s; completion tokens 21/48/68. MCP surfaces cost
  +0.1–0.3s and +27–47 tokens per decision — parity at negligible cost.
- **Local reasoning model (bonsai-27b, n=1/mode): v1 prompt failed twice**
  (`empty_content:length` at 1024 tokens; then 120s timeout — the SEQ-011 budget
  burnout) **while both MCP modes succeeded** (~39s, ~350–390 tokens, exact-key
  claims). The structured tool contract contains reasoning-model deliberation where
  the freeform decision prompt does not. Small n; the mechanism (constrained output
  shortens deliberation) is consistent — re-measure with the scenario corpus (Q4).
- **Native-mode models call whatever tools they are given.** With `observe.*`
  offered alongside the decision set, deepseek re-observed instead of deciding 2/3
  runs. Sensible multi-step instinct (supports D5/D6); S0 restricts the native leg to
  the decision set for parity, and D5's situational card gating becomes a hard
  requirement, not an optimization.
- **LM Studio (llama.cpp) accepted native tool-calling for bonsai-27b** — local
  native profiles are viable; the prompt-mode default stays until the corpus (Q4)
  says otherwise.
- The v1 baseline's local failure also reframes Q2: the win of tool-calling is not
  just fewer malformed replies, it is **containing deliberation budget** on reasoning
  models.

## Spike plan (next, per initiative)

- **S0 — plumbing + parity.** Sidecar speaks MCP over stdio to a mock mod catalog
  exposing exactly the v1 3 decision tools (`claim`/`rest`/`say`) + `observe.*`; run
  one local (LM Studio) and one cloud (OpenRouter) model. Measure: decision latency,
  malformed-decision rate (unknown tool / bad args ≡ v1 `bad_json`), fallback rate,
  tokens/decision — vs the recorded v1 numbers. Prompt-mode rendering mandatory,
  native tool-calling optional profile flag.
- **S1 — in-mod, behind the flag.** Real mod spawns the sidecar; decision path
  routes through it; template fallback on sidecar death. Gametest: catalog serves,
  effect validation rejects malformed, fallback engages.
- **S2 — first real pattern.** Implement the building walk's first three patterns
  against the real build machinery; same scenario as a v1 build task; compare.

## Open questions (for the spike, not blockers)

1. Session granularity: one MCP session per agent (persistent, re-entrant) vs per
   decision. Leaning per-agent persistent; S0 measures cheap sessions first.
2. Effect-reject storms: cap retries per session/step to avoid model-argument loops.
3. Plan-queue schema (`{"steps":[{"tool":..., "args":...}]}` sequential JSON) — design
   when the north-star mechanism is scheduled; the descriptor format already carries
   everything a step needs.
4. Root catalog UX: are category tools needed, or does a flat root list of entry
   patterns suffice? (Cards make categories cheap; decide on model behavior evidence.)
5. Later optimization: tools/list_changed for native-MCP models instead of cards.
