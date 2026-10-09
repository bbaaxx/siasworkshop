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
next_action: "User creates CF project in browser using docs/curseforge-description.html + logo; upload v1.0.1 jars with NeoForge tags"
---

## Objective
Get Sia's Workshop v1.0.0 published on CurseForge for MC 1.21.1 (NeoForge 21.1.x) and 1.20.1 (NeoForge 47.1.x): investigate the exact author setup, project creation, file upload, and review steps, then execute them (manual upload first; CI automation optional follow-up).

## Plan
- [x] Investigate CurseForge author account + project creation requirements (current docs) — wiki setup/curseforge-publishing
- [x] Investigate file upload specifics: game versions/loader tags per jar, release types, review process — wiki setup/curseforge-publishing
- [x] Decide license/visibility/reward-program settings appropriate for All Rights Reserved mod — ARR via license dropdown; Release type; reward program optional opt-in; logo added to designer spec
- [x] Create CurseForge project + upload jars (v1.0.1, both MC versions with NeoForge tags) — submitted 2026-10-09, UNDER REVIEW
- [ ] Optional follow-up: wire CurseForge API token into release workflow for automated publishing (project ID exists once approved)

## Progress Log
- [2026-10-09T18:42:43.239Z] Created initiative via mdocs command
- [2026-10-09T18:55:00Z] Research complete against current CF support docs; findings recorded in wiki setup/curseforge-publishing; project logo added to docs/art/texture-specs.md designer handoff.
- Reciprocal wiki link added.
- [2026-10-09T21:05:00Z] Project "Sia's Workshop" created on CurseForge with logo + description draft (docs/curseforge-description.html); both v1.0.1 jars uploaded (1.21.1 + 1.20.1, NeoForge tags, Release). Project under moderation review.
- Project page description drafted at docs/curseforge-description.html (paste-ready HTML: pitch, compass/foundation/cherryfy features, usage steps, version table, config, modpack + source notes; screenshots + designer credit marked TODO). Summary blurb: "A compass that points away from villages so you know when you are clear to build one — then a block that generates the whole village. Cherry blossom included."

## Artifacts
