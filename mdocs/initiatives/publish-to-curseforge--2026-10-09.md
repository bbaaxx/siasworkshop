---
id: "publish-to-curseforge"
title: "Publish v1.0.0 to CurseForge"
status: "active"
created: "2026-10-09"
updated: "2026-10-09"
owner: ""
tags: ["release","publishing","curseforge"]
related_wiki: ["setup/curseforge-publishing"]
priority: "medium"
---

## Objective
Get Sia's Workshop v1.0.0 published on CurseForge for MC 1.21.1 (NeoForge 21.1.x) and 1.20.1 (NeoForge 47.1.x): investigate the exact author setup, project creation, file upload, and review steps, then execute them (manual upload first; CI automation optional follow-up).

## Plan
- [x] Investigate CurseForge author account + project creation requirements (current docs) — wiki setup/curseforge-publishing
- [x] Investigate file upload specifics: game versions/loader tags per jar, release types, review process — wiki setup/curseforge-publishing
- [x] Decide license/visibility/reward-program settings appropriate for All Rights Reserved mod — ARR via license dropdown; Release type; reward program optional opt-in; logo added to designer spec
- [ ] Create CurseForge project + upload both v1.0.0 jars once designer art lands
- [ ] Optional follow-up: wire CurseForge API token into release workflow for automated publishing

## Progress Log
- [2026-10-09T18:42:43.239Z] Created initiative via mdocs command
- [2026-10-09T18:55:00Z] Research complete against current CF support docs; findings recorded in wiki setup/curseforge-publishing; project logo added to docs/art/texture-specs.md designer handoff.
- Reciprocal wiki link added.

## Artifacts
