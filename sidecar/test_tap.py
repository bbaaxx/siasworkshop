"""Wire-tap test: sidecar.py --tap must record the full mod<->sidecar exchange as
timestamped, direction-tagged JSON lines while the protocol on stdout stays pure.

Runs the same crossed-pipe topology as test_sidecar_bridge.py, asserts the tap file
captured both directions (out: initialize/initialized/decide-response; in: the mod's
initialize result and tool results) and that every tap line parses as the documented
{"ts", "dir", "msg"} shape.

Run: python3 test_tap.py
"""
from __future__ import annotations

import json
import os
import subprocess
import sys
import tempfile
import threading
import time

HERE = os.path.dirname(os.path.abspath(__file__))


def main() -> int:
    tap_path = os.path.join(tempfile.mkdtemp(prefix="siasworkshop-tap-"), "wire.jsonl")
    mock = subprocess.Popen([sys.executable, os.path.join(HERE, "mockmod.py"),
                             os.path.join(HERE, "scenarios/stone_bricks_chest.json")],
                            stdin=subprocess.PIPE, stdout=subprocess.PIPE)
    sidecar = subprocess.Popen([sys.executable, os.path.join(HERE, "sidecar.py"),
                                "--replay", os.path.join(HERE, "replay/bridge-script.json"),
                                "--tap", tap_path],
                               stdin=subprocess.PIPE, stdout=subprocess.PIPE)
    write_lock = threading.Lock()
    done = threading.Event()

    def relay(src, dst, tag):
        for line in src:
            with write_lock:
                dst.write(line)
                dst.flush()
            if tag == "s2m" and '"id":99' in line.decode(errors="replace").replace(" ", "") \
                    and '"result"' in line.decode(errors="replace"):
                done.set()
        done.set()

    for args in ((sidecar.stdout, mock.stdin, "s2m"), (mock.stdout, sidecar.stdin, "m2s")):
        threading.Thread(target=relay, args=args, daemon=True).start()

    time.sleep(0.7)  # handshake first
    decide_request = (json.dumps({"jsonrpc": "2.0", "id": 99, "method": "tools/call",
                                  "params": {"name": "decide",
                                             "arguments": {"agent": "agent-1"}}}) + "\n").encode()
    with write_lock:
        sidecar.stdin.write(decide_request)
        sidecar.stdin.flush()
    finished = done.wait(timeout=20)
    time.sleep(0.3)  # let the tap file flush before reading it
    for proc in (mock, sidecar):
        try:
            proc.terminate()
            proc.wait(timeout=5)
        except Exception:
            proc.kill()

    failures = 0
    if not finished:
        failures += 1
        print("FAIL: relay ended before the decide cycle")

    rows = []
    if os.path.exists(tap_path):
        with open(tap_path, encoding="utf-8") as fh:
            for lineno, text in enumerate(fh, 1):
                text = text.strip()
                if not text:
                    continue
                try:
                    row = json.loads(text)
                except ValueError:
                    failures += 1
                    print(f"FAIL: tap line {lineno} is not JSON: {text[:120]!r}")
                    continue
                if not ("ts" in row and "dir" in row and ("msg" in row or "unparsed" in row)):
                    failures += 1
                    print(f"FAIL: tap line {lineno} missing ts/dir/msg: {row}")
                    continue
                if row["dir"] not in ("in", "out"):
                    failures += 1
                    print(f"FAIL: tap line {lineno} bad dir: {row['dir']}")
                    continue
                rows.append(row)
    else:
        failures += 1
        print(f"FAIL: tap file was never created: {tap_path}")

    out_msgs = [r["msg"] for r in rows if r["dir"] == "out" and "msg" in r]
    in_msgs = [r["msg"] for r in rows if r["dir"] == "in" and "msg" in r]
    out_methods = [m.get("method") for m in out_msgs]
    if "initialize" not in out_methods:
        failures += 1
        print("FAIL: tap missing outbound initialize")
    if "notifications/initialized" not in out_methods:
        failures += 1
        print("FAIL: tap missing outbound initialized notification")
    if not any(m.get("id") == 99 and "result" in m for m in out_msgs):
        failures += 1
        print("FAIL: tap missing the sidecar's decide response (id 99, out)")
    if not any(m.get("id") == 99 and "method" in m for m in in_msgs):
        failures += 1
        print("FAIL: tap missing the decide request relayed into the sidecar (id 99, in)")
    if not any(m.get("result", {}).get("serverInfo") for m in in_msgs):
        failures += 1
        print("FAIL: tap missing the mod's initialize result (inbound serverInfo)")
    if any("unparsed" in r for r in rows):
        failures += 1
        print("FAIL: wire corruption — unparsed lines on the tap")

    print(f"tap rows: {len(rows)} ({sum(1 for r in rows if r['dir'] == 'out')} out,"
          f" {sum(1 for r in rows if r['dir'] == 'in')} in)")
    print("tap test:", "PASS" if failures == 0 else f"{failures} failures")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
