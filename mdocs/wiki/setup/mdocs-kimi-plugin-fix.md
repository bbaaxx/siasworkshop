---
id: "mdocs-kimi-plugin-fix"
title: "mdocs Kimi Plugin MCP Launch Fix (realpath '-e')"
category: "setup"
created: "2026-10-17"
updated: "2026-10-17"
related_initiatives: []
tags: ["handoff","mdocs","kimi-code","mcp","plugin"]
lifecycle: "stable"
knowledge_type: "reference"
confidence: "high"
source_initiatives: []
---

# mdocs Kimi plugin MCP launch fix — read before touching the plugin

## What happened

Every Kimi Code session in this repo failed to start the mdocs MCP server
(`MCP server "plugin-mdocs:mdocs" failed: Connection closed / stderr: ENOENT …
realpath '-e'`) — since 2026-09-22, i.e. since the plugin was first installed.
Installing harness-mdocs 2.2.0 did NOT fix it: the 2.2.0 manifest launches the
server via `node -e "<inline script>"`, and Kimi Code's native binary rewrites
`command: "node"` plugin servers to `kimi __plugin_run_node <entry>`, which
parses the literal `-e` as the entry file path and runs `realpath('-e')`.

## Current state (2026-10-17)

- **Managed plugin copy** (`~/.kimi-code/plugins/managed/mdocs/`) is patched
  IN PLACE: new `kimi-mcp-entry.js` + manifest args `["./kimi-mcp-entry.js"]`.
  Verified end-to-end with the real binary (initialize, tools/list, status).
- **harness-mdocs repo** (`~/Code/projects/openagent-mdocs/harness-mdocs`) holds
  the durable fix as an uncommitted 4-file change (manifest, entry file, 2
  tests) on `main` @ 2.2.0. Full diagnosis + follow-ups:
  https://github.com/bbaaxx/harness-mdocs/issues/10#issuecomment-6236245258
- **Pending:** the repo workstream cuts 2.2.1 and the plugin is reinstalled from
  the updated package (a reinstall/refresh of the managed copy re-applies it).

## Traps for the next agent

- **The in-place patch is ephemeral.** Any plugin update/reinstall before 2.2.1
  restores the broken `node -e` manifest and the session-start error returns.
  Do NOT "fix" the managed manifest back to the `-e` form — that form cannot
  work under the native binary.
- **Memory anchoring gap (pre-existing, upstream).** Kimi spawns plugin MCP
  servers with `cwd` = plugin root and no session-cwd env, so MCP server tools
  anchor memory at the plugin dir unless `MDOCS_PROJECT_DIR` is pinned. The
  workflow hooks are unaffected (per-project payloads) — hook state and MCP
  tool state can disagree about the project. See issue #10 follow-ups.
- This workspace's stale `EXECUTE` initiative
  `mcp-model-behavior-research--2026-10-15` belongs to the in-mod MCP
  workstream — it is unrelated to the plugin failure above.
