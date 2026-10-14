---
id: "agent-embodiment-observation--2026-10-13"
title: "Agent embodiment + world-state observation design"
status: "done"
created: "2026-10-14"
updated: "2026-10-14"
owner: ""
tags: ["design","llm","agents","entities","observation"]
related_wiki: ["reference/agent-embodiment-observation"]
priority: "medium"
---

## Objective
Design how LLM agents are embodied in-game (fake ServerPlayer vs custom player-like entity, referencing Modular Golems) and the text observation schema that gives agents full visibility of world state + available actions. Output: wiki reference page + recommendation.

## Plan
- [ ] Survey embodiment options and prior art (Carpet fake players, NeoForge FakePlayer, Modular Golems, Mineflayer)
- [ ] Define observation schema (world state + action space) with token budgets
- [ ] Write mdocs/wiki/reference/agent-embodiment-observation.md, validate, link, recommend

## Progress Log
- [2026-10-14T05:16:57.637Z] Created initiative via mdocs command
- Design brief written to mdocs/wiki/reference/agent-embodiment-observation.md (coordinator knowledge; no web access, caveat on page). Scope grew twice during EXECUTE per user: added shared decision pool (collective learning, Voyager-style, SavedData-backed) and goal families (player comfort via deterministic deficit detectors + build queue), then build-knowledge sources (own templates, BYO .schem/.litematic/.nbt imports, dev-time distilled build cards; retrieve-and-inject, never live browsing; write-own-parsers, WorldEdit GPL / Litematica LGPL). Recommendation: Option C hybrid entity (custom Mob + FakePlayer delegate), observation <=600-900 tokens, ~12 validated actions, tag-based pool retrieval v1.
- Design brief written to mdocs/wiki/reference/agent-embodiment-observation.md. Scope grew twice during EXECUTE per user: shared decision pool (collective learning, SavedData-backed) + goal families (player comfort deficit detectors + build queue), then build-knowledge sources (own templates, BYO .schem/.litematic/.nbt, dev-time build cards, retrieve-and-inject never browse, write-own-parsers). Recommendation: Option C hybrid entity, <=600-900 token observations, ~12 validated actions, tag-based pool retrieval v1.
- [2026-10-14T05:27:54.392Z] Marked done via mdocs command

## Artifacts
