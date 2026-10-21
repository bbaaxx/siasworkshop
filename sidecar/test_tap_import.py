"""Tap->corpus importer test: tap_import.py must turn a recorded --tap wire.jsonl into a
corpus scenario skeleton — observation verbatim from observe_self, open_tasks parsed from
observe_board's board lines (garbage lines skipped with a warning), name from the decide
call's agent + the first tap date, assert block left as the TODO placeholder.

Also the negative case: a tap with observe_self but no observe_board must exit non-zero.

Run: python3 test_tap_import.py
"""
from __future__ import annotations

import json
import os
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))

OBSERVATION = (
    "[SELF] name=Workshop Agent pos=12,64,30 hp=20/20 held=empty task=idle\n"
    "[PLAYERS] Dev@6.2m hp=20 food=20 held=siasworkshop:agent_inspector\n"
    "[NEARBY BLOCKS] total=17240 types=19 top: stonex9011, dirtx3204"
)
BOARD_LINES = [
    "build:mazons:7d2f — small stone-brick house",
    "comfort:light:9a41 — dark spot",
    "a log line without the separator",
]


def _row(ts: str, direction: str, msg: dict) -> dict:
    return {"ts": ts, "dir": direction, "msg": msg}


def _tool_result(msg_id: int, body: dict) -> dict:
    return {"jsonrpc": "2.0", "id": msg_id,
            "result": {"isError": False,
                       "content": [{"type": "text", "text": json.dumps(body)}]}}


def write_wire(path: str, rows: list[dict]) -> None:
    with open(path, "w", encoding="utf-8") as fh:
        for row in rows:
            fh.write(json.dumps(row, ensure_ascii=False) + "\n")


def full_wire() -> list[dict]:
    """One complete observe/decide cycle as sidecar.py --tap would record it."""
    return [
        _row("2026-10-17T09:15:00.000Z", "out",
             {"jsonrpc": "2.0", "id": 1, "method": "initialize",
              "params": {"protocolVersion": "2025-06-18"}}),
        _row("2026-10-17T09:15:00.100Z", "in",
             {"jsonrpc": "2.0", "id": 1,
              "result": {"protocolVersion": "2025-06-18",
                         "serverInfo": {"name": "siasworkshop-mockmod", "version": "0.2.0"}}}),
        _row("2026-10-17T09:15:00.200Z", "out",
             {"jsonrpc": "2.0", "method": "notifications/initialized"}),
        _row("2026-10-17T09:15:01.000Z", "in",
             {"jsonrpc": "2.0", "id": 99, "method": "tools/call",
              "params": {"name": "decide",
                         "arguments": {"agent": "a1b2c3d4-1234-5678-9abc-def012345678"}}}),
        _row("2026-10-17T09:15:01.050Z", "out",
             {"jsonrpc": "2.0", "id": 2, "method": "tools/call",
              "params": {"name": "observe_self", "arguments": {"agent": "uuid-1"}}}),
        _row("2026-10-17T09:15:01.200Z", "in", _tool_result(2, {"observation": OBSERVATION})),
        _row("2026-10-17T09:15:01.250Z", "out",
             {"jsonrpc": "2.0", "id": 3, "method": "tools/call",
              "params": {"name": "observe_board", "arguments": {"agent": "uuid-1"}}}),
        _row("2026-10-17T09:15:01.400Z", "in",
             _tool_result(3, {"open_tasks": BOARD_LINES})),
        _row("2026-10-17T09:15:01.450Z", "out",
             {"jsonrpc": "2.0", "id": 4, "method": "tools/call",
              "params": {"name": "claim",
                         "arguments": {"task": "build:mazons:7d2f", "agent": "uuid-1"}}}),
        _row("2026-10-17T09:15:01.600Z", "in",
             _tool_result(4, {"claimed": "build:mazons:7d2f"})),
        _row("2026-10-17T09:15:02.000Z", "out",
             {"jsonrpc": "2.0", "id": 99,
              "result": {"isError": False,
                         "content": [{"type": "text", "text": json.dumps(
                             {"decision": "claim", "task": "build:mazons:7d2f"})}]}}),
        {"ts": "2026-10-17T09:15:02.100Z", "dir": "in",
         "unparsed": "{\"jsonrpc\": \"2.0\", \"id\": \broken"},
    ]


