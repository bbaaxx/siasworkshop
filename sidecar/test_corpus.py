"""Corpus Q4 tests: Player-1 scoring units, JEVA Tier-0 metrics, and runner
integration with stub models (no network; mocks spawn as real subprocesses).

Run: python3 test_corpus.py
"""
from __future__ import annotations

import json
import math
import os
import shutil
import statistics
import tempfile

import corpus_score
import run_corpus
from mcp import spawn_mock
from model import ModelReply
from session import run_decision

HERE = os.path.dirname(os.path.abspath(__file__))
CORPUS = os.path.join(HERE, "corpus")

# The integration contract pins row counts, allow-lists, and board shapes, so it runs
# against the CANONICAL scenario set only — skeleton/live imports into corpus/ (e.g. the
# 2026-10-20 tap import, ground truth pending) must not perturb the expectations.
CANONICAL_SCENARIOS = (
    "missing_material_stone_vs_bricks",
    "null_board_cooling",
    "station_radius_edge",
    "unroutable_anchor_first_listed",
)

FAILURES = 0


def check(cond, label, detail=""):
    global FAILURES
    status = "PASS" if cond else "FAIL"
    if not cond:
        FAILURES += 1
    print(f"[{status}] {label}" + (f" — {detail}" if (detail and not cond) else ""))


def close(a, b) -> bool:
    return math.isclose(a, b, rel_tol=1e-9, abs_tol=1e-9)


# --- stub models (duck-type session.run_decision's model) ----------------------

def _board_keys(messages) -> list:
    """Task keys from the OPEN TASKS block of the last user message."""
    user = messages[-1]["content"]
    block = user.split("OPEN TASKS:\n", 1)[-1]
    keys = []
    for line in block.splitlines():
        if line.startswith("- "):
            keys.append(line[2:].split(" — ")[0])
        elif not line.strip():
            continue
        else:
            break
    return keys


class StubClaimFirst:
    """Position-biased model: always claims the first-listed board task."""

    def chat(self, model, messages, tools=None, temperature=0.3, max_tokens=1024):
        keys = _board_keys(messages)
        if keys:
            reply = {"tool": "claim", "args": {"task": keys[0]}}
        else:
            reply = {"tool": "rest", "args": {}}
        return ModelReply(ok=True, content=json.dumps(reply),
                          prompt_tokens=200, completion_tokens=10, latency_ms=1)


class StubGood:
    """Claims the first board key present in its compiled allow-list, else rests."""

    def __init__(self, allow):
        self.allow = set(allow)

    def chat(self, model, messages, tools=None, temperature=0.3, max_tokens=1024):
        keys = _board_keys(messages)
        chosen = next((k for k in keys if k in self.allow), None)
        reply = {"tool": "claim", "args": {"task": chosen}} if chosen else {"tool": "rest", "args": {}}
        return ModelReply(ok=True, content=json.dumps(reply),
                          prompt_tokens=200, completion_tokens=10, latency_ms=1)


class StubCoverage(StubClaimFirst):
    """Tier-0: position-biased claim plus a coverage answer."""

    def chat(self, model, messages, tools=None, temperature=0.3, max_tokens=1024):
        keys = _board_keys(messages)
        reply = {"tool": "claim", "args": {"task": keys[0] if keys else ""},
                 "coverage": {"any_claimable": True, "confidence": 70}}
        if not keys:
            reply = {"tool": "rest", "args": {},
                     "coverage": {"any_claimable": False, "confidence": 80}}
        return ModelReply(ok=True, content=json.dumps(reply),
                          prompt_tokens=200, completion_tokens=10, latency_ms=1)


class StubNativeCoverage:
    """Native-mode Tier-0: claims with the coverage args riding the tool call."""

    def __init__(self, key, with_coverage=True):
        self.key = key
        self.with_coverage = with_coverage
        self.tools = None

    def chat(self, model, messages, tools=None, temperature=0.3, max_tokens=1024):
        self.tools = tools
        args = {"task": self.key}
        if self.with_coverage:
            args["any_claimable"] = False
            args["confidence"] = 66
        return ModelReply(ok=True, content=None,
                          tool_calls=[{"id": "c1", "name": "claim", "args": args}],
                          prompt_tokens=200, completion_tokens=10, latency_ms=1)


