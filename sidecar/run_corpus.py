"""Corpus runner (Q4): seeded trials × board permutations of corpus/*.json scenarios
against any model surface (v1 | mcp-prompt | mcp-native), scored Player-1 + JEVA Tier-0.

Examples:
  # deterministic stub/replay run (no network), Tier-0 decide path:
  python3 run_corpus.py --corpus corpus --replay replay/script.json --tier0 --tag t0

  # live local leg:
  python3 run_corpus.py --corpus corpus --endpoint http://127.0.0.1:1234/v1 \
      --model prism-ml/bonsai-27b --trials 4 --permutations 2

  # cloud leg (TOML like run_spike; apiKey never printed):
  python3 run_corpus.py --corpus corpus --cloud-config siasworkshop-server.toml --tag cloud

Seeding: seed = scenario.seed + trial*1000 + permutation (the runner owns it and writes
it into every row). Without --tier0 and perms>1 the scenario file itself is pre-permuted
into a temp copy (session sees that order); with --tier0 the session-side seeded shuffle
of the board lines is the order variation.
"""
from __future__ import annotations

import argparse
import copy
import glob
import json
import os
import random
import shutil
import sys
import tempfile
import time

import corpus_score
from mcp import spawn_mock
from model import FakeModel, OpenAiChat, profile_for
from run_spike import parse_llm_toml
from session import run_decision

MODES = ["v1", "mcp-prompt", "mcp-native"]


def discover_scenarios(path: str) -> list:
    if os.path.isdir(path):
        return sorted(glob.glob(os.path.join(path, "*.json")))
    return [path]


def build_chat(args) -> tuple:
    """Endpoint leg, mirroring run_spike: cloud TOML fills gaps over --endpoint/--model."""
    endpoint, model, api_key = args.endpoint, args.model, None
    timeout_s = 120
    if args.cloud_config:
        cfg = parse_llm_toml(os.path.abspath(args.cloud_config))
        endpoint = endpoint or cfg.get("endpoint")
        model = model or cfg.get("model")
        api_key = cfg.get("apiKey") or None
        timeout_s = int(cfg.get("timeoutSeconds", 120))
        print(f"cloud config: endpoint={endpoint} model={model} "
              f"apiKey={'<present>' if api_key else '<absent>'}")
    api_key = api_key or os.environ.get(args.api_key_env) or None
    if not endpoint or not model:
        raise SystemExit("--endpoint and --model are required (or --cloud-config)")
    profile = dict(profile_for(model), timeout_seconds=timeout_s)
    return OpenAiChat(endpoint, api_key, timeout_s), profile


def run(args, model=None, model_tag: str | None = None) -> list:
    """Run the corpus; returns the metric rows. `model` overrides model construction
    (tests pass stubs); otherwise --replay or the endpoint legs build one per run."""
    scenario_paths = discover_scenarios(args.corpus)
    if not scenario_paths:
        print(f"no corpus scenarios found at {args.corpus}")
        return []
    replay_script = None
    chat = None
    profile: dict = {}
    if model is None:
        if args.replay:
            with open(args.replay, encoding="utf-8") as fh:
                script = json.load(fh)
            replay_script = [s for s in script if not s.get("modes") or args.mode in s["modes"]]
        else:
            chat, profile = build_chat(args)
    if model_tag is None:
        # replay legs tag the mode (FakeModel filters script entries by it, as in
        # run_spike.replay); live legs tag the model name.
        model_tag = args.model or (args.mode if args.replay else "model")
    out_path = args.out or os.path.join(
        "metrics", f"corpus-{args.tag or args.mode}-{time.strftime('%Y%m%d-%H%M%S')}.jsonl")
    out_dir = os.path.dirname(os.path.abspath(out_path))
    if out_dir:
        os.makedirs(out_dir, exist_ok=True)

    all_rows: list = []
    assert_specs: dict = {}
    with open(out_path, "a", encoding="utf-8") as out:
        for path in scenario_paths:
            with open(path, encoding="utf-8") as fh:
                scenario = json.load(fh)
            name = scenario.get("name", path)
            assert_spec = scenario.get("assert") or {}
            assert_specs[name] = assert_spec
            rows = run_scenario(args, path, scenario, profile, model, model_tag,
                                replay_script, chat, assert_spec, out)
            all_rows.extend(rows)
            if not args.quiet:
                for row in rows:
                    print(f"{row['scenario']} t{row['trial']}.{row['permutation']} "
                          f"seed={row['seed']} -> {row['decision_class']}:{row['chosen_key']} "
                          f"reward={row['reward']:+.2f} cov={row['coverage_any_claimable']} "
                          f"board={' > '.join(row['board_order']) or '<empty>'}")
    print_summary(all_rows, args, assert_specs)
    if not args.quiet:
        print(f"\nmetrics -> {out_path}")
    return all_rows


