"""S0 spike runner: A/B one decision surface (v1 vs mcp-prompt vs mcp-native) per model.

Examples:
  # deterministic plumbing self-test (no network, FakeModel with scripted replies):
  python3 run_spike.py --replay replay/script.json --scenario scenarios/stone_bricks_chest.json

  # local leg (LM Studio):
  python3 run_spike.py --endpoint http://127.0.0.1:1234/v1 --model prism-ml/bonsai-27b \
      --mode all --iterations 2 --scenario scenarios/stone_bricks_chest.json

  # cloud leg (OpenRouter config from the test world; key never printed):
  python3 run_spike.py --cloud-config ../run/saves/TestLLM_01/serverconfig/siasworkshop-server.toml \
      --mode all --iterations 3 --scenario scenarios/stone_bricks_chest.json
"""
from __future__ import annotations

import argparse
import json
import os
import re
import statistics
import sys
import time

from mcp import spawn_mock
from model import FakeModel, OpenAiChat, profile_for
from session import run_decision

MODES = ["v1", "mcp-prompt", "mcp-native"]


def parse_llm_toml(path: str) -> dict:
    """Naive [llm] section parse (avoids a tomllib dependency floor); apiKey stays internal."""
    cfg: dict = {}
    in_llm = False
    with open(path, encoding="utf-8") as fh:
        for line in fh:
            stripped = line.strip()
            if stripped.startswith("["):
                in_llm = stripped == "[llm]"
                continue
            if not in_llm or "=" not in stripped or stripped.startswith("#"):
                continue
            key, _, val = stripped.partition("=")
            key, val = key.strip(), val.strip()
            if val.startswith('"') and val.endswith('"'):
                cfg[key] = val[1:-1]
            else:
                try:
                    cfg[key] = int(val)
                except ValueError:
                    try:
                        cfg[key] = float(val)
                    except ValueError:
                        cfg[key] = val.lower() == "true"
    return cfg


def load_scenario(path: str) -> dict:
    with open(path, encoding="utf-8") as fh:
        return json.load(fh)


def run_one(mode: str, model, model_tag: str, scenario_path: str, profile: dict) -> dict:
    client = spawn_mock(scenario_path)
    try:
        client.start()
        client.list_tools()  # exercises the catalog hop; result unused by S0 decisions
        decision = run_decision(mode, model, model_tag, client, profile)
        scenario = load_scenario(scenario_path)
        return decision.to_row(model=model_tag, scenario=scenario.get("name", scenario_path),
                               iteration=0)
    finally:
        client.close()


def summarize(rows: list[dict]) -> None:
    groups: dict[tuple, list[dict]] = {}
    for row in rows:
        groups.setdefault((row["model"], row["mode"]), []).append(row)
    print("\n=== summary (model × mode) ===")
    header = f'{"model":<34} {"mode":<11} {"n":>3} {"ok%":>6} {"malformed%":>10} {"other%":>7} {"mean s":>7} {"p50 s":>6} {"tok/call":>8}'
    print(header)
    print("-" * len(header))
    for (model, mode), group in sorted(groups.items()):
        n = len(group)
        oks = sum(1 for r in group if r["decision_outcome"] == "ok" and (r["exec_outcome"] or "exec_ok") == "exec_ok")
        malformed = sum(1 for r in group if r["decision_outcome"] == "malformed_json")
        lat = [r["total_latency_ms"] / 1000 for r in group]
        toks = [r["completion_tokens"] for r in group if r["completion_tokens"] is not None]
        print(f"{model:<34} {mode:<11} {n:>3} {oks / n * 100:>5.0f}% {malformed / n * 100:>9.0f}% "
              f"{(n - oks - malformed) / n * 100:>6.0f}% {statistics.mean(lat):>7.1f} "
              f"{statistics.median(lat):>6.1f} "
              f"{(int(statistics.mean(toks)) if toks else 0):>8}")