# --- units: classify + score_run ----------------------------------------------

def test_score_run() -> None:
    spec = {"forbid_claim_keys": ["build:mazons:bad"], "null_ground_truth": False}
    null_spec = {"forbid_claim_keys": ["comfort:light:x"], "null_ground_truth": True}

    def score(assert_spec, tool, args, outcome, exec_out, err=None, toks=10, budget=512):
        classified = corpus_score.classify(tool, args, outcome, exec_out, err)
        return classified, corpus_score.score_run(assert_spec, classified, toks, budget)

    cls, s = score(spec, "claim", {"task": "comfort:light:ok"}, "ok", "exec_ok")
    check(cls == ("claim", "comfort:light:ok", None) and s["reward"] == 1 and s["correct"]
          and not s["failure_class_hit"], "score: correct claim +1")

    cls, s = score(spec, "claim", {"task": "build:mazons:bad"}, "ok", "exec_ok")
    check(cls == ("claim", "build:mazons:bad", None) and s["reward"] == 0
          and s["failure_class_hit"] and not s["correct"],
          "score: forbidden claim 0 + failure_class_hit")

    cls, s = score(spec, "claim", {"task": "build:mazons:nope"}, "ok",
                   "exec_reject:unknown or claimed task key 'build:mazons:nope'")
    check(cls == ("claim", "build:mazons:nope", "exec_reject") and s["reward"] == 0
          and not s["correct"] and not s["failure_class_hit"],
          "score: exec_reject claim 0, class stays claim")

    cls, s = score(null_spec, "rest", {}, "ok", "exec_ok")
    check(cls == ("rest", None, None) and s["reward"] == 1 and s["correct"],
          "score: rest on null board +1")

    cls, s = score(spec, "rest", {}, "ok", "exec_ok")
    check(s["reward"] == 0 and not s["correct"], "score: missed-opportunity rest 0")

    cls, s = score(spec, "say", {"player": "Dev", "msg": "hi"}, "ok", "exec_ok")
    check(cls == ("say", None, None) and s["reward"] == 0 and not s["correct"],
          "score: say 0")

    for outcome, err, label in (("malformed_json", None, "malformed_json"),
                                ("unknown_tool", None, "unknown_tool"),
                                ("bad_args", "missing required arg 'msg'", "bad_args"),
                                ("model_error:timeout", None, "model_error")):
        cls, s = score(spec, None if outcome == "malformed_json" else "say",
                       None, outcome, None, err=err)
        forced = "wait_fallback" if corpus_score.is_invalid(cls) else cls[0]
        check(s["reward"] == -1 and not s["correct"] and forced == "wait_fallback"
              and cls[2] is not None, f"score: invalid ({label}) -1 -> wait_fallback")

    cls, s = score(spec, None, None, "malformed_json", None, toks=612)
    check(close(s["reward"], -1.5), "score: invalid still pays reasoning penalty", str(s["reward"]))

    _, s = score(spec, "claim", {"task": "comfort:light:ok"}, "ok", "exec_ok", toks=612)
    check(close(s["reward"], 0.5), "score: reasoning penalty 100 over -> 0.5", str(s["reward"]))
    _, s = score(spec, "claim", {"task": "comfort:light:ok"}, "ok", "exec_ok", toks=762)
    check(close(s["reward"], 0.0), "score: reasoning penalty cap at 1.0", str(s["reward"]))
    _, s = score(spec, "claim", {"task": "comfort:light:ok"}, "ok", "exec_ok", toks=1612)
    check(close(s["reward"], 0.0), "score: penalty stays capped", str(s["reward"]))
    _, s = score(spec, "claim", {"task": "comfort:light:ok"}, "ok", "exec_ok", toks=None)
    check(close(s["reward"], 1.0), "score: unknown tokens -> no penalty")
    _, s = score(spec, "claim", {"task": "comfort:light:ok"}, "ok", "exec_ok",
                 toks=356, budget=256)
    check(close(s["reward"], 0.5), "score: budget override", str(s["reward"]))

    cls, s = score(spec, "claim", {}, "ok", "exec_ok")
    check(cls == ("claim", None, None) and s["reward"] == 0 and not s["correct"],
          "score: keyless claim unverifiable 0")


