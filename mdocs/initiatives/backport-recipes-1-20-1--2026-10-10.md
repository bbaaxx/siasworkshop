---
id: "backport-recipes-1-20-1"
title: "Backport end-game tool recipes to 1.20.1 (final 1.20.x release)"
status: "done"
created: "2026-10-10"
updated: "2026-10-10"
owner: ""
tags: ["backport","recipes","datagen","release"]
related_wiki: ["setup/multi-version-release-notes"]
priority: "medium"
next_action: "Done"
---

## Objective
Port the wilderness compass + village foundation recipes (shipped on main/1.21.1 in initiative endgame-village-tool-recipes) to the version/1.20.1 branch. NOTE: the user has decided this is the LAST feature release supporting 1.20.x.

## Plan
- [ ] Create git worktree for version/1.20.1
- [ ] Implement 1.20.1-flavored ModRecipeProvider (pre-1.20.5 datagen API, RegistryObject holders)
- [ ] runData + build in the worktree
- [ ] Review diff, commit to version/1.20.1

## Progress Log
- [2026-10-10T22:40:13.993Z] Created initiative via mdocs command
- Worktree created at /tmp/siasworkshop-1.20.1 on version/1.20.1 (HEAD e8e3cff, v1.0.1). No ModRecipeProvider exists on that branch yet.
- Backport committed to version/1.20.1: ModRecipeProvider (1.20.1 FinishedRecipe API) + generated recipes/advancements. runData + build green in worktree; reviewer no blocking findings. User confirmed this is the final 1.20.x feature release.
- [2026-10-10T22:50:06.994Z] Marked done via mdocs command
- Local smoke test PASSED on 1.20.1 (version/1.20.1 @ d55be60): recipe unlock toasts, both items craft with identical patterns to main, compass + foundation functional. Both supported versions verified.

## Artifacts
