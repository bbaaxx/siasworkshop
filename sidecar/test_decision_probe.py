"""Tier-1 SystemOne probe tests: an in-thread stub HTTP endpoint speaking
/v1/systemone from a scripted per-request policy (keyed on the criteria order it
receives), corpus scenarios driven end-to-end through probe_decision (real urllib
transport, tmp --out), plus null_auc units and failure legs. No network beyond
127.0.0.1; nothing written into the repo's metrics/.

Run: python3 test_decision_probe.py
"""
from __future__ import annotations

import copy
import json
import os
import random
import shutil
import socket
import tempfile
import threading
from http.server import BaseHTTPRequestHandler, HTTPServer

import corpus_score
import probe_decision

HERE = os.path.dirname(os.path.abspath(__file__))
CORPUS = os.path.join(HERE, "corpus")
UNROUTABLE = os.path.join(CORPUS, "unroutable_anchor_first_listed.json")
NULL_BOARD = os.path.join(CORPUS, "null_board_cooling.json")

BUILD = "build:mazons:-20,86,0"
COMFORT = "comfort:light:9a41f0c2-7d18-4a5e-b3f9-2c8e6d1f4a77"
COOLING = "comfort:light:2f6a9c1d-8e3b-4c5a-b7d1-4a9e6c3f5b28"

FAILURES = 0
REQUESTS: list = []


def check(cond, label, detail=""):
    global FAILURES
    status = "PASS" if cond else "FAIL"
    if not cond:
        FAILURES += 1
    print(f"[{status}] {label}" + (f" — {detail}" if (detail and not cond) else ""))


def expected_order(scenario: dict, trial: int, perm: int, perms: int) -> list:
    """The probe's seeding contract, recomputed independently (run_corpus scheme)."""
    seed = scenario.get("seed", 0) + trial * 1000 + perm
    rng = random.Random(seed)
    tasks = copy.deepcopy(scenario.get("open_tasks") or [])
    if perms > 1:
        rng.shuffle(tasks)
    return [t["key"] for t in tasks]


def load_scenario(path: str) -> dict:
    with open(path, encoding="utf-8") as fh:
        return json.load(fh)


# --- stub SystemOne endpoint -------------------------------------------------------

def body_for(claim_probs, choice, p_yes):
    """The DOCUMENTED SDK shape (per the serving setup's SDK docs)."""
    return {"answers": {"claim": {"probabilities": claim_probs, "choice": choice,
                                  "confidence": 0.9},
                        "coverage": {"p_yes": p_yes}},
            "usage": {"prompt_tokens": 100, "completion_tokens": 5}}


def good_policy(payload):
    """Scripted GOOD policy: claims the permitted key regardless of order; P(yes)=0.9
    on the claimable board, 0.1 on the null board."""
    criteria = payload["questions"]["claim"]["criteria"]
    if set(criteria) == {COOLING}:
        return body_for({COOLING: 1.0}, COOLING, 0.1)
    return body_for({k: (0.95 if k == COMFORT else 0.05) for k in criteria}, COMFORT, 0.9)


def first_option_policy(payload):
    """Position-biased: claims the FIRST option in whatever order it receives."""
    criteria = payload["questions"]["claim"]["criteria"]
    first = next(iter(criteria))
    return body_for({k: (0.9 if k == first else 0.1) for k in criteria}, first, 0.9)


class StubHandler(BaseHTTPRequestHandler):
    policy = staticmethod(good_policy)

    def do_POST(self):
        if self.path != "/v1/systemone":
            self._reply(404, {"error": f"unknown path {self.path}"})
            return
        length = int(self.headers.get("Content-Length") or 0)
        payload = json.loads(self.rfile.read(length) or b"{}")
        REQUESTS.append(payload)
        self._reply(200, StubHandler.policy(payload))

    def _reply(self, code, obj):
        data = json.dumps(obj).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, *args):
        pass


