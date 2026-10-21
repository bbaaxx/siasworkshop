"""SystemOne decision probe (Q4 Tier-1): drive corpus/*.json scenarios against ANY
SystemOne-compatible typed decision endpoint (e.g. Kev-4B, Jev-Style-0.8B served
behind POST {endpoint}/v1/systemone) and record corpus JSONL rows for scoring.

Unlike the LLM paths, question isolation is a native API property here: ONE POST per
run carries TWO questions and the coverage/null question is NEVER a claim option
(JEVA Prop 2). The client derives the decision from the typed answers (argmax option
of the choice answer; rest iff P(yes) < 0.5) and scores it with the existing
corpus_score Player-1 recipe — a typed endpoint's decision is a decision; execution
is the mod's business downstream.

Examples:
  # against a locally served typed model:
  python3 probe_decision.py --corpus corpus --endpoint http://127.0.0.1:8010 \
      --tag kev4b --trials 4 --permutations 2

Seeding is EXACTLY run_corpus's scheme: seed = scenario.seed + trial*1000 + perm;
with permutations > 1 the open-task list is a seeded shuffle of a deep copy, and
board_order (the permuted keys) is the order options are sent in the claim criteria.
"""
from __future__ import annotations

import argparse
import copy
import json
import random
import time
import urllib.error
import urllib.request
from pathlib import Path

import corpus_score

QUESTION_NAMES = ("claim", "coverage")
CLAIM_INSTRUCTIONS = "Which listed open task should this agent claim right now?"
COVERAGE_INSTRUCTIONS = "Is any listed open task claimable by this agent right now?"
TIMEOUT_S = 120

# The corpus row schema plus the Tier-1 extras (option_probs, p_yes).
ROW_KEYS = ("scenario", "trial", "permutation", "seed", "mode", "model", "tier0",
            "decision_class", "chosen_key", "correct", "failure_class_hit",
            "invalid_reason", "coverage_any_claimable", "coverage_confidence",
            "completion_tokens", "prompt_tokens", "latency_ms", "reward",
            "board_order", "option_probs", "p_yes")


class TransportError(ConnectionError):
    """Network-level or non-2xx failure talking to the endpoint."""


# --- response parsing: the ONLY place that knows the SystemOne wire shape --------
# Tolerant of spelling variants because the exact key names are not yet verified
# against a live server; a wire fix should be a one-line change here.

def parse_systemone(body: dict, question_names) -> dict:
    """Parse a SystemOne response into {name: {"probs", "answer", "p_yes"}}.

    Tolerant variants: answers|results (or question names top-level); choice
    probabilities as {opt: p} dict or [{option, probability}, ...] list; the chosen
    option as choice|selected|most_likely|answer (str or one-level dict); noul
    P(yes) as noul|p_yes|probability|p field, {"yes": p}, a bare bool, or a bare number.
    Raises ValueError naming the body's top-level keys on total mismatch.
    """
    def probs_of(block) -> dict:
        raw = None
        if isinstance(block, dict):
            raw = block.get("probabilities", block.get("probs"))
        if isinstance(raw, dict):
            return {str(k): float(v) for k, v in raw.items()
                    if isinstance(v, (int, float)) and not isinstance(v, bool)}
        if isinstance(raw, list):
            out = {}
            for entry in raw:
                if not isinstance(entry, dict):
                    continue
                opt = entry.get("option", entry.get("key", entry.get("label")))
                p = entry.get("probability", entry.get("p", entry.get("value")))
                if opt is not None and isinstance(p, (int, float)) and not isinstance(p, bool):
                    out[str(opt)] = float(p)
            return out
        return {}

    def choice_of(block):
        if not isinstance(block, dict):
            return None
        for key in ("choice", "selected", "most_likely", "answer"):
            v = block.get(key)
            if isinstance(v, str):
                return v
            if isinstance(v, dict):
                inner = v.get("option", v.get("key", v.get("value")))
                if isinstance(inner, str):
                    return inner
        return None

    def p_yes_of(block):
        if isinstance(block, bool):
            return 1.0 if block else 0.0
        if isinstance(block, (int, float)):
            return float(block)
        if not isinstance(block, dict):
            return None
        for key in ("noul", "p_yes", "probability", "p"):
            v = block.get(key)
            if isinstance(v, (int, float)) and not isinstance(v, bool):
                return float(v)
        yes = block.get("yes")
        if isinstance(yes, (int, float)) and not isinstance(yes, bool):
            return float(yes)
        ans = block.get("answer")
        if isinstance(ans, bool):
            return 1.0 if ans else 0.0
        if isinstance(ans, (int, float)):
            return float(ans)
        return None

    if not isinstance(body, dict):
        raise ValueError(f"systemone wire mismatch: body is {type(body).__name__}, "
                         f"expected object; top-level keys: n/a")
    container = None
    for key in ("answers", "results"):
        if isinstance(body.get(key), dict):
            container = body[key]
            break
    if container is None and any(name in body for name in question_names):
        container = body
    if not isinstance(container, dict):
        raise ValueError(f"systemone wire mismatch: no answers/results block; "
                         f"top-level keys: {sorted(body)}")
    parsed = {}
    for name in question_names:
        block = container.get(name)
        if block is None:
            continue
        parsed[name] = {"probs": probs_of(block), "answer": choice_of(block),
                        "p_yes": p_yes_of(block)}
    if not parsed:
        raise ValueError(f"systemone wire mismatch: no blocks for {list(question_names)}; "
                         f"top-level keys: {sorted(body)}")
    return parsed


