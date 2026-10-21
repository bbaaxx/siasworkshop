---
id: "mcp-hotplug-debug-access--2026-10-17"
title: "MCP hot-plug debug access: observe the live model↔mod channel"
status: "complete"
created: "2026-10-17"
updated: "2026-10-17"
owner: "coordinator"
tags: ["mcp", "agents", "tooling", "debug", "observability"]
related_wiki: ["reference/mcp-pattern-tree-design", "setup/agent-shared-ledger", "setup/session-handoff-2026-10-17", "reference/minecraft-mcp-servers", "setup/test-run-protocol-2026-10-20"]
priority: "medium"
graduated: "2026-10-17"
---

## Objective

Give the coordinator agent (and any MCP client) a way to **hot-plug into the running
MCP channel** between the mod and the sidecar, to observe firsthand what the LLM sees —
the rendered observation, the board listing, the tool catalog, and the live request/
response traffic — without restarting the game or editing code.

## Motivation (pain observed in the S1 live test, SEQ-024)

During the first live run of the in-mod MCP path, every model-behavior question cost a
manual forensics round:

- A sidecar reject surfaced in the mod log as `decided via MCP sidecar: unknown` with an
  empty detail — the actual reject reason was on the wire but nobody was watching it.
  Diagnosis required code-reading, not evidence.
- The model latching onto the first-listed unroutable task (`build:mazons:-20,86,0`)
  was inferred from log timestamps and the board dump, not observed directly.
- The COMFORT_SHELTER leak was caught by luck (the model happened to pick it). What else
  is on the wire that we never see?
- Board dumps via `/siasworkshop board` are point-in-time and player-driven; the wire
  traffic (observation text the model actually receives, exact claim args, rejects) is
  invisible.

A tap/attach capability turns every future live test from "instrument after the fact"
into "watch the model's context in real time".

## Direction (design sketch, to be validated in PLAN)

Two phases, both **default-OFF, localhost-only, dev-only** (CurseForge installs must not
open any listener; same stance as `agents.mcp.enabled`):

- **P1 — passive wire tap (cheap, immediate value).** The wire is already newline-
  delimited JSON-RPC. Add a `--tap <file>` mode to `sidecar.py` that tees every inbound
  and outbound line (timestamped, direction-tagged) to a file the coordinator tails.
  Zero mod changes; answers "what did the model see/say" for the S1 decision channel.
- **P2 — active attach (full fidelity).** A second MCP transport on the mod for debug
  clients: when `agents.mcp.debugPort > 0` (default 0 = off), the mod listens on
  127.0.0.1:<port> speaking the same symmetric newline-JSON-RPC as the stdio channel.
  Attach with a small stdlib client (`tools/mcp_probe.py` or Kimi Code's MCP client
  support): `tools/list`, `observe_self`, `observe_board {agent}`, and read-only traffic.
  Write tools (claim/rest/say) behind a second flag or left to the tap, to be decided.

Security stance: bind 127.0.0.1 only, config-gated (default OFF), no auth needed at
localhost scope, and the debug transport must never spawn when `agents.mcp.enabled` is
off. Dev builds only.

## Open questions

1. P2 transport shape: raw TCP listener in `McpSidecar` (thread-per-connection, same
   server-thread dispatch as inbound sidecar calls) vs a socket the *sidecar* exposes
   that relays into its mod session. Mod-side keeps the mod as the tool-registry owner;
   sidecar-side needs no mod changes. Lean: mod-side, symmetric with existing design.
2. Does the debug client get the *exact* observation the model got (requires tapping
   inside the sidecar's decide flow) or a fresh `observe_self` at attach time (stale
   the moment the world moves)? P1's tap answers this for the S1 channel; P2 re-query
   is probably enough for a live human/agent debugger.
3. Read-only vs read-write for the debug client: read-only is the safe default; calling
   `claim` as the coordinator could double-claim against a live agent run. Gate writes
   behind a separate config flag if ever needed.
4. Relationship to Q4 scenario corpus (mcp-model-behavior-research): the tap file is a
   natural recorded-artifact format for corpus scenarios — align the tap format with the
   replay script schema so a live session can be replayed offline.

## Plan

- [x] P1: `--tap <file>` wire tap in `sidecar.py` (timestamped, direction-tagged lines)
      + a tailing recipe for the coordinator; validate during the next live test round
      → DONE (2026-10-17): `WireTap` class, `{"ts","dir","msg"}` JSONL per line, append
      mode, stdout stays protocol-pure, tap I/O errors never break the wire; unparsed
      lines recorded as wire-corruption evidence; `sidecar/test_tap.py` PASS; README
      tail recipe (live: `tail -f`; decisions only: grep dir=in tools/call)
- [x] P2: `agents.mcp.debugPort` config (0 = off) + localhost TCP transport on the mod
      reusing the existing MCP dispatch; `tools/mcp_probe.py` stdlib attach client
      (handshake → tools/list → observe_self/observe_board → optional traffic echo)
      → DONE (2026-10-17): `content/mcp/McpDebugServer` (loopback-only bind, thread-per-
      connection, read-only catalog via McpTools.listDescriptor(true) + isReadOnly
      dispatch guard, server-thread execution like the sidecar channel, lifecycle rides
      McpSidecar.startFor/stop, bind failure never kills the sidecar); `tools/mcp_probe.py
      list|self|board`; stdio hygiene checked by inspection (only JSON-RPC on stdout)
- [x] Gametest for the debug transport (in-JVM socket client asserting catalog + a
      read-only observation), default-off gating verified by inspection as with
      `agents.mcp.enabled`
      → DONE (2026-10-17): `McpDebugGameTest` — pure gate predicate pinned
      (never starts when mcp off or port 0) + in-JVM socket client over ephemeral port
      asserting read-only catalog, observe_board on the real board, and claim rejected
      by name; needed `mcpdebuggametest.empty.nbt` (structures resolve per class name)
- [x] Update `reference/mcp-pattern-tree-design` with the debug-access channel section
      → DONE (2026-10-17): "Debug access channel (hot-plug)" section incl. the four
      open-question resolutions (mod-side transport; fresh observe at attach; read-only
      default; tap format = corpus artifact format)

## Progress Log

- [2026-10-17] Initiative created from user direction after the S1 live test: "we need
      to provide you with MCP access so that you can experience firsthand what the LLMs
      are seeing" — hot-plug into the running MCP server, tackled as its own initiative
      alongside mcp-model-behavior-research (ledger SEQ-025).
- [2026-10-17] P1+P2 implemented and green: python `test_sidecar_bridge.py` + new
      `test_tap.py` PASS; `./gradlew runGameTestServer` 38/38 (was 36; +2 debug
      transport tests). Base commit a6edcfd, code uncommitted at close. First LIVE use
      is the next dev round per the user's sequencing ("start building complexity from
      simple patterns... so you have full visibility during development of the next
      stages") — tail the tap while the model decides; attach with mcp_probe for
      point-in-time board/observation reads.