def start_stub(policy) -> tuple:
    REQUESTS.clear()
    StubHandler.policy = staticmethod(policy)
    httpd = HTTPServer(("127.0.0.1", 0), StubHandler)
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    return httpd, f"http://127.0.0.1:{httpd.server_address[1]}"


def make_corpus_dir(tmp, *paths) -> str:
    corpus_dir = os.path.join(tmp, "corpus")
    os.makedirs(corpus_dir)
    for path in paths:
        shutil.copy(path, corpus_dir)
    return corpus_dir


def read_rows(path: str) -> list:
    with open(path, encoding="utf-8") as fh:
        return [json.loads(line) for line in fh if line.strip()]


# --- case 1: end-to-end GOOD policy ------------------------------------------------

def test_end_to_end() -> None:
    tmp = tempfile.mkdtemp(prefix="siasworkshop-probe-test-")
    httpd, endpoint = start_stub(good_policy)
    corpus_dir = make_corpus_dir(tmp, UNROUTABLE, NULL_BOARD)
    out_path = os.path.join(tmp, "rows.jsonl")
    argv = ["--corpus", corpus_dir, "--endpoint", endpoint, "--tag", "itest",
            "--trials", "2", "--permutations", "2", "--out", out_path, "--quiet"]
    rc = probe_decision.main(argv)
    check(rc == 0, "e2e: main exits 0")
    rows = read_rows(out_path)
    check(len(rows) == 8, "e2e: 2 scenarios x 2 trials x 2 perms", str(len(rows)))
    check(all(set(r) == set(probe_decision.ROW_KEYS) for r in rows),
          "e2e: row schema contract (19 corpus keys + option_probs + p_yes)")
    check(all(r["mode"] == "systemone:itest" and r["model"] == endpoint
              and r["tier0"] is False for r in rows),
          "e2e: mode is systemone:<tag>, model is endpoint, tier0 false")

    un = [r for r in rows if r["scenario"] == "unroutable_anchor_first_listed"]
    check(all(r["decision_class"] == "claim" and r["chosen_key"] == COMFORT for r in un),
          "e2e: GOOD policy claims the permitted task on the unroutable board")
    nb = [r for r in rows if r["scenario"] == "null_board_cooling"]
    check(all(r["decision_class"] == "rest" and r["chosen_key"] is None for r in nb),
          "e2e: P(yes)=0.1 < 0.5 -> rest on the null board")
    check(all(r["correct"] and r["reward"] == 1.0 for r in rows),
          "e2e: all rows correct with Player-1 reward +1")
    check(all(r["coverage_any_claimable"] is False and r["p_yes"] == 0.1 for r in nb),
          "e2e: null board -> coverage_any_claimable False (P(yes) < 0.5)")
    check(all(r["coverage_any_claimable"] is True and r["p_yes"] == 0.9 for r in un),
          "e2e: claimable board -> coverage_any_claimable True")
    check(all(r["coverage_confidence"] == r["p_yes"] for r in rows),
          "e2e: coverage_confidence mirrors p_yes (the soft score)")
    check(all(r["completion_tokens"] == 5 and r["prompt_tokens"] == 100 for r in rows),
          "e2e: usage tokens recorded")
    check(all(isinstance(r["latency_ms"], (int, float)) and r["latency_ms"] >= 0
              for r in rows), "e2e: client-measured latency recorded")
    check(all(r["invalid_reason"] is None and not r["failure_class_hit"] for r in rows),
          "e2e: valid rows carry no invalid_reason / failure hit")
    check(all(r["option_probs"] == {COMFORT: 0.95, BUILD: 0.05} for r in un),
          "e2e: full option_probs dict recorded")

    scen_un = load_scenario(UNROUTABLE)
    check(all(r["board_order"] == expected_order(scen_un, r["trial"], r["permutation"], 2)
              for r in un),
          "e2e: board_order matches the seeded scheme per (trial, perm)")
    check(len({tuple(r["board_order"]) for r in un}) >= 2,
          "e2e: board_order varies with the permutation")
    check(all(r["seed"] == scen_un.get("seed", 0) + r["trial"] * 1000 + r["permutation"]
              for r in un), "e2e: seed = scenario.seed + trial*1000 + perm")

    check(len(REQUESTS) == 8, "e2e: one POST per run", str(len(REQUESTS)))
    for req, row in zip(REQUESTS, rows):
        criteria = req["questions"]["claim"]["criteria"]
        if list(criteria.keys()) != row["board_order"]:
            check(False, "e2e: criteria sent in board_order (permuted) order")
            break
    else:
        check(True, "e2e: criteria sent in board_order (permuted) order")
    check(all(req["questions"]["claim"]["type"] == "choice"
              and req["questions"]["coverage"]["type"] == "noul" for req in REQUESTS),
          "e2e: claim is a choice question, coverage is a noul question")
    check(all(req["questions"]["claim"]["instructions"]
              == "Which listed open task should this agent claim right now?"
              and req["questions"]["coverage"]["instructions"]
              == "Is any listed open task claimable by this agent right now?"
              for req in REQUESTS), "e2e: instruction strings")
    first = REQUESTS[0]
    state, scen = first["state"], load_scenario(os.path.join(corpus_dir, sorted(os.listdir(corpus_dir))[0]))
    board_lines = "\n".join("- " + t["key"] + " — " + t["detail"]
                            for t in scen["open_tasks"])
    check(state == scen["observation"] + "\n\nOPEN TASKS:\n" + board_lines,
          "e2e: state text is observation + OPEN TASKS board lines (em dash)")
    check(all(set(q) == {"type", "instructions", "criteria"}
              if q["type"] == "choice" else set(q) == {"type", "instructions"}
              for req in REQUESTS for q in req["questions"].values()),
          "e2e: no position identifiers in questions (criteria carry key: detail only)")
    httpd.shutdown()
    httpd.server_close()