def usage_tokens(body) -> tuple:
    """(prompt_tokens, completion_tokens) from the usage block, (None, None) absent."""
    usage = body.get("usage") if isinstance(body, dict) else None
    if not isinstance(usage, dict):
        return (None, None)

    def num(*keys):
        for key in keys:
            v = usage.get(key)
            if isinstance(v, (int, float)) and not isinstance(v, bool):
                return v
        return None

    return (num("prompt_tokens", "input_tokens", "prompt", "input"),
            num("completion_tokens", "output_tokens", "completion", "output"))


# --- transport --------------------------------------------------------------------

def default_http_post(url: str, payload: dict, timeout: float = TIMEOUT_S) -> dict:
    """POST payload as JSON, return the decoded body dict. Raises TransportError
    on network failure or non-2xx (with a response body preview)."""
    data = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(url, data=data, method="POST",
                                 headers={"Content-Type": "application/json",
                                          "Content-Length": str(len(data))})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw = resp.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as exc:
        preview = exc.read().decode("utf-8", "replace")[:400]
        raise TransportError(f"HTTP {exc.code}: {preview}") from exc
    except (urllib.error.URLError, TimeoutError, OSError) as exc:
        raise TransportError(str(exc)) from exc
    try:
        body = json.loads(raw)
    except ValueError as exc:
        raise ValueError(f"systemone: response is not JSON: {raw[:200]!r}") from exc
    if not isinstance(body, dict):
        raise ValueError(f"systemone: response JSON is {type(body).__name__}, expected object")
    return body


# --- run rendering + decision derivation -------------------------------------------

def discover_scenarios(path: str) -> list:
    p = Path(path)
    if p.is_dir():
        return sorted(str(f) for f in p.glob("*.json"))
    return [str(p)]


def build_payload(scenario: dict, tasks: list) -> dict:
    """The single POST body: the state text plus the two questions. Criteria are
    built in the permuted task order — that IS the order options are sent."""
    lines = "\n".join("- " + t["key"] + " — " + t.get("detail", "") for t in tasks)
    state = scenario["observation"] + "\n\nOPEN TASKS:\n" + lines
    return {
        "state": state,
        "questions": {
            "claim": {"type": "choice", "instructions": CLAIM_INSTRUCTIONS,
                      "criteria": {t["key"]: t.get("detail", "") for t in tasks}},
            "coverage": {"type": "noul", "instructions": COVERAGE_INSTRUCTIONS},
        },
    }