# --- units: jeva_metrics -------------------------------------------------------

def test_jeva_metrics() -> None:
    spec = {"forbid_claim_keys": ["A"], "null_ground_truth": False}
    rows = []
    for trial in (1, 2):
        for order, chosen, correct in ((("A", "B"), "A", False), (("B", "A"), "B", True)):
            rows.append({"trial": trial, "permutation": 1 if order[0] == "A" else 2,
                         "decision_class": "claim", "chosen_key": chosen,
                         "correct": correct, "board_order": list(order),
                         "coverage_any_claimable": True})
    jm = corpus_score.jeva_metrics(rows, spec)
    check(jm["flip_rate"] == 1.0 and jm["flip_trials"] == 2, "jeva: position-biased set flips",
          str(jm))
    check(jm["all_order_accuracy"] == 0.5, "jeva: all_order_accuracy", str(jm["all_order_accuracy"]))
    check(jm["per_order_accuracy"] == {"A > B": 0.0, "B > A": 1.0},
          "jeva: per-order accuracy", str(jm["per_order_accuracy"]))
    nm = jm["null_matrix"]
    check(nm == {"tp": 4, "fp": 0, "tn": 0, "fn": 0, "tpr": 1.0, "fpr": None},
          "jeva: null matrix (claimable board)", str(nm))

    invariant = [dict(r, chosen_key="B", correct=True) for r in rows]
    jm = corpus_score.jeva_metrics(invariant, spec)
    check(jm["flip_rate"] == 0.0 and jm["all_order_accuracy"] == 1.0,
          "jeva: invariant set does not flip")

    # invalid runs excluded from flip grouping; null matrix skips None coverage
    mixed = [{"trial": 1, "decision_class": "wait_fallback", "chosen_key": None,
              "correct": False, "board_order": ["A", "B"], "coverage_any_claimable": None},
             {"trial": 1, "decision_class": "claim", "chosen_key": "B", "correct": True,
              "board_order": ["B", "A"], "coverage_any_claimable": False}]
    jm = corpus_score.jeva_metrics(mixed, spec)
    check(jm["eligible_trials"] == 0 and jm["flip_rate"] == 0.0,
          "jeva: invalid excluded, no eligible trial")
    nm = corpus_score.jeva_metrics(mixed, {"forbid_claim_keys": [], "null_ground_truth": True})["null_matrix"]
    check(nm["tn"] == 1 and nm["fp"] == 0 and nm["tpr"] is None and nm["fpr"] == 0.0,
          "jeva: null-board matrix, None coverage skipped", str(nm))


# --- units: summarize ----------------------------------------------------------

def test_summarize() -> None:
    rows = [{"trial": 1, "correct": True, "reward": 1.0},
            {"trial": 1, "correct": False, "reward": 0.0},
            {"trial": 2, "correct": True, "reward": 0.5},
            {"trial": 2, "correct": True, "reward": 1.0}]
    a = corpus_score.summarize(rows)
    b = corpus_score.summarize(rows)
    check(a["ci_low"] == b["ci_low"] and a["ci_high"] == b["ci_high"],
          "summarize: seeded bootstrap CI deterministic")
    check(a["n"] == 4 and close(a["mean_reward"], statistics.mean([1.0, 0.0, 0.5, 1.0])),
          "summarize: n and mean")
    check(a["trials_success_ge1"] == 2 and a["trials_success_ge2"] == 1
          and a["trials_success_ge4"] == 0, "summarize: coverage counts ge1/2/4")
    check(a["covered_ge1"] and a["covered_ge2"] and not a["covered_ge4"],
          "summarize: coverage booleans")
    check(a["ci_low"] <= a["mean_reward"] <= a["ci_high"], "summarize: CI brackets mean")


