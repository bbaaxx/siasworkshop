---
id: "agent-ux-feedback-2026-10-16"
title: "Playtest feedback — agent state visibility + MCP surface shape"
category: "setup"
created: "2026-10-16"
updated: "2026-10-16"
related_initiatives: ["mcp-model-behavior-research--2026-10-15"]
tags: ["agents","ux","feedback","mcp","design"]
lifecycle: "evolving"
knowledge_type: "feedback"
confidence: "high"
source_initiatives: []
---

# Playtest feedback — agent state visibility + MCP surface shape

User feedback after the local + cloud model test rounds (2026-10-15/16).
Raw observations, grouped; prioritization happens in the ledger.

## 1. Agent state is invisible — add an in-game indicator

- The agent on its own "feels naturalish", clearly better with the fast cloud
  model. Long waits are acceptable **when processing is visibly happening** —
  the problem is not latency, it is opacity.
- Desired: an observable state on the agent. Candidate renderings: pose change,
  color/skin change, or text. States the user named: *looking for what to do*,
  *building*, *waiting for materials*, *doing comfort tasks*.
- User's constraint: **simplest first** — if text is the simple path, do text now
  and grow complexity only as needed.
- Agreed first step (text): map the loop/executor states onto the agent's
  existing task label so it renders in-game, and make the `say` action actually
  emit a chat line (today `say` sets a label and produces nothing visible).
- Note the state set is executor-derived, not task-type-derived: "waiting for
  materials" comes from the build executor's withdraw path, "thinking/deciding"
  from the LLM in-flight window, etc.

## 2. MCP surface = composable pattern trees, not mapped actions

- Agreement that more surface is the right goal — most models already "know
  Minecraft"; they decide well when given **visibility of world state** and a
  **clear path to tool access**.
- Sharpening of the tool-catalog direction: tools must NOT be low-level mapped
  verbs ("open chest", "place block T at X,Y"). The catalog is **leveled and
  composable, tree-structured patterns**. Example, category *building*:
  "think through a build request" → "analyze terrain / find where" →
  "design a build" → "select materials" → "create blueprint" → "apply blueprint".
- The user's framing: the Minecraft world is a **state machine** — observe state
  via the MCP, request state changes via patterns, observe the state machine
  converge, plan at a high level before issuing state-change requests (build,
  change, farm, mine...). Use LLM intelligence for planning; the mod executes
  the leaves (effects).
- Implication for the initiative: MCP sessions become **multi-step workflows**
  (a pattern expands into sub-tools across turns), not one-shot decision calls.
  Tool descriptors need hierarchy + sub-tool discovery; prompts stay small by
  retrieve-and-inject per situation.

## What was NOT said (assumptions to validate)

- Whether state-indicator text should be nameplate-only, chat-only, or both.
- How much per-turn autonomy: one tool call per decision cycle vs. an entire
  pattern walk inside one session.
- Whether the existing task board remains the planning substrate beneath
  patterns, or gets subsumed by pattern workflows.
