---
id: "compass-fix-foundation-consume"
title: "v1.0.1: compass pre-scan behavior + foundation consumption"
status: "done"
created: "2026-10-09"
updated: "2026-10-09"
owner: ""
tags: ["bugfix","enhancement","release"]
related_wiki: []
priority: "medium"
next_action: "Verify v1.0.1 release assets, then graduate + COMPLETE"
graduated: "2026-10-09"
---

## Objective
Fix the wilderness compass spinning wildly before its first scan (it should show a calm no-target state until right-clicked), make the Village Foundation block consume itself on successful village creation, and ship both fixes in the pending v1.0.1 release on main (1.21.1) and version/1.20.1.

## Plan
- [x] Investigate compass property function no-target path and foundation creation flow
- [x] Fix: compass renders a static no-target state until first scan (main)
- [x] Enhance: foundation block destroys itself after successful village creation (main)
- [x] Verify: build + client test drive — user-verified in-game 2026-10-09
- [x] Port both changes to version/1.20.1 (worktree flow)
- [x] Cut v1.0.1 release with final art + fixes on both branches — https://github.com/bbaaxx/siasworkshop/releases/tag/v1.0.1

## Progress Log
- [2026-10-09T19:03:13.203Z] Created initiative via mdocs command
- Both fixes implemented and user-verified in-game: compass rests at up frame with no target (unclampedCall override returning 0.0F when village_target absent) and Village Foundation is consumed on successful creation (serverLevel.destroyBlock, no drops; menu auto-closes). Ported to version/1.20.1 (e8e3cff, incl. 1.0.1 bump); main at 77edba6. v1.0.1 push+tag in flight.
- [2026-10-09T20:38:43.697Z] Marked done via mdocs command

## Artifacts