def derive_decision(parsed: dict) -> tuple:
    """Client-side decision from the typed answers: chosen_key = argmax-probability
    option of the claim answer (independent of the coverage answer); class = rest
    iff P(yes) < 0.5. Returns (decision_class, chosen_key, p_yes, option_probs)."""
    claim = parsed.get("claim") or {}
    coverage = parsed.get("coverage") or {}
    probs = claim.get("probs") or {}
    chosen = max(probs, key=probs.get) if probs else claim.get("answer")
    if not isinstance(chosen, str):
        chosen = None
    p_yes = coverage.get("p_yes")
    if p_yes is None:
        raise ValueError("systemone wire mismatch: coverage answer carries no P(yes) "
                         "(tried noul/p_yes/probability/p/yes/bool)")
    if p_yes < 0.5:
        return ("rest", None, float(p_yes), probs)
    if chosen is None:
        raise ValueError("systemone wire mismatch: claim answer has no probabilities "
                         "or choice to take the argmax from")
    return ("claim", chosen, float(p_yes), probs)


def failure_reason(exc: Exception) -> str:
    preview = str(exc).replace("\n", " ")[:200]
    if isinstance(exc, (TransportError, ConnectionError, TimeoutError, OSError)):
        return f"transport_error:{exc.__class__.__name__}: {preview}"
    if isinstance(exc, (ValueError, KeyError, TypeError)):
        return f"parse_error:{preview}"
    return f"probe_error:{exc.__class__.__name__}: {preview}"


def build_invalid_row(name, trial, perm, seed, mode, model, assert_spec, reason,
                      latency_ms, board_order) -> dict:
    classified = ("error", None, reason)
    scored = corpus_score.score_run(assert_spec, classified, None)
    return {"scenario": name, "trial": trial, "permutation": perm, "seed": seed,
            "mode": mode, "model": model, "tier0": False,
            "decision_class": classified[0], "chosen_key": None,
            "correct": scored["correct"], "failure_class_hit": scored["failure_class_hit"],
            "invalid_reason": reason, "coverage_any_claimable": None,
            "coverage_confidence": None, "completion_tokens": None, "prompt_tokens": None,
            "latency_ms": latency_ms, "reward": scored["reward"], "board_order": board_order,
            "option_probs": None, "p_yes": None}


def run_scenario(args, path: str, scenario: dict, post, url: str, mode: str,
                 model: str, out) -> list:
    name = scenario.get("name", path)
    assert_spec = scenario.get("assert") or {}
    trials = args.trials or scenario.get("trials") or 8
    perms = args.permutations or scenario.get("permutations") or 1
    rows = []
    for trial in range(1, trials + 1):
        for perm in range(1, perms + 1):
            seed = scenario.get("seed", 0) + trial * 1000 + perm
            rng = random.Random(seed)
            tasks = copy.deepcopy(scenario.get("open_tasks") or [])
            if perms > 1:
                rng.shuffle(tasks)
            board_order = [t.get("key") for t in tasks]
            payload = build_payload(scenario, tasks)
            t0 = time.perf_counter()
            try:
                body = post(url, payload, TIMEOUT_S)
                latency_ms = (time.perf_counter() - t0) * 1000.0
                prompt_tokens, completion_tokens = usage_tokens(body)
                decision_class, chosen_key, p_yes, probs = derive_decision(
                    parse_systemone(body, QUESTION_NAMES))
                classified = (decision_class, chosen_key, None)
                scored = corpus_score.score_run(assert_spec, classified, completion_tokens)
                row = {"scenario": name, "trial": trial, "permutation": perm, "seed": seed,
                       "mode": mode, "model": model, "tier0": False,
                       "decision_class": decision_class, "chosen_key": chosen_key,
                       "correct": scored["correct"],
                       "failure_class_hit": scored["failure_class_hit"],
                       "invalid_reason": None,
                       "coverage_any_claimable": p_yes >= 0.5,
                       "coverage_confidence": p_yes,
                       "completion_tokens": completion_tokens, "prompt_tokens": prompt_tokens,
                       "latency_ms": latency_ms, "reward": scored["reward"],
                       "board_order": board_order, "option_probs": probs or None,
                       "p_yes": p_yes}
            except Exception as exc:
                latency_ms = (time.perf_counter() - t0) * 1000.0
                row = build_invalid_row(name, trial, perm, seed, mode, model, assert_spec,
                                        failure_reason(exc), latency_ms, board_order)
            rows.append(row)
            out.write(json.dumps(row) + "\n")
            out.flush()
            if not args.quiet:
                print(f"{row['scenario']} t{trial}.{perm} seed={seed} -> "
                      f"{row['decision_class']}:{row['chosen_key']} "
                      f"reward={row['reward']:+.2f} cov={row['coverage_any_claimable']} "
                      f"board={' > '.join(board_order) or '<empty>'}")
    return rows


