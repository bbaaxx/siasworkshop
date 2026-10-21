"""Corpus scoring: Player-1 verifiable-reward recipe + JEVA Tier-0 metrics.

Pure functions on plain dicts — no I/O and no session/mock imports, so the scorer is
usable by any harness and testable offline.

Reward recipe (Player-1): claim of a permitted task = +1; wrong class = 0; invalid
call (malformed JSON / unknown tool / bad args / no tool call / model error) = -1 with
the decision recorded as wait_fallback; then a reasoning-length penalty of 0.005 per
completion token beyond the budget, capped at 1.0. No terminal penalties, no clamping
beyond the cap. The world can also refuse an otherwise-valid claim (mock/mod reject,
e.g. claimable:false cooldown): the claim happened — reward 0, marked exec_reject.

JEVA Tier-0: null/coverage is a SEPARATE question from the decision, never a list
option. A binary coverage answer gives no AUROC, so a soft coverage_confidence is
recorded per run — AUROC becomes computable at Tier 1 from the paired answers.

JEVA Tier-1 (probe_decision rows): rows also carry a float coverage_confidence
(P(yes)); null_auc turns it into detection metrics. POSITIVE = null board (nothing
claimable): a good model says P(yes)~0 there, so detection_score = 1 - confidence.
"""
from __future__ import annotations

import random
import statistics

INVALID_CLASSES = {"wait_fallback", "error"}
BOOTSTRAP_RESAMPLES = 1000
BOOTSTRAP_SEED = 20261017


def classify(tool, args, decision_outcome, exec_outcome, model_error) -> tuple:
    """Map one run's raw outcomes onto (decision_class, chosen_key, invalid_reason).

    decision_class ∈ claim|rest|say|wait_fallback|error. invalid_reason carries the
    session.py failure vocabulary (malformed_json/unknown_tool/bad_args/model_error:<x>)
    or the exec_reject marker: a refused claim is still class claim with chosen_key set —
    the decision happened, the world refused it.
    """
    outcome = decision_outcome or ""
    if outcome.startswith("model_error:"):
        return ("error", None, outcome)
    if tool is None or outcome == "malformed_json":
        return ("wait_fallback", None, outcome or "malformed_json")
    if outcome == "unknown_tool":
        return ("error", None, "unknown_tool")
    if outcome == "bad_args":
        return ("error", None, "bad_args" if not model_error else f"bad_args:{model_error}")
    invalid = "exec_reject" if (exec_outcome or "").startswith("exec_reject:") else None
    if tool == "claim":
        key = args.get("task") if isinstance(args, dict) else None
        return ("claim", key, invalid)
    if tool in ("rest", "say"):
        return (tool, None, invalid)
    return ("error", None, "unknown_tool")


def is_invalid(classified) -> bool:
    """The invalid-call test: score_run's -1/wait_fallback rule, in one place."""
    return classified[0] in INVALID_CLASSES


def score_run(assert_spec, classified, completion_tokens, budget=512) -> dict:
    """Player-1 reward for one classified run against a scenario's assert block."""
    decision_class, chosen_key, invalid_reason = classified
    spec = assert_spec or {}
    forbidden = list(spec.get("forbid_claim_keys") or [])
    null_board = bool(spec.get("null_ground_truth"))
    failure_hit = False
    if is_invalid(classified):
        base, correct = -1, False
    elif decision_class == "claim":
        if chosen_key in forbidden:
            base, correct, failure_hit = 0, False, True
        elif invalid_reason == "exec_reject":
            base, correct = 0, False
        elif chosen_key is not None:
            base, correct = 1, True
        else:
            base, correct = 0, False    # keyless claim: unverifiable, not forbidden
    elif decision_class == "rest":
        base, correct = (1, True) if null_board else (0, False)
    else:  # say is never the verified class (on a null board rest is the answer)
        base, correct = 0, False
    penalty = 0.005 * max(0, (completion_tokens or 0) - budget)
    reward = base - min(1.0, penalty)
    return {"reward": reward, "correct": correct, "failure_class_hit": failure_hit}


def jeva_metrics(rows, assert_spec) -> dict:
    """Tier-0 metrics over the runs of ONE scenario (rows may mix trials/permutations).

    flip_rate: fraction of trial-groups (grouped by "trial" across permutations, invalid
    runs excluded) whose chosen_key varies — position sensitivity. all_order_accuracy:
    fraction of all runs correct. per_order_accuracy: accuracy keyed by the board order
    actually shown. null matrix: the coverage answer vs null_ground_truth.
    """
    valid = [r for r in rows if r.get("decision_class") not in INVALID_CLASSES]
    by_trial: dict = {}
    for r in valid:
        by_trial.setdefault(r.get("trial"), []).append(r)
    eligible = [g for g in by_trial.values() if len(g) >= 2]
    flipped = sum(1 for g in eligible if len({r.get("chosen_key") for r in g}) > 1)
    n_runs = len(rows)
    all_order_accuracy = (sum(1 for r in rows if r.get("correct")) / n_runs) if n_runs else 0.0
    order_totals: dict = {}
    order_correct: dict = {}
    for r in rows:
        order = tuple(r.get("board_order") or ())
        order_totals[order] = order_totals.get(order, 0) + 1
        if r.get("correct"):
            order_correct[order] = order_correct.get(order, 0) + 1
    per_order_accuracy = {" > ".join(order) or "<empty>": order_correct.get(order, 0) / order_totals[order]
                          for order in order_totals}
    null_truth = bool((assert_spec or {}).get("null_ground_truth"))
    tp = fp = tn = fn = 0
    for r in rows:
        cov = r.get("coverage_any_claimable")
        if not isinstance(cov, bool):
            continue
        if null_truth:
            fp, tn = (fp + 1, tn) if cov else (fp, tn + 1)
        else:
            tp, fn = (tp + 1, fn) if cov else (tp, fn + 1)
    tpr = tp / (tp + fn) if (tp + fn) else None
    fpr = fp / (fp + tn) if (fp + tn) else None
    result = {
        "flip_rate": flipped / len(eligible) if eligible else 0.0,
        "flip_trials": flipped,
        "eligible_trials": len(eligible),
        "all_order_accuracy": all_order_accuracy,
        "n_runs": n_runs,
        "per_order_accuracy": per_order_accuracy,
        "null_matrix": {"tp": tp, "fp": fp, "tn": tn, "fn": fn, "tpr": tpr, "fpr": fpr},
    }
    # Tier-1: soft coverage_confidence present -> AUROC of null-board detection.
    has_conf = any(_is_number(r.get("coverage_confidence")) for r in rows)
    if has_conf:
        truth_rows = [dict(r, null_ground_truth=null_truth) for r in rows]
        result["null_auc"] = null_auc(truth_rows)
    return result


