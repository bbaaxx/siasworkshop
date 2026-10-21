"""Tap -> corpus importer (corpus Q4): convert a recorded live wire tap (sidecar.py --tap)
into a corpus scenario skeleton. Closes the README P1 loop — the tap doubles as the
recorded-artifact format for corpus scenarios. Ground truth is always hand-annotated
post-import: the `assert` block is emitted as a TODO placeholder.

Extracts the first complete observe/decide cycle: observe_self's result body becomes the
scenario `observation`, observe_board's board lines become `open_tasks`, and the decide
call's agent uuid + the first tap timestamp become the scenario name. Mod-tool results
are parsed exactly like sidecar.py's call_mod_tool (JSON body in content[0].text, with a
{"raw": text} fallback).

Run: python3 tap_import.py WIRE.jsonl [--out PATH.json]   (prints to stdout without --out)
"""
from __future__ import annotations

import argparse
import datetime
import json
import sys

EM_DASH = "\u2014"  # the exact separator McpTools.java and the mock emit
ASSERT_PLACEHOLDER = {
    "forbid_claim_keys": [],
    "null_ground_truth": None,
    "notes": "TODO hand-annotate ground truth (corpus Q4)",
}


class TapError(Exception):
    """Fatal import problem; the message is user-facing."""


def mod_body(result: dict) -> dict:
    """Mirror sidecar.py call_mod_tool: the MCP content text is the mod tool's JSON
    body; anything that is not a JSON object degrades to {"raw": text}."""
    content = result.get("content") or []
    text = "\n".join(c.get("text", "") for c in content if c.get("type") == "text")
    try:
        parsed = json.loads(text) if text.strip() else None
    except ValueError:
        parsed = None
    return parsed if isinstance(parsed, dict) else {"raw": text}


def extract(path: str) -> dict:
    """Pull observation / open_tasks / decide agent out of the tap's first complete
    observe/decide cycle. `unparsed` rows are corrupt wire rows and skipped silently."""
    pending: dict[tuple[str, object], str] = {}  # (dir, id) -> tools/call name
    observation = None
    board_lines = None
    decide_agent = None
    first_ts = None
    lines = 0
    try:
        fh = open(path, encoding="utf-8")
    except OSError as e:
        raise TapError(f"cannot read {path}: {e}")
    with fh:
        for text in fh:
            text = text.strip()
            if not text:
                continue
            lines += 1
            try:
                row = json.loads(text)
            except ValueError:
                continue  # a corrupt tap line, same as an unparsed wire row
            if first_ts is None and isinstance(row.get("ts"), str) and row["ts"]:
                first_ts = row["ts"]
            msg = row.get("msg")
            if not isinstance(msg, dict):
                continue
            direction = row.get("dir")
            if msg.get("method") == "tools/call":
                params = msg.get("params") or {}
                msg_id = msg.get("id")
                if msg_id is not None and direction in ("in", "out"):
                    pending[(direction, msg_id)] = params.get("name", "")
                if params.get("name") == "decide" and decide_agent is None:
                    agent = (params.get("arguments") or {}).get("agent")
                    if isinstance(agent, str) and agent:
                        decide_agent = agent
                continue
            msg_id = msg.get("id")
            if msg_id is None or "result" not in msg:
                continue
            result = msg.get("result")
            if not isinstance(result, dict) or result.get("isError"):
                continue  # an errored observe answers nothing
            # a result travels the opposite direction from its request
            want_dir = "in" if direction == "out" else "out"
            tool = pending.pop((want_dir, msg_id), None)
            body = mod_body(result)
            if observation is None and isinstance(body.get("observation"), str) \
                    and (tool in (None, "observe_self")):
                observation = body["observation"]
            if board_lines is None and isinstance(body.get("open_tasks"), list) \
                    and (tool in (None, "observe_board")):
                board_lines = body["open_tasks"]

    if observation is None:
        raise TapError(f"no observe_self result found in {path}; "
                       "the tap must contain a tools/call observe_self/result pair")
    if board_lines is None:
        raise TapError(f"no observe_board result found in {path}; "
                       "the tap must contain a tools/call observe_board/result pair")

    open_tasks = []
    for line in board_lines:
        if not isinstance(line, str):
            print(f"tap_import: warning: skipping non-string board line: {line!r}",
                  file=sys.stderr)
            continue
        if EM_DASH not in line:
            print(f"tap_import: warning: skipping board line without '{EM_DASH}': "
                  f"{line!r}", file=sys.stderr)
            continue
        key, detail = line.split(EM_DASH, 1)
        key = key.strip()
        if not key:
            print(f"tap_import: warning: skipping board line with empty key: {line!r}",
                  file=sys.stderr)
            continue
        open_tasks.append({"key": key, "type": key.split(":")[0].upper(),
                           "detail": detail.strip(), "claimable": True})
    if not open_tasks:
        raise TapError(f"observe_board in {path} yielded no parseable 'key {EM_DASH} detail' "
                       "board lines")

    date = (first_ts[:10] if first_ts and len(first_ts) >= 10
            else datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%d"))
    suffix = decide_agent[:8] if decide_agent else "unknown"
    return {
        "name": f"live-{date}-{suffix}",
        "player_name": "Dev",
        "seed": 0,
        "trials": 8,
        "permutations": 1,
        "observation": observation,
        "open_tasks": open_tasks,
        "assert": dict(ASSERT_PLACEHOLDER),
        "source": {"tap": path, "lines": lines},
    }


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("tap", metavar="WIRE.jsonl", help="recorded sidecar.py --tap file")
    ap.add_argument("--out", metavar="PATH.json",
                    help="write the scenario here (default: stdout)")
    args = ap.parse_args()

    try:
        scenario = extract(args.tap)
    except TapError as e:
        print(f"tap_import: error: {e}", file=sys.stderr)
        return 1
    text = json.dumps(scenario, indent=2, ensure_ascii=False) + "\n"
    if args.out:
        try:
            with open(args.out, "w", encoding="utf-8") as fh:
                fh.write(text)
        except OSError as e:
            print(f"tap_import: error: cannot write {args.out}: {e}", file=sys.stderr)
            return 1
    else:
        sys.stdout.write(text)
    return 0


if __name__ == "__main__":
    sys.exit(main())