# --- case 2: option-order caveat ----------------------------------------------------

def test_option_order_caveat() -> None:
    tmp = tempfile.mkdtemp(prefix="siasworkshop-probe-test-")
    httpd, endpoint = start_stub(first_option_policy)
    out_path = os.path.join(tmp, "rows.jsonl")
    argv = ["--corpus", UNROUTABLE, "--endpoint", endpoint, "--tag", "ord",
            "--trials", "2", "--permutations", "2", "--out", out_path, "--quiet"]
    args = probe_decision.build_parser().parse_args(argv)
    rows = probe_decision.run(args)
    check(len(rows) == 4, "order: 2 trials x 2 perms", str(len(rows)))
    check(all(r["decision_class"] == "claim" and r["coverage_any_claimable"] is True
              for r in rows), "order: always claims (P(yes)=0.9)")
    for req, row in zip(REQUESTS, rows):
        first = next(iter(req["questions"]["claim"]["criteria"]))
        if row["chosen_key"] != first:
            check(False, "order: chosen_key follows the received option order")
            break
    else:
        check(True, "order: chosen_key follows the received option order")
    by_trial: dict = {}
    for r in rows:
        by_trial.setdefault(r["trial"], []).append(r)
    check(all(any(r["failure_class_hit"] for r in g) for g in by_trial.values()),
          "order: claiming the first-listed forbidden key hits the failure class")
    check(all(r["chosen_key"] == BUILD for r in rows if not r["correct"]),
          "order: every wrong row is the forbidden build claim")
    jm = corpus_score.jeva_metrics(rows, load_scenario(UNROUTABLE)["assert"])
    check(jm["flip_rate"] == 1.0 and jm["flip_trials"] == 2,
          "order: flip_rate 1.0 via corpus_score.jeva_metrics", str(jm["flip_rate"]))
    httpd.shutdown()
    httpd.server_close()


# --- case 3: null_auc ----------------------------------------------------------------