def print_summary(rows: list, mode: str, assert_specs: dict) -> None:
    groups: dict = {}
    for row in rows:
        groups.setdefault(row["scenario"], []).append(row)
    print("\n=== probe summary (scenario) ===")
    for name, group in sorted(groups.items()):
        spec = assert_specs.get(name, {})
        agg = corpus_score.summarize(group)
        print(f"{name:<36} n={agg['n']:>3} mean_reward={agg['mean_reward']:+.3f} "
              f"CI[{agg['ci_low']:+.3f},{agg['ci_high']:+.3f}]")
        jm = corpus_score.jeva_metrics(group, spec)
        na = jm.get("null_auc")
        na_txt = (f"auc={na['auc']:.3f} tpr@{na['fpr_target']:.2f}={na['tpr_at_fpr']:.3f}"
                  if na and na["auc"] is not None else
                  (f"auc={na['auc']} tpr={na['tpr_at_fpr']}" if na else "auc=n/a"))
        print(f"{'':<36} flip_rate={jm['flip_rate']:.2f} "
              f"all_order_acc={jm['all_order_accuracy']:.2f} {na_txt}")


def run(args, http_post=None) -> list:
    """Run the probe; returns the metric rows. `http_post` overrides transport
    (tests inject stubs); otherwise default_http_post (urllib) is used."""
    scenario_paths = discover_scenarios(args.corpus)
    if not scenario_paths:
        print(f"no corpus scenarios found at {args.corpus}")
        return []
    post = http_post or default_http_post
    tag = args.tag or "typed"
    mode = f"systemone:{tag}"
    url = args.endpoint.rstrip("/") + "/v1/systemone"
    out_path = args.out or str(Path("metrics") /
                               f"corpus-{tag}-{time.strftime('%Y%m%d-%H%M%S')}.jsonl")
    Path(out_path).parent.mkdir(parents=True, exist_ok=True)

    all_rows: list = []
    assert_specs: dict = {}
    with open(out_path, "a", encoding="utf-8") as out:
        for path in scenario_paths:
            with open(path, encoding="utf-8") as fh:
                scenario = json.load(fh)
            name = scenario.get("name", path)
            assert_specs[name] = scenario.get("assert") or {}
            all_rows.extend(run_scenario(args, path, scenario, post, url, mode,
                                         args.endpoint, out))
    print_summary(all_rows, mode, assert_specs)
    if not args.quiet:
        print(f"\nmetrics -> {out_path}")
    return all_rows


def build_parser() -> argparse.ArgumentParser:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--corpus", required=True,
                    help="corpus scenario .json file or directory of them")
    ap.add_argument("--endpoint", default="http://127.0.0.1:8010",
                    help="SystemOne-compatible base URL (default: http://127.0.0.1:8010)")
    ap.add_argument("--tag", default="typed",
                    help="run-batch tag; mode is recorded as systemone:<tag> "
                         "(default: typed)")
    ap.add_argument("--trials", type=int, default=None, help="override scenario trials")
    ap.add_argument("--permutations", type=int, default=None,
                    help="override scenario permutations")
    ap.add_argument("--out", help="metrics JSONL path (default: metrics/corpus-<tag>-<ts>.jsonl)")
    ap.add_argument("--quiet", action="store_true", help="suppress per-run lines")
    return ap


def main(argv=None) -> int:
    args = build_parser().parse_args(argv)
    run(args)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