def run_importer(wire_path: str, *extra: str) -> subprocess.CompletedProcess:
    return subprocess.run([sys.executable, os.path.join(HERE, "tap_import.py"),
                           wire_path, *extra],
                          capture_output=True, text=True, timeout=30)


def main() -> int:
    tmp = tempfile.mkdtemp(prefix="siasworkshop-tap-import-")
    failures = 0

    wire_path = os.path.join(tmp, "wire.jsonl")
    write_wire(wire_path, full_wire())

    def check(cond: bool, label: str):
        nonlocal failures
        if not cond:
            failures += 1
            print(f"FAIL: {label}")

    proc = run_importer(wire_path)
    check(proc.returncode == 0, f"importer exited {proc.returncode}: {proc.stderr.strip()}")
    scenario = None
    if proc.returncode == 0:
        try:
            scenario = json.loads(proc.stdout)
        except ValueError:
            failures += 1
            print(f"FAIL: importer stdout is not JSON: {proc.stdout[:200]!r}")

    if scenario is not None:
        check(scenario.get("name") == "live-2026-10-17-a1b2c3d4",
              f"name = {scenario.get('name')!r}")
        check(scenario.get("player_name") == "Dev", "player_name != 'Dev'")
        check((scenario.get("seed"), scenario.get("trials"),
               scenario.get("permutations")) == (0, 8, 1), "seed/trials/permutations")
        check(scenario.get("observation") == OBSERVATION,
              "observation is not the observe_self content verbatim")
        check(scenario.get("open_tasks") == [
            {"key": "build:mazons:7d2f", "type": "BUILD",
             "detail": "small stone-brick house", "claimable": True},
            {"key": "comfort:light:9a41", "type": "COMFORT",
             "detail": "dark spot", "claimable": True},
        ], f"open_tasks = {scenario.get('open_tasks')!r}")
        check(scenario.get("assert") == {
            "forbid_claim_keys": [],
            "null_ground_truth": None,
            "notes": "TODO hand-annotate ground truth (corpus Q4)",
        }, "assert block is not the TODO placeholder")
        check(scenario.get("source") == {"tap": wire_path, "lines": len(full_wire())},
              f"source = {scenario.get('source')!r}")
    check("without" in proc.stderr and "tap_import: warning" in proc.stderr,
          f"garbage board line not skipped with a warning, stderr={proc.stderr.strip()!r}")

    # --out writes the same JSON to a file
    out_path = os.path.join(tmp, "scenario.json")
    proc_out = run_importer(wire_path, "--out", out_path)
    check(proc_out.returncode == 0, f"--out run exited {proc_out.returncode}")
    if proc_out.returncode == 0:
        try:
            with open(out_path, encoding="utf-8") as fh:
                from_file = json.load(fh)
            check(from_file == scenario, "--out file differs from stdout output")
        except (OSError, ValueError) as e:
            failures += 1
            print(f"FAIL: --out file unreadable: {e}")

    # negative: observe_self but no observe_board -> non-zero exit naming observe_board
    no_board_path = os.path.join(tmp, "no-board.jsonl")
    write_wire(no_board_path, [
        _row("2026-10-17T10:00:00.000Z", "out",
             {"jsonrpc": "2.0", "id": 1, "method": "tools/call",
              "params": {"name": "observe_self", "arguments": {"agent": "uuid-1"}}}),
        _row("2026-10-17T10:00:00.100Z", "in", _tool_result(1, {"observation": OBSERVATION})),
    ])
    proc_neg = run_importer(no_board_path)
    check(proc_neg.returncode != 0, "missing observe_board did not fail")
    check("observe_board" in proc_neg.stderr,
          f"error does not name observe_board: {proc_neg.stderr.strip()!r}")

    # negative: tap without even observe_self -> non-zero exit
    empty_path = os.path.join(tmp, "empty.jsonl")
    write_wire(empty_path, [
        {"ts": "2026-10-17T11:00:00.000Z", "dir": "in", "unparsed": "junk"},
    ])
    proc_empty = run_importer(empty_path)
    check(proc_empty.returncode != 0 and "observe_self" in proc_empty.stderr,
          "tap without observe_self did not fail clearly")

    print("tap import test:", "PASS" if failures == 0 else f"{failures} failures")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