def test_null_auc() -> None:
    def row(conf, null):
        return {"coverage_confidence": conf, "null_ground_truth": null}

    perfect = [row(0.05, True), row(0.1, True), row(0.9, False), row(0.95, False)]
    r = corpus_score.null_auc(perfect)
    check(r["auc"] == 1.0 and r["tpr_at_fpr"] == 1.0 and r["n"] == 4
          and r["fpr_target"] == 0.05, "null_auc: perfect separation auc/tpr 1.0", str(r))

    reversed_rows = [row(0.95, True), row(0.9, True), row(0.1, False), row(0.05, False)]
    r = corpus_score.null_auc(reversed_rows)
    check(r["auc"] == 0.0 and r["tpr_at_fpr"] == 0.0, "null_auc: reversed auc/tpr 0.0", str(r))

    single = corpus_score.null_auc([row(0.1, True), row(0.2, True)])
    check(single["auc"] is None and single["tpr_at_fpr"] is None and single["n"] == 2,
          "null_auc: single class -> None", str(single))

    skipped = corpus_score.null_auc([row(0.1, True), row(None, False), row(0.9, False),
                                     {"null_ground_truth": False}])
    check(skipped["n"] == 2 and skipped["auc"] == 1.0,
          "null_auc: None/missing confidence rows skipped from n, rest scored",
          str(skipped))

    budget = [row(0.5, True), row(0.5, True), row(0.5, False), row(0.5, False),
              row(0.5, False), row(0.5, False), row(0.5, False), row(0.5, False),
              row(0.5, False), row(0.5, False), row(0.5, False), row(0.5, False),
              row(0.5, False), row(0.5, False), row(0.5, False), row(0.5, False),
              row(0.5, False), row(0.5, False), row(0.5, False), row(0.5, False)]
    r = corpus_score.null_auc(budget, fpr_target=0.05)
    check(r["tpr_at_fpr"] == 0.0, "null_auc: uninformative scores -> tpr 0.0", str(r))

    jm = corpus_score.jeva_metrics(perfect, {"null_ground_truth": False})
    check("null_auc" in jm and jm["null_auc"]["n"] == 4,
          "jeva: null_auc block appears when rows carry float coverage_confidence")
    check(jm["null_auc"]["auc"] is None,
          "jeva: per-scenario rows share one truth, so auc is single-class None "
          "(cross-scenario AUC comes from corpus_score.null_auc directly)")
    jm_t0 = corpus_score.jeva_metrics(
        [{"trial": 1, "decision_class": "claim", "chosen_key": "A", "correct": True,
          "board_order": ["A", "B"], "coverage_any_claimable": True}],
        {"null_ground_truth": False})
    check("null_auc" not in jm_t0,
          "jeva: no null_auc block without coverage_confidence (Tier-0 rows)")


# --- case 4: wire mismatch -----------------------------------------------------------

def test_wire_mismatch() -> None:
    try:
        probe_decision.parse_systemone({"unexpected": True}, ["claim", "coverage"])
        check(False, "wire: parse_systemone raises on total mismatch")
    except ValueError as exc:
        check("unexpected" in str(exc), "wire: error names the top-level keys",
              str(exc))
        check(str(exc) != "", "wire: error message non-empty")

    tmp = tempfile.mkdtemp(prefix="siasworkshop-probe-test-")
    out_path = os.path.join(tmp, "rows.jsonl")
    argv = ["--corpus", NULL_BOARD, "--endpoint", "http://127.0.0.1:1",
            "--trials", "1", "--permutations", "1", "--out", out_path, "--quiet"]
    args = probe_decision.build_parser().parse_args(argv)
    rows = probe_decision.run(args, http_post=lambda url, payload, timeout: {"unexpected": True})
    check(len(rows) == 1 and rows[0]["reward"] == -1
          and rows[0]["decision_class"] == "error" and rows[0]["correct"] is False,
          "wire: mismatch row scores -1 as an invalid call", str(rows[0]["reward"]))
    check(rows[0]["invalid_reason"] and "unexpected" in rows[0]["invalid_reason"],
          "wire: invalid_reason carries the parse detail naming the keys",
          str(rows[0]["invalid_reason"]))
    check(rows[0]["coverage_confidence"] is None and rows[0]["p_yes"] is None
          and rows[0]["option_probs"] is None,
          "wire: unusable answers recorded as None, not guessed")

    # tolerance units: variants the one parse function must accept
    variants = probe_decision.parse_systemone(
        {"results": {"claim": {"probabilities": [{"option": "A", "probability": 0.7},
                                                 {"option": "B", "probability": 0.3}],
                               "answer": "A"},
                     "coverage": {"yes": 0.8}}},
        ["claim", "coverage"])
    check(variants["claim"]["probs"] == {"A": 0.7, "B": 0.3}
          and variants["claim"]["answer"] == "A"
          and variants["coverage"]["p_yes"] == 0.8,
          "wire: results container + list probabilities + answer + yes variants")
    bool_noul = probe_decision.parse_systemone(
        {"answers": {"claim": {"probabilities": {"A": 1.0}, "choice": "A"},
                     "coverage": True}}, ["claim", "coverage"])
    check(bool_noul["coverage"]["p_yes"] == 1.0, "wire: noul as bare bool")
    bare = probe_decision.parse_systemone({"claim": {"probabilities": {"A": 1.0},
                                                     "choice": "A"},
                                           "coverage": 0.25},
                                          ["claim", "coverage"])
    check(bare["coverage"]["p_yes"] == 0.25, "wire: questions top-level + noul bare number")
    check(probe_decision.usage_tokens({"usage": {"prompt": 3, "completion": 7}}) == (3, 7),
          "wire: usage_tokens accepts prompt/completion variants")