# --- integration: run_corpus with stubs ----------------------------------------

def drive(model, out_path, extra=(), corpus=None):
    argv = ["--corpus", corpus or CORPUS, "--trials", "2", "--permutations", "2",
            "--mode", "mcp-prompt", "--replay", os.path.join(HERE, "replay", "script.json"),
            "--tag", "itest", "--out", out_path, "--quiet"] + list(extra)
    args = run_corpus.build_parser().parse_args(argv)
    return run_corpus.run(args, model=model)


def rows_for(rows, scenario):
    return [r for r in rows if r["scenario"] == scenario]


CONTRACT_KEYS = {"scenario", "trial", "permutation", "seed", "mode", "model", "tier0",
                 "decision_class", "chosen_key", "correct", "failure_class_hit",
                 "invalid_reason", "coverage_any_claimable", "coverage_confidence",
                 "completion_tokens", "prompt_tokens", "latency_ms", "reward",
                 "board_order"}


def test_integration() -> None:
    out_dir = tempfile.mkdtemp(prefix="siasworkshop-corpus-test-")
    canon_dir = os.path.join(out_dir, "canon-corpus")
    os.mkdir(canon_dir)
    for name in CANONICAL_SCENARIOS:
        shutil.copy(os.path.join(CORPUS, name + ".json"), canon_dir)
    bad_out = os.path.join(out_dir, "bad.jsonl")
    bad = drive(StubClaimFirst(), bad_out, corpus=canon_dir)
    check(len(bad) == 16, "integration: 4 scenarios x 2 trials x 2 perms", str(len(bad)))
    check(all(set(r) == CONTRACT_KEYS for r in bad), "integration: row schema contract")
    with open(bad_out, encoding="utf-8") as fh:
        file_rows = [json.loads(line) for line in fh if line.strip()]
    check(len(file_rows) == 16, "integration: rows appended to --out file")

    un = rows_for(bad, "unroutable_anchor_first_listed")
    build_key = "build:mazons:-20,86,0"
    by_trial = {}
    for r in un:
        by_trial.setdefault(r["trial"], []).append(r)
    check(all(any(r["failure_class_hit"] for r in g) for g in by_trial.values()),
          "integration: StubClaimFirst hits the failure class on every trial")
    check(len({r["chosen_key"] for r in un}) == 2,
          "integration: StubClaimFirst flips chosen_key across permutations",
          str({r["chosen_key"] for r in un}))
    check(any(r["correct"] for r in un) and all(r["decision_class"] == "claim" for r in un),
          "integration: position bias claims, sometimes the permitted task")
    check(all(r["reward"] == 0 for r in un if r["chosen_key"] == build_key),
          "integration: forbidden claims score 0")
    check(all(r["coverage_any_claimable"] is None for r in bad),
          "integration: non-tier0 records coverage None (not invalid)")
    check(all(r["seed"] for r in un), "integration: real seed recorded")
    first = un[0]
    check(first["seed"] == 42 + first["trial"] * 1000 + first["permutation"],
          "integration: seed = scenario.seed + trial*1000 + perm")
    check(all(isinstance(r["board_order"], list) and len(r["board_order"]) == 2 for r in un),
          "integration: board_order recorded (fallback to effective scenario order)")
    check(len({tuple(r["board_order"]) for r in un}) >= 2,
          "integration: board_order varies with the permutation")

    null_rows = rows_for(bad, "null_board_cooling")
    cooling = "comfort:light:2f6a9c1d-8e3b-4c5a-b7d1-4a9e6c3f5b28"
    check(all(r["decision_class"] == "claim" and r["chosen_key"] == cooling for r in null_rows),
          "integration: StubClaimFirst claims the cooling task")
    check(all(r["invalid_reason"] == "exec_reject" and r["reward"] == 0
              and not r["correct"] for r in null_rows),
          "integration: cooling claim lands exec_reject -> wrong")

    good_out = os.path.join(out_dir, "good.jsonl")
    allow = {"comfort:light:9a41f0c2-7d18-4a5e-b3f9-2c8e6d1f4a77",
             "comfort:light:6b3d9e10-2c4f-4a7b-9e5a-1d7c8f2a6b45",
             "build:mazons:51b0"}
    good = drive(StubGood(allow), good_out, corpus=canon_dir)
    check(all(r["correct"] and r["reward"] == 1.0 for r in good),
          "integration: StubGood scores +1 everywhere, misses nothing")
    edge = rows_for(good, "station_radius_edge")
    check(all(r["chosen_key"] == "build:mazons:51b0" for r in edge),
          "integration: StubGood claims the satisfiable 51b0")
    check(all(r["decision_class"] == "rest" for r in rows_for(good, "null_board_cooling")),
          "integration: StubGood rests on the null board")

    # Tier-0 decide path: seeded session-side shuffle + coverage answer recorded
    t0_out = os.path.join(out_dir, "tier0.jsonl")
    t0 = drive(StubCoverage(), t0_out,
               extra=["--tier0", "--corpus", os.path.join(CORPUS, "unroutable_anchor_first_listed.json")])
    check(len(t0) == 4 and all(r["tier0"] for r in t0), "tier0: 2x2 runs flagged")
    check(all(r["coverage_any_claimable"] is True and r["coverage_confidence"] == 70.0
              for r in t0), "tier0: coverage answer parsed from the JSON contract")
    check(all(len(r["board_order"]) == 2 for r in t0),
          "tier0: board_order recorded from the shuffled lines")
    check(all(set(r["board_order"]) == {build_key, "comfort:light:9a41f0c2-7d18-4a5e-b3f9-2c8e6d1f4a77"}
              for r in t0), "tier0: board_order keys match the scenario tasks")


