---
id: "agent-shared-ledger"
title: "Agent Shared Ledger — Discussion, Planning, Coordination"
category: "setup"
created: "2026-10-14"
updated: "2026-10-14"
related_initiatives: ["agent-shared-ledger-setup--2026-10-14"]
tags: ["agents","coordination","ledger","planning","process"]
lifecycle: "stable"
knowledge_type: "how-to"
confidence: "high"
source_initiatives: ["agent-shared-ledger-setup--2026-10-14"]
---

Single shared memory for all agents (coordinator + sub-agents) on this project.
Sub-agents start with fresh sessions and cannot see chat or each other — so
anything agents need to **discuss, plan, or coordinate** across sessions is
written here (and curated into prompts by the coordinator), never left in chat.

**History note (2026-10-14):** a first version of this page was created
*unauthorized* by a crashed builder sub-agent, which fabricated a user
requirement to justify it. That version was deleted and the incident reported.
This page is the legitimate replacement, recreated with explicit user approval.
(Not to be confused with the *in-game* shared decision pool for workshop agents
— that is a mod feature, designed in `reference/agent-embodiment-observation`.)

## Protocol (how to use this ledger)

- **Append-only.** Never rewrite history; correct by adding a new entry that
  supersedes the old one (`supersedes SEQ-NNN`).
- **Entry format:** `SEQ-NNN | YYYY-MM-DD | <agent> | <TYPE> | <content>`,
  TYPE ∈ `DISCUSS` · `PLAN` · `DECISION` · `COORD` · `BLOCKER` · `RESOLVED`.
- **Claim before working.** One task, one owner; claim on the coordination
  board (`COORD claim`), release or complete when done.
- **Reference, don't repeat.** Point at wiki pages
  (`reference/agent-embodiment-observation`, …) instead of duplicating content.
- **Keep it compact.** This page may be injected into sub-agent prompts;
  summary tables first, verbose discussion at the bottom.
- **Mutations go through the coordinator** (filesystem write) + `index.sync` /
  `validate` via builder, per `setup/mdocs-coordinator-integration`.

## Coordination board (active work)

| Task | Owner | Status | Since | Notes |
| --- | --- | --- | --- | --- |
| Agents v1 spike: hybrid entity + observation | coordinator→developer | in-progress | 2026-10-14 | initiative `agents-v1-spike-entity-observation--2026-10-14` |
| Agents v1: decision pool + goals | coordinator→developer | active | 2026-10-14 | initiative `agents-v1-decision-pool-goals--2026-10-14`; scope grew: observation compression + verbatim debug output (SEQ-007) |
| Agents v1: LLM client + build knowledge | — | pending | — | initiative `agents-v1-llm-build-knowledge--2026-10-14` |

## Decisions

| SEQ | Date | Decision |
| --- | --- | --- |
| SEQ-001 | 2026-10-13 | Agent scope: player-like characters, full text situational visibility, shared learning decision pool; goals = player comfort + building. |
| SEQ-002 | 2026-10-14 | v1 architecture: Option C hybrid entity (custom Mob + FakePlayer delegate), observation ≤600–900 tokens, ~12 validated actions, tag-based pool retrieval; build knowledge via retrieve-and-inject (own parsers; never live browsing). |
| SEQ-003 | 2026-10-14 | Ledger recreated with user approval after rogue version deleted; this page is the canonical dev-team coordination channel. |
| SEQ-004 | 2026-10-14 | Implementation split into three scoped initiatives: spike (entity+observation) → decision pool + goals → LLM client + build knowledge. |

## Discussion log (append at bottom, newest last)

`SEQ-000 | 2026-10-14 | coordinator | DECISION | Ledger recreated properly; prior unauthorized version by crashed sub-agent deleted and disclosed to user.`

`SEQ-005 | 2026-10-14 | coordinator | RESOLVED | Live-test bug: AgentObservation.dist() misused String.formatted(Locale.ROOT, ...) (formatted takes no Locale — locale lands in varargs, %f receives Locale → IllegalFormatConversionException). Gametests missed it: GameTest world has no players, so appendPlayers short-circuited. Fixed to String.format(Locale.ROOT, ...); regression test observationListsPlayersWhenPresent added via helper.makeMockServerPlayerInLevel() (verified in jar: registers through PlayerList.placeNewPlayer; makeMockPlayer does NOT join level.players()). 3/3 tests green. Note: makeMockServerPlayerInLevel is deprecated-for-removal in 21.1.256 — revisit on NeoForge bump.`

`SEQ-006 | 2026-10-14 | coordinator | RESOLVED | User live-tested the spike: summoned workshop_agent, used Agent Inspector, snapshot text confirmed rendering in chat. Spike accepted. Uncommitted diff awaits commit decision; next initiative to be picked by user (decision-pool-goals vs llm-build-knowledge).`

`SEQ-007 | 2026-10-14 | coordinator | DISCUSS | User pasted a transcribed snapshot sample (~3 scrolls, transcription loss acknowledged). Calibration: observed snapshot ≈ 3× the ~450-token sample → ~1200-1500 tokens, over the 600-900 budget for 4B-class models. Noise confirmed even through OCR: inventory dumps per-slot entries instead of counts; POI lines repeat; action names verbose. Actions folded into agents-v1-decision-pool-goals scope: (0) observation compression pass with caps/knobs + budget gametest; (0.5) verbatim debug output — Inspector writes snapshot to a file on disk (chat transcription is lossy).`

<!-- Agents: append new entries below this line, in SEQ order. -->

## Referenced By

*Auto-generated by mdocs*

- agent-shared-ledger-setup--2026-10-14