def run_scenario(args, path: str, scenario: dict, profile: dict, model, model_tag: str,
                 replay_script, chat, assert_spec: dict, out) -> list:
    trials = args.trials or scenario.get("trials") or 8
    perms = args.permutations or scenario.get("permutations") or 1
    budget = profile.get("budget", 512) if hasattr(profile, "get") else 512
    rows = []
    for trial in range(1, trials + 1):
        for perm in range(1, perms + 1):
            seed = scenario.get("seed", 0) + trial * 1000 + perm
            rng = random.Random(seed)
            effective, eff_path, tmpdir = scenario, path, None
            if not args.tier0 and perms > 1:
                effective = copy.deepcopy(scenario)
                rng.shuffle(effective.get("open_tasks", []))
                tmpdir = tempfile.mkdtemp(prefix="siasworkshop-corpus-")
                eff_path = os.path.join(tmpdir, "scenario.json")
                with open(eff_path, "w", encoding="utf-8") as fh:
                    json.dump(effective, fh)
            try:
                client = spawn_mock(eff_path)
                try:
                    client.start()
                    run_model = model if model is not None else (
                        FakeModel(replay_script, effective) if replay_script is not None else chat)
                    decision = run_decision(args.mode, run_model, model_tag, client, profile,
                                            tier0=args.tier0, rng=rng)
                finally:
                    client.close()
            finally:
                if tmpdir:
                    shutil.rmtree(tmpdir, ignore_errors=True)
            row = build_row(scenario, effective, trial, perm, seed, args, model_tag,
                            decision, assert_spec, budget)
            rows.append(row)
            out.write(json.dumps(row) + "\n")
            out.flush()
    return rows


def build_row(scenario, effective, trial, perm, seed, args, model_tag, decision,
              assert_spec, budget) -> dict:
    classified = corpus_score.classify(decision.tool, decision.args,
                                       decision.decision_outcome, decision.exec_outcome,
                                       decision.model_error)
    scored = corpus_score.score_run(assert_spec, classified, decision.completion_tokens, budget)
    decision_class = ("wait_fallback" if corpus_score.is_invalid(classified)
                      else classified[0])
    board_order = decision.board_order or [t["key"] for t in effective.get("open_tasks", [])]
    return {
        "scenario": scenario.get("name", "?"),
        "trial": trial,
        "permutation": perm,
        "seed": seed,
        "mode": args.mode,
        "model": model_tag,
        "tier0": bool(args.tier0),
        "decision_class": decision_class,
        "chosen_key": classified[1],
        "correct": scored["correct"],
        "failure_class_hit": scored["failure_class_hit"],
        "invalid_reason": classified[2],
        "coverage_any_claimable": decision.coverage_any_claimable,
        "coverage_confidence": decision.coverage_confidence,
        "completion_tokens": decision.completion_tokens,
        "prompt_tokens": decision.prompt_tokens,
        "latency_ms": decision.total_latency_ms,
        "reward": scored["reward"],
        "board_order": board_order,
    }


def print_summary(rows: list, args, assert_specs: dict) -> None:
    groups: dict = {}
    for row in rows:
        groups.setdefault((row["scenario"], row["mode"]), []).append(row)
    print("\n=== corpus summary (scenario × mode) ===")
    for (name, mode), group in sorted(groups.items()):
        agg = corpus_score.summarize(group)
        print(f"{name:<36} {mode:<11} n={agg['n']:>3} "
              f"mean_reward={agg['mean_reward']:+.3f} "
              f"CI[{agg['ci_low']:+.3f},{agg['ci_high']:+.3f}] "
              f"cov>=1/2/4 {agg['trials_success_ge1']}/{agg['trials_success_ge2']}/"
              f"{agg['trials_success_ge4']} of {agg['trials']} trials")
        perms = args.permutations
        if (perms and perms > 1) or args.tier0:
            jm = corpus_score.jeva_metrics(group, assert_specs.get(name, {}))
            nm = jm["null_matrix"]
            print(f"{'':<36} {'':<11} flip_rate={jm['flip_rate']:.2f} "
                  f"({jm['flip_trials']}/{jm['eligible_trials']} trials) "
                  f"all_order_acc={jm['all_order_accuracy']:.2f} "
                  f"per_order={json.dumps(jm['per_order_accuracy'])}")
            print(f"{'':<36} {'':<11} null_matrix tp={nm['tp']} fp={nm['fp']} tn={nm['tn']} "
                  f"fn={nm['fn']} tpr={nm['tpr']} fpr={nm['fpr']}")


def build_parser() -> argparse.ArgumentParser:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--corpus", required=True,
                    help="corpus scenario .json file or directory of them")
    ap.add_argument("--trials", type=int, default=None, help="override scenario trials")
    ap.add_argument("--permutations", type=int, default=None,
                    help="override scenario permutations")
    ap.add_argument("--mode", default="mcp-prompt", choices=MODES)
    src = ap.add_mutually_exclusive_group(required=True)
    src.add_argument("--replay", metavar="SCRIPT.json",
                     help="scripted-reply FakeModel per run (no network)")
    src.add_argument("--endpoint", help="OpenAI-compatible base URL")
    ap.add_argument("--model")
    ap.add_argument("--api-key-env", default="OPENROUTER_API_KEY")
    ap.add_argument("--cloud-config", help="siasworkshop-server.toml with an [llm] section")
    ap.add_argument("--tier0", action="store_true",
                    help="JEVA Tier-0 decide path: seeded board shuffle + coverage answer")
    ap.add_argument("--tag", help="run-batch tag (default: mode)")
    ap.add_argument("--out", help="metrics JSONL path (default: metrics/corpus-<tag>-<ts>.jsonl)")
    ap.add_argument("--quiet", action="store_true", help="suppress per-run lines")
    return ap


def main(argv=None) -> int:
    args = build_parser().parse_args(argv)
    run(args)
    return 0


if __name__ == "__main__":
    sys.exit(main())
