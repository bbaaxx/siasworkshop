"""Bridge test: sidecar.py (client role) against mockmod.py (server role) over crossed
pipes — the exact production topology, no Java required.

The driver spawns both, relays bytes both ways (line-locked), injects one tools/call
decide as the mod would, and asserts the decide summary reports the claimed task.

Run: python3 test_sidecar_bridge.py
"""
from __future__ import annotations

import json
import os
import subprocess
import sys
import threading
import time

HERE = os.path.dirname(os.path.abspath(__file__))


def main() -> int:
    mock = subprocess.Popen([sys.executable, os.path.join(HERE, "mockmod.py"),
                             os.path.join(HERE, "scenarios/stone_bricks_chest.json")],
                            stdin=subprocess.PIPE, stdout=subprocess.PIPE)
    sidecar = subprocess.Popen([sys.executable, os.path.join(HERE, "sidecar.py"),
                                "--replay", os.path.join(HERE, "replay/bridge-script.json")],
                               stdin=subprocess.PIPE, stdout=subprocess.PIPE)
    write_lock = threading.Lock()
    seen = {"initialize": False, "initialized": False, "response": None}
    done = threading.Event()

    def relay(src, dst, tag):
        for line in src:
            with write_lock:
                dst.write(line)
                dst.flush()
            text = line.decode(errors="replace").strip()
            flat = text.replace(" ", "")
            if tag == "s2m" and '"method":"initialize"' in flat:
                seen["initialize"] = True
            if tag == "s2m" and "notifications/initialized" in text:
                seen["initialized"] = True
            if tag == "s2m" and '"id":99' in flat and '"result"' in flat:
                try:
                    seen["response"] = json.loads(text)
                except ValueError:
                    pass
                done.set()
        done.set()

    threads = [
        threading.Thread(target=relay, args=(sidecar.stdout, mock.stdin, "s2m"), daemon=True),
        threading.Thread(target=relay, args=(mock.stdout, sidecar.stdin, "m2s"), daemon=True),
    ]
    for t in threads:
        t.start()

    time.sleep(0.7)  # handshake first
    decide_request = (json.dumps({"jsonrpc": "2.0", "id": 99, "method": "tools/call",
                                  "params": {"name": "decide",
                                             "arguments": {"agent": "agent-1"}}}) + "\n").encode()
    with write_lock:
        sidecar.stdin.write(decide_request)
        sidecar.stdin.flush()

    finished = done.wait(timeout=20)
    for proc in (mock, sidecar):
        try:
            proc.terminate()
            proc.wait(timeout=5)
        except Exception:
            proc.kill()

    failures = 0
    if not seen["initialize"]:
        failures += 1
        print("FAIL: sidecar never sent initialize")
    if not seen["initialized"]:
        failures += 1
        print("FAIL: initialized notification never seen")
    if not finished or seen["response"] is None:
        failures += 1
        print("FAIL: no decide response captured")
    else:
        content = seen["response"].get("result", {}).get("content", [])
        summary = json.loads(content[0]["text"]) if content else {}
        print("decide summary:", json.dumps(summary))
        if summary.get("decision") != "claim" or summary.get("task") != "build:mazons:7d2f":
            failures += 1
            print(f"FAIL: expected claim of build:mazons:7d2f, got {summary}")
    print("bridge test:", "PASS" if failures == 0 else f"{failures} failures")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
