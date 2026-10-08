# Ideas Backlog

Things we want to come back to. Details live in the mdocs wiki/initiatives — this file is just the pointer list.

## 1. Recipes for the village tools

The Wilderness Compass and Village Foundation are creative-tab only. Design survival recipes
(and decide balance: the foundation probably wants rare/expensive ingredients since it creates
a village). See `worldgen/village-tools-notes` and initiative
`village-tools-wilderness-compass-village-foundation-block`.

## 2. Style picker in the Village Foundation GUI

The foundation GUI currently offers flavors Cherry Blossom / Vanilla, always plains style.
Add a village-style picker (plains / snowy / taiga / savanna / desert) so players can choose
which village type to spawn. Needs per-style processor lists with the right `primary` family
(e.g. `cherryfy_savanna` with `primary: "acacia"`, snowy/taiga `primary: "spruce"`) — see the
Roll-out status section in `worldgen/cherryfy-villages`. Desert mapping still undecided
(sandstone has no cherry equivalent).

## 3. Roll the cherry reskin out to savanna / snowy / taiga

Behind the same `cherryfyVillages` config flag, cherryfy the remaining village styles so the
option covers all villages, not just plains. Same per-style `primary` work as idea 2; the
pool-override generator script (see `worldgen/cherryfy-villages`) already handles multiple
pools — extend its style list. Keep the config default off; reassess the vanilla-pool-override
conflict risk if it ever defaults on.
