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
| Agents v1 spike: hybrid entity + observation | coordinator→developer | done | 2026-10-14 | committed as 8bfa3b8 |
| Agents v1: decision pool + goals | coordinator→developer | done | 2026-10-14 | committed as 3cc616c |
| Agents v1: LLM client + build knowledge | coordinator→developer | done | 2026-10-15 | committed as aa17226; live-tested vs LM Studio (SEQ-011) |

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

`SEQ-008 | 2026-10-14 | coordinator | RESOLVED | decision-pool-goals slice implemented + reviewed (builder → reviewer → fix cycle). Delivered: pool SavedData (schema v1, LRU cap, tag top-K, trusted≥N), task board (claim/lease/steal, claimant-verified complete/fail, reap 128 resolved), light+shelter detectors, SEQ-007 compression (bottom-up, ~chars/4 estimator, 900-token config default) + Inspector snapshot-to-file under debug/siasworkshop/. Review catch worth remembering: exposure checks must use canSeeSky, NOT getBrightness(SKY)>=15 — at night the latter returns 4 (moonlight) so the check is dead code (now in setup/gametest-notes-1-21-1). Also: lease expiry writes NO pool entry (a timeout is not a plan verdict); GameTest batches isolate time-pinning tests (shared batch world race). 16/16 gametests green. Follow-ups for the LLM initiative: task-execution loop that claims board tasks + cites pool entries via recordSuccess; currentTask() still the "idle" stub.`

`SEQ-009 | 2026-10-14 | coordinator | RESOLVED | Live verification (user, runClient): Inspector snapshot-to-file works end-to-end (run/debug/siasworkshop/agent-observation-1792005907792.txt). Sparse night-village scene = 701 bytes ≈ 176 est tokens, well under the 900 budget (compression pass not even triggered; the earlier 1200-1500 sample was a much busier scene — trimming path itself is gametest-covered at low budget). Station-filtered actions confirmed (no deposit/withdraw without a chest). Nit: zero-count POIs (chest×0 crafting_table×0 furnace×0) waste ~35 tokens — suppress zeros in a future observation pass.`

`SEQ-010 | 2026-10-15 | coordinator | RESOLVED | agents-v1-llm-build-knowledge COMPLETE (26/26 gametests, build green, coordinator-rerun). LLM client (OpenAI-compatible, verified defaults http://localhost:1234/v1 + prism-ml/bonsai-27b; template fallback, bad_endpoint safe), own .schem v2/v3 + .nbt parsers (air skipped — real exports are mostly air; hostile-input hardened), build queue + /siasworkshop build cmd, build-card loader, AgentDecisionLoop (per-tick execution + interval decisions; release-on-stuck/unplaceable; pool citations), TaskBoard.release. War stories → setup/gametest-notes-1-21-1 (world persistence → dedicated run-gametest dir + wipe + seeded serverconfig; onEachTick fastutil trap; barrier frames as spawnable floors; production handlers race driven tests). Builder sub-agent timed out twice on the gametest debug loop — coordinator took over; lesson: give builders a strict "report after N runs, don't spiral" budget and keep the coordinator in the loop for integration-test debugging. All three agents-v1 initiatives now done. Next candidates (user picks): live LLM smoke test (enable llm.enabled against LM Studio), foundation-block GUI for pool/board inspection + blueprint queueing, chronicle/gossip flavor layer per local-llm-feasibility Phase 1, observation zero-POI nit.`

<!-- Agents: append new entries below this line, in SEQ order. -->

`SEQ-016 | 2026-10-16 | coordinator | DECISION | Four user decisions recorded (wiki: setup/agent-ux-feedback-2026-10-16 + initiative): (1) state indicator = nameplate + config-gated DEBUG activity log on disk for coordinator visibility; (2) AUTONOMY NORTH STAR — progressively move decisions to the LLM (highest abstraction, sequential JSON plans the mod queues/executes over long horizons, multi-agent) and actions to the mod; furthest horizon = model progressively redefines action implementations from usage learning (long-term research only); iterate per release, no rush; (3) task board stays, incl. as inter-agent collaboration substrate; (4) Python sidecar approved WITH feature flag default-OFF (no Python dependency for CurseForge installs; dev runs full toolset). Next implementation: agent state visibility (nameplate states, working say(), debug activity log) as its own initiative, then MCP spike.`

`SEQ-015 | 2026-10-16 | coordinator | DISCUSS | User playtest feedback captured (wiki: setup/agent-ux-feedback-2026-10-16). Two threads: (1) agent state is invisible in-game — agreed first step is text: executor-derived state labels (looking/building/waiting-materials/comfort) rendered on the agent + make say() emit a real chat line; simplest first, more complex renderings later. (2) MCP tool catalog refined: composable PATTERN TREENS, not mapped verbs — world = observable state machine, LLM plans high-level via patterns, mod executes leaf effects; MCP sessions become multi-step workflows. Initiative updated (low-level tools recorded rejected; Q3 = pattern-tree design). Next: state-visibility feature, then sidecar spike.`