def replay(script_path: str, scenario_path: str) -> int:
    """Deterministic plumbing test: scripted model replies, asserted outcomes (the gametest analog)."""
    with open(script_path, encoding="utf-8") as fh:
        script = json.load(fh)
    scenario = load_scenario(scenario_path)
    failures = 0
    rows = []
    for mode in MODES:
        applicable = [s for s in script if not s.get("modes") or mode in s["modes"]]
        model = FakeModel(applicable, scenario)
        for entry in applicable:
            row = run_one(mode, model, mode, scenario_path, {})
            rows.append(row)
            checks = []
            if "expect_tool" in entry:
                checks.append(("tool", row["tool"], entry["expect_tool"]))
            if "expect_decision" in entry:
                checks.append(("decision_outcome", row["decision_outcome"], entry["expect_decision"]))
            if "expect_exec" in entry:
                checks.append(("exec_outcome", row["exec_outcome"], entry["expect_exec"]))
            failed = [c for c in checks if c[1] != c[2]]
            status = "PASS" if not failed else "FAIL"
            if failed:
                failures += 1
            detail = f"tool={row['tool']} decision={row['decision_outcome']} exec={row['exec_outcome']}"
            print(f"[{status}] {mode:<11} {entry['name']:<22} {detail}")
            for kind, actual, expected in failed:
                print(f"        ^ {kind}: expected {expected!r}, got {actual!r}")
    print(f"\nreplay: {len(rows)} runs, {failures} failures")
    return 1 if failures else 0


def run_walk_one(model, model_tag: str, scenario_path: str, profile: dict,
                 mode: str) -> dict:
    from session import run_pattern_walk
    client = spawn_mock(scenario_path)
    try:
        client.start()
        client.list_tools()
        scenario = load_scenario(scenario_path)
        result = run_pattern_walk(model, model_tag, client, scenario, profile, mode=mode)
        return result.to_row(model=model_tag, scenario=scenario.get("name", scenario_path),
                             iteration=0)
    finally:
        client.close()