# --- case 5: transport failure --------------------------------------------------------

def test_transport_failure() -> None:
    def boom(url, payload, timeout):
        raise ConnectionError("connection refused")

    tmp = tempfile.mkdtemp(prefix="siasworkshop-probe-test-")
    out_path = os.path.join(tmp, "rows.jsonl")
    argv = ["--corpus", UNROUTABLE, "--endpoint", "http://127.0.0.1:1",
            "--trials", "2", "--permutations", "2", "--out", out_path, "--quiet"]
    args = probe_decision.build_parser().parse_args(argv)
    rows = probe_decision.run(args, http_post=boom)   # must not raise
    check(len(rows) == 4, "transport: run continues, every run recorded", str(len(rows)))
    check(all(r["reward"] == -1 and r["decision_class"] == "error"
              and r["correct"] is False for r in rows),
          "transport: failed rows score -1 as invalid calls")
    check(all(r["invalid_reason"].startswith("transport_error:")
              and "connection refused" in r["invalid_reason"] for r in rows),
          "transport: invalid_reason carries the failure")
    check(all(isinstance(r["latency_ms"], (int, float)) for r in rows),
          "transport: latency still measured on failure")
    file_rows = read_rows(out_path)
    check(len(file_rows) == 4 and all(r["reward"] == -1 for r in file_rows),
          "transport: failure rows appended to --out")

    # a dead endpoint through the real urllib path still exits 0 (measurement tool)
    sock = socket.socket()
    sock.bind(("127.0.0.1", 0))
    dead_port = sock.getsockname()[1]
    sock.close()
    dead_out = os.path.join(tmp, "dead.jsonl")
    rc = probe_decision.main(["--corpus", NULL_BOARD, "--endpoint",
                              f"http://127.0.0.1:{dead_port}", "--trials", "1",
                              "--permutations", "1", "--out", dead_out, "--quiet"])
    dead_rows = read_rows(dead_out)
    check(rc == 0 and len(dead_rows) == 1 and dead_rows[0]["reward"] == -1
          and dead_rows[0]["invalid_reason"].startswith("transport_error:"),
          "transport: dead endpoint via urllib exits 0 with a -1 row",
          str(dead_rows[0]["invalid_reason"] if dead_rows else "no row"))


def main() -> int:
    test_end_to_end()
    test_option_order_caveat()
    test_null_auc()
    test_wire_mismatch()
    test_transport_failure()
    print(f"\ndecision probe tests: {'PASS' if FAILURES == 0 else f'{FAILURES} failures'}")
    return 1 if FAILURES else 0


if __name__ == "__main__":
    raise SystemExit(main())
