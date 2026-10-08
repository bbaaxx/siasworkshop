---
id: "forge-compatibility-1-20-1-verification-1-21-1-forge-build"
title: "Forge compatibility: 1.20.1 verification + 1.21.1 Forge build"
status: "done"
created: "2026-10-08"
updated: "2026-10-08"
owner: ""
tags: ["tooling","release","forge","backport"]
related_wiki: ["setup/multi-version-release-notes"]
priority: "medium"
graduated: "2026-10-08"
---

## Objective
Support MinecraftForge (not just NeoForge) on both MC versions. Two very different work items — see the estimate below.

## Plan


## Progress Log
- [2026-10-08T20:07:33.821Z] Created initiative via mdocs command; estimate prepared.
- [2026-10-08T21:30:00Z] Scope A approved. Build toggle added; first Forge resolution attempt failed (net.minecraftforge has no 47.1.106) → separate minecraftforge_version=1.20.1-47.4.26 property. CI job added.
- [2026-10-08T21:45:00Z] Note: initiative file was accidentally committed on main (e222240) via an audit-log sweep; cherry-picked to this branch (01d82ad). Workflow re-resumed here.
- [2026-10-08T20:18:34.929Z] Marked done via mdocs command

## Artifacts
