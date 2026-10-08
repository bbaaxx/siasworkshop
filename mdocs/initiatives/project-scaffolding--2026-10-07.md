---
id: "project-scaffolding"
title: "Scaffold project structure and dev loop"
status: "done"
created: "2026-10-07"
updated: "2026-10-07"
owner: ""
tags: []
related_wiki: ["setup/datagen-notes-1-21-1"]
priority: "medium"
graduated: "2026-10-07"
---

## Objective
Restructure the stock MDK into a maintainable package layout (init/ per-registry DeferredRegister holders, content/ per-feature folders, config/ for configuration), wire datagen for example assets so first-run WARNs disappear, and document the dev loop (build, runData, runClient, mdocs) in AGENTS.md — adopting the best practices from reference/ecosystem-map while keeping iteration fast.

## Plan
- [x] Create and activate initiative; advance workflow to EXECUTE
- [x] Restructure packages: init/, content/, config/ following the Ender IO pattern
- [x] Slim the main mod class to wiring; move registrations into init holders
- [x] Add datagen providers for example assets and run runData
- [x] Add AGENTS.md documenting the dev loop and conventions
- [x] Verify: gradle build green, generated assets present, mdocs validate clean
- [/] REPORT and COMPLETE

## Progress Log
- [2026-10-07T21:36:19.566Z] Created initiative via mdocs command
- [2026-10-07T21:43:52.787Z] Marked done via mdocs command

## Artifacts