`SEQ-014 | 2026-10-16 | coordinator | RESOLVED | Cloud-model test round PASSED (OpenRouter, ~deepseek/deepseek-pro-latest; the ~ prefix is a valid OpenRouter id family — required, do not strip). Three findings: (1) trailing slash in llm.endpoint 404s — the client appends /chat/completions and OpenRouter does not normalize double slashes (verified /api/v1//models -> 404); fixed in config + client hardening (a322111). Diagnostic habit worth keeping: 401 vs 404 separated auth from path immediately. (2) Cloud decisions return in SECONDS (deepseek-pro does not burn reasoning tokens like bonsai-27b's 500-900) — prompt redesign validated: exact-key claims (decided via LLM: claim {"task":"comfort:light:380df991-..."}). Pipeline is now provably model-agnostic: same code ran LM Studio local and OpenRouter cloud. (3) Shelter ping-pong: the LLM correctly claims the night COMFORT_SHELTER task, but v1 has no shelter executor, so the claim is released and re-claimed every cycle — filtered COMFORT_SHELTER from the LLM-visible open list (same rule the fallback claimer already had) (3414e67). User has extensive gameplay feedback queued; next session: collect + prioritize. MCP initiative requirements input largely fulfilled by this round.`

`SEQ-012 | 2026-10-15 | coordinator | DISCUSS | User idea parked for later: would a dedicated MCP layer give model-stable agent decisions? Recorded as initiative mcp-model-behavior-research--2026-10-15 (status proposed, research questions + 4 candidate approaches: in-mod normalization / MCP sidecar / OpenAI shim proxy / per-model profiles). Context: live test showed per-model variance absorbed ad hoc by LlmClient (token budgets, fence parsing, prompt-constrained JSON). Explore after the cloud-model test round.`

`SEQ-013 | 2026-10-15 | coordinator | DECISION | MCP initiative direction settled by user: normalization in the MCP SIDECAR (shim rejected); mod exposes its action/pattern catalog as MCP tools — the tool catalog is the stable model<->mod interface and grows with mod capabilities (new capability = new tool, no model-integration touch); per-model optimization profiles allowed for most-used models, hosted in the sidecar. Research questions refocused on tool-catalog evolution, model-behavior CI, sidecar ops. Initiative priority low->medium. First concrete step after the cloud round: minimal sidecar spike exposing the 3 decision tools to one local + one cloud model, measuring latency and malformed-decision rate vs v1.`

`SEQ-011 | 2026-10-15 | coordinator | RESOLVED | Live smoke tests 1-3 ALL PASSED against real LM Studio. T1 (light task via template fallback) and T2 (build queue) passed earlier in the day. T3 (LLM decision path) exposed four real bugs, all fixed and committed: (1) LM Studio (llama.cpp) rejects response_format "json_object" (HTTP 400; only json_schema/text allowed), surfacing client-side as ClosedChannelException — field dropped, system prompt constrains JSON instead (f974a47); (2) dev launchers set -Djava.net.preferIPv6Addresses=system, so localhost resolves ::1-first and java.net.http fails fast with ClosedChannelException instead of falling back to IPv4, while LM Studio binds 127.0.0.1 only — endpoint default now 127.0.0.1; reproduced standalone with the same flag (c9ebf8d); (3) reasoning models burn the whole maxTokens budget on hidden thinking (bonsai-27b: 511/511 completion tokens reasoning, empty content, finish_reason=length) — maxTokens default 512->1024, range 16~2048, timeoutSeconds default 30->120, bad_json WARN now includes a 200-char preview of the raw reply (212f147); (4) parseJsonObject required a leading '{' while bonsai answers in json code fences — parser now extracts the first {...} span. Observability added: INFO on LLM decisions + task claims, WARN with reason on fallback, /siasworkshop board debug command. HARD-WON TRAPS: (a) quit-to-menu != client restart — classes and ModConfigSpec bake at JVM start; re-entering the world keeps old code/spec (cost us one full test round); (b) config values outside the RUNNING spec's range are silently "corrected" at load — hand-edits beyond the running max get reverted, always re-read the file after load; (c) a commit once claimed a change it didn't contain — verified via javap on build/classes constants; do that when config behaves impossibly. T3 verdict: "decided via LLM: light {}" logged twice (13:43:54, 13:44:21) even on the stale 512 spec — intermittent, since reasoning sometimes exceeds 512 (bad_json with empty preview). Reliability needs one true client restart with the 212f147 classes. Model notes: bonsai-27b reasons ~450-900 tokens per decision (60-120s on a loaded M3 Pro); a smaller non-reasoning model is the likely production choice for snappy decisions (config-only switch of llm.model). One isolated http_400 from LM Studio mid-session, unrepeated. Wire-compat writeup: reference/local-llm-feasibility.`

## Referenced By

*Auto-generated by mdocs*

- agent-shared-ledger-setup--2026-10-14