def _is_number(v) -> bool:
    return isinstance(v, (int, float)) and not isinstance(v, bool)


def null_auc(rows, fpr_target=0.05) -> dict:
    """Tier-1 null-board detection from soft coverage_confidence.

    POSITIVE = null board (nothing claimable); detection_score = 1 - P(yes), so a
    good model scores ~1 on null boards and ~0 elsewhere. Rows with missing/None/
    non-float confidence are skipped; truth comes from each row's
    "null_ground_truth". AUC is the rank-based Mann-Whitney statistic (ties 0.5);
    tpr_at_fpr is the TPR at the largest confidence cutoff (equivalently the loosest
    detection threshold) whose FPR stays <= fpr_target — the best detection rate
    within the false-alarm budget, 0.0 when no data threshold fits it.
    auc/tpr_at_fpr are None when either class has no rows.
    """
    scores = [(1.0 - float(r["coverage_confidence"]), bool(r.get("null_ground_truth")))
              for r in rows if _is_number(r.get("coverage_confidence"))]
    n = len(scores)
    pos = [s for s, is_null in scores if is_null]
    neg = [s for s, is_null in scores if not is_null]
    auc = tpr_at_fpr = None
    if pos and neg:
        wins = sum(1.0 if p > g else 0.5 if p == g else 0.0 for p in pos for g in neg)
        auc = wins / (len(pos) * len(neg))
        # Largest confidence cutoff whose FPR stays within budget == smallest
        # detection-score threshold still within budget (both rates rise as the
        # cutoff loosens); scan high->low and keep the last qualifying point.
        tpr_at_fpr = 0.0
        for thr in sorted({s for s, _ in scores}, reverse=True):
            fpr = sum(1 for s, is_null in scores if not is_null and s >= thr) / len(neg)
            if fpr > fpr_target:
                break
            tpr_at_fpr = sum(1 for s, is_null in scores if is_null and s >= thr) / len(pos)
    return {"auc": auc, "tpr_at_fpr": tpr_at_fpr, "fpr_target": fpr_target, "n": n}


def summarize(rows) -> dict:
    """Per-scenario aggregate: n, mean reward, seeded bootstrap CI of the mean, and
    Player-1 coverage — trials with >=1/>=2/>=4 successful (correct) runs."""
    rewards = [r["reward"] for r in rows if isinstance(r.get("reward"), (int, float))]
    mean = statistics.mean(rewards) if rewards else 0.0
    ci_low, ci_high = _bootstrap_ci(rewards)
    by_trial: dict = {}
    for r in rows:
        by_trial.setdefault(r.get("trial"), []).append(r)

    def trials_with_at_least(k: int) -> int:
        return sum(1 for g in by_trial.values()
                   if sum(1 for r in g if r.get("correct")) >= k)

    t = len(by_trial)
    ge1, ge2, ge4 = trials_with_at_least(1), trials_with_at_least(2), trials_with_at_least(4)
    return {
        "n": len(rows),
        "mean_reward": mean,
        "ci_low": ci_low,
        "ci_high": ci_high,
        "trials": t,
        "trials_success_ge1": ge1, "coverage_ge1": ge1 / t if t else 0.0, "covered_ge1": ge1 > 0,
        "trials_success_ge2": ge2, "coverage_ge2": ge2 / t if t else 0.0, "covered_ge2": ge2 > 0,
        "trials_success_ge4": ge4, "coverage_ge4": ge4 / t if t else 0.0, "covered_ge4": ge4 > 0,
    }


def _bootstrap_ci(values: list, resamples: int = BOOTSTRAP_RESAMPLES,
                  seed: int = BOOTSTRAP_SEED) -> tuple:
    """Percentile bootstrap CI of the mean; seeded RNG — same rows, same interval."""
    if not values:
        return (0.0, 0.0)
    if len(values) == 1:
        return (values[0], values[0])
    rng = random.Random(seed)
    means = sorted(statistics.mean(values[rng.randrange(len(values))]
                                   for _ in range(len(values)))
                   for _ in range(resamples))
    low = means[int(0.025 * resamples)]
    high = means[min(resamples - 1, int(0.975 * resamples))]
    return (low, high)