def replay_walk(script_path: str, scenario_path: str) -> int:
    """Asserted multi-step building-walk replays (the S2 gametest analog)."""
    from session import run_pattern_walk
    with open(script_path, encoding="utf-8") as fh:
        walks = json.load(fh)
    scenario = load_scenario(scenario_path)
    failures = 0
    for walk in walks:
        mode = walk.get("mode", "native")
        model = FakeModel(walk["replies"], scenario)
        row = run_walk_one(model, mode, scenario_path, {}, mode)
        expect = walk["expect"]
        checks = [("outcome", row["outcome"], expect["outcome"]),
                  ("chain", row["tools_chain"], expect["chain"])]
        if "gate_rejects" in expect:
            checks.append(("gate_rejects", row["gate_rejects"], expect["gate_rejects"]))
        if "rejects" in expect:
            checks.append(("rejects", row["rejects"], expect["rejects"]))
        failed = [c for c in checks if c[1] != c[2]]
        status = "PASS" if not failed else "FAIL"
        if failed:
            failures += 1
        print(f"[{status}] {mode:<7} {walk['name']:<32} outcome={row['outcome']} "
              f"steps={row['steps']} rejects={row['rejects']}/{row['gate_rejects']} "
              f"task={row['final_task']}")
        for kind, actual, expected in failed:
            print(f"        ^ {kind}:\n          expected {expected!r}\n          got      {actual!r}")
    print(f"\nwalk replay: {len(walks)} walks, {failures} failures")
    return 1 if failures else 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--scenario", default="scenarios/stone_bricks_chest.json")
    ap.add_argument("--mode", default="all", choices=["all"] + MODES)
    ap.add_argument("--iterations", type=int, default=1)
    ap.add_argument("--endpoint")
    ap.add_argument("--model")
    ap.add_argument("--api-key-env", default="OPENROUTER_API_KEY")
    ap.add_argument("--cloud-config", help="path to siasworkshop-server.toml with an [llm] section")
    ap.add_argument("--replay", metavar="SCRIPT.json", help="scripted-reply plumbing self-test (no network)")
    ap.add_argument("--walk", action="store_true", help="run the S2 building pattern walk (multi-step) instead of one-shot decisions")
    ap.add_argument("--walk-mode", choices=["native", "prompt"], help="force the walk's model-facing mode")
    ap.add_argument("--out", help="metrics JSONL path (default: metrics-<ts>.jsonl)")
    args = ap.parse_args()

    scenario_path = os.path.abspath(args.scenario)
    if args.replay:
        if args.walk:
            return replay_walk(os.path.abspath(args.replay), scenario_path)
        return replay(os.path.abspath(args.replay), scenario_path)

    endpoint, model, api_key = args.endpoint, args.model, None
    timeout_s = 120
    if args.cloud_config:
        cfg = parse_llm_toml(os.path.abspath(args.cloud_config))
        endpoint = endpoint or cfg.get("endpoint")
        model = model or cfg.get("model")
        api_key = cfg.get("apiKey") or None
        timeout_s = int(cfg.get("timeoutSeconds", 120))
        print(f"cloud config: endpoint={endpoint} model={model} apiKey={'<present>' if api_key else '<absent>'}")
    api_key = api_key or os.environ.get(args.api_key_env) or None
    if not endpoint or not model:
        ap.error("--endpoint and --model are required (or --cloud-config)")
    profile = profile_for(model)
    profile = dict(profile, timeout_seconds=timeout_s)
    chat = OpenAiChat(endpoint, api_key, timeout_s)
    out_path = args.out or f"metrics-{time.strftime('%Y%m%d-%H%M%S')}.jsonl"
    rows: list[dict] = []
    if args.walk:
        walk_mode = args.walk_mode or ("native" if profile["mode"] == "native" else "prompt")
        with open(out_path, "a", encoding="utf-8") as out:
            for iteration in range(1, args.iterations + 1):
                row = run_walk_one(chat, model, scenario_path, profile, walk_mode)
                row["iteration"] = iteration
                rows.append(row)
                out.write(json.dumps(row) + "\n")
                out.flush()
                print(f"iter {iteration} walk({walk_mode}) -> outcome={row['outcome']} "
                      f"steps={row['steps']} chain={' > '.join(row['tools_chain'])} "
                      f"rejects={row['rejects']}/{row['gate_rejects']} "
                      f"total={row['total_latency_ms']}ms ctoks={row['completion_tokens']}")
        ok = sum(1 for r in rows if r["outcome"] == "done_converged")
        print(f"\nwalk summary: {ok}/{len(rows)} converged")
        print(f"metrics -> {out_path}")
        return 0
    modes = MODES if args.mode == "all" else [args.mode]
    rows: list[dict] = []
    with open(out_path, "a", encoding="utf-8") as out:
        for iteration in range(1, args.iterations + 1):
            for mode in modes:
                mode_profile = dict(profile)
                if args.mode == "all":
                    pass  # profile's render mode is fixed per model; --mode forces the surface for A/B
                if mode == "mcp-native" and profile["mode"] == "prompt":
                    print(f"note: {model} profile is prompt-mode; mcp-native leg tests wire compat anyway")
                row = run_one(mode, chat, model, scenario_path, mode_profile)
                row["iteration"] = iteration
                rows.append(row)
                out.write(json.dumps(row) + "\n")
                out.flush()
                print(f"iter {iteration} {mode:<11} -> decision={row['decision_outcome']:<12} "
                      f"tool={row['tool']} exec={row['exec_outcome']} "
                      f"model={row['model_latency_ms']}ms total={row['total_latency_ms']}ms "
                      f"ctoks={row['completion_tokens']}")
    summarize(rows)
    print(f"\nmetrics -> {out_path}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