def test_native_tier0() -> None:
    key = "comfort:light:9a41f0c2-7d18-4a5e-b3f9-2c8e6d1f4a77"
    scenario_path = os.path.join(CORPUS, "unroutable_anchor_first_listed.json")
    client = spawn_mock(scenario_path)
    try:
        client.start()
        stub = StubNativeCoverage(key)
        decision = run_decision("mcp-native", stub, "native-tier0", client, {},
                                tier0=True, rng=None)
    finally:
        client.close()
    schemas = {t["function"]["name"]: t["function"]["parameters"] for t in (stub.tools or [])}
    check(set(schemas) == {"claim", "rest", "say"}, "native tier0: effect-only tool set")
    check(all("any_claimable" in p.get("required", []) for p in schemas.values()),
          "native tier0: any_claimable required on every effect tool")
    check(all("confidence" in p["properties"] for p in schemas.values()),
          "native tier0: confidence optional on every effect tool")
    check(decision.coverage_any_claimable is False and decision.coverage_confidence == 66.0,
          "native tier0: coverage args recorded")
    check(decision.decision_outcome == "ok" and decision.exec_outcome == "exec_ok"
          and decision.args == {"task": key},
          "native tier0: coverage args stripped before check_args, claim executed")

    client = spawn_mock(scenario_path)
    try:
        client.start()
        stub = StubNativeCoverage(key, with_coverage=False)
        decision = run_decision("mcp-native", stub, "native-tier0", client, {},
                                tier0=True, rng=None)
    finally:
        client.close()
    check(decision.coverage_any_claimable is None and decision.decision_outcome == "ok",
          "native tier0: missing coverage tolerated (recorded None, not invalid)")


def main() -> int:
    test_score_run()
    test_jeva_metrics()
    test_summarize()
    test_integration()
    test_native_tier0()
    print(f"\ncorpus tests: {'PASS' if FAILURES == 0 else f'{FAILURES} failures'}")
    return 1 if FAILURES else 0


if __name__ == "__main__":
    raise SystemExit(main())
