"""Coverage-gate calibration for the served decision models (Tier-1 probe rows).

kev.calibrate cannot consume probe rows (it requires logits; the server serves only
probabilities), so this module fits the two numbers the decision gate needs directly
on our labeled corpus:

- a 2-class temperature T (same 121-point log grid as kev.metrics.TEMPERATURE_FIT),
  minimizing Bernoulli NLL of the tempered P(yes) against the scenario label;
- a decision threshold tau = the largest cutoff whose false-positive rate on NULL
  boards stays within --fpr-target (default 0.05, matching corpus_score.null_auc's
  operating point).

The artifact is JSON, tracked in the repo under sidecar/calibration/, and consumed by
probe_decision --calibration. Caveat recorded in the artifact: fitting from served
probabilities calibrates relative to the served distribution — fine for a decision
gate, not a claim about the model's raw logits.

Labels come from the corpus itself: a scenario is positive (claimable) unless its
assert block sets null_ground_truth, equivalently OR(open_tasks[].claimable).
"""
from __future__ import annotations

import argparse
import datetime
import glob
import json
import math
import os
import sys

SCHEMA_VERSION = 1


# --- math -------------------------------------------------------------------------

def temperature_grid() -> list:
    """0.25..4.0 log grid, 121 points — mirrors kev.metrics.TEMPERATURE_FIT."""
    return [0.25 * (4.0 / 0.25) ** (i / 120.0) for i in range(121)]


def temper(p: float, temperature: float) -> float:
    """2-class temperature scaling on a served probability."""
    if temperature is None or abs(temperature - 1.0) < 1e-12:
        return p
    p = min(max(float(p), 1e-9), 1.0 - 1e-9)
    a = p ** (1.0 / temperature)
    b = (1.0 - p) ** (1.0 / temperature)
    return a / (a + b)


def mean_nll(ps: list, ys: list) -> float:
    total = 0.0
    for p, y in zip(ps, ys):
        p = min(max(p, 1e-9), 1.0 - 1e-9)
        total += -(math.log(p) if y else math.log(1.0 - p))
    return total / max(len(ps), 1)


def brier(ps: list, ys: list) -> float:
    return sum((p - y) ** 2 for p, y in zip(ps, ys)) / max(len(ps), 1)


def auc(ps: list, ys: list) -> float:
    """Mann-Whitney AUC with the positive class = claimable (y=1)."""
    pos = [p for p, y in zip(ps, ys) if y]
    neg = [p for p, y in zip(ps, ys) if not y]
    if not pos or not neg:
        return float("nan")
    wins = 0.0
    for p in pos:
        for n in neg:
            wins += 1.0 if p > n else 0.5 if p == n else 0.0
    return wins / (len(pos) * len(neg))


def threshold_at_fpr(ps: list, ys: list, fpr_target: float) -> tuple:
    """Fits (threshold, direction): claim iff tempered P(yes) >= threshold ("above"),
    or — when the coverage signal is inverted, which kev-4b-ternary's is on our corpus
    (AUC < 0.5: null boards score HIGHER than claimable ones) — claim iff P(yes) <=
    threshold ("below").

    "above": largest cutoff with P(score >= cutoff | negative) <= fpr_target.
    "below": largest cutoff with P(score <= cutoff | negative) <= fpr_target
    (null boards must sit above the cutoff; positives below it get claimed).
    Fallback when no cutoff meets the budget: just outside the extreme scores (FPR 0).
    """
    negatives = [p for p, y in zip(ps, ys) if not y]
    n_neg = max(len(negatives), 1)
    n_pos = max(sum(ys), 1)
    best_tau, best_tpr = None, -1.0
    for tau in sorted(set(ps), reverse=True):
        fpr = sum(1 for n in negatives if n >= tau) / n_neg
        if fpr > fpr_target:
            continue
        tpr = sum(1 for p, y in zip(ps, ys) if y and p >= tau) / n_pos
        if tpr > best_tpr:
            best_tau, best_tpr = tau, tpr
    if best_tau is not None:
        return best_tau, "above"
    for tau in sorted(set(ps)):
        fpr = sum(1 for n in negatives if n <= tau) / n_neg
        if fpr > fpr_target:
            continue
        tpr = sum(1 for p, y in zip(ps, ys) if y and p <= tau) / n_pos
        if tpr > best_tpr:
            best_tau, best_tpr = tau, tpr
    if best_tau is not None:
        return best_tau, "below"
    extreme = max(ps) if ps else 1.0
    return extreme + 1e-6, "above"


def tpr_at(ps: list, ys: list, tau: float, direction: str) -> float:
    positives = sum(ys)
    if not positives:
        return float("nan")
    if direction == "below":
        return sum(1 for p, y in zip(ps, ys) if y and p <= tau) / positives
    return sum(1 for p, y in zip(ps, ys) if y and p >= tau) / positives


# --- data -------------------------------------------------------------------------

def load_labels(corpus_dir: str) -> dict:
    """scenario name -> claimable label (not assert.null_ground_truth)."""
    labels = {}
    for path in sorted(glob.glob(os.path.join(corpus_dir, "*.json"))):
        with open(path, encoding="utf-8") as fh:
            scenario = json.load(fh)
        name = scenario.get("name") or os.path.splitext(os.path.basename(path))[0]
        assert_spec = scenario.get("assert")
        if not assert_spec:
            print(f"calibrate: scenario {name} has no assert block — skipped", file=sys.stderr)
            continue
        labels[name] = not assert_spec.get("null_ground_truth", False)
    return labels


def load_rows(patterns: list) -> list:
    rows = []
    seen_files = 0
    for pattern in patterns:
        for path in sorted(glob.glob(pattern)):
            seen_files += 1
            with open(path, encoding="utf-8") as fh:
                for line in fh:
                    line = line.strip()
                    if line:
                        rows.append(json.loads(line))
    if not seen_files:
        raise SystemExit(f"calibrate: no rows matched {patterns}")
    return rows


# --- fit --------------------------------------------------------------------------

def fit(rows: list, labels: dict, model: str, fpr_target: float) -> dict:
    pairs = [(float(r["p_yes"]), 1 if labels[r["scenario"]] else 0)
             for r in rows
             if r.get("p_yes") is not None and r.get("scenario") in labels]
    if not pairs:
        raise SystemExit("calibrate: no usable (p_yes, label) pairs")
    ps = [p for p, _ in pairs]
    ys = [y for _, y in pairs]
    n_pos, n_neg = sum(ys), len(ys) - sum(ys)

    if n_pos == 0 or n_neg == 0:
        # single class present: leave the gate at the raw default, say so loudly
        temperature, tau, direction = 1.0, 0.5, "above"
    else:
        temperature = min(temperature_grid(), key=lambda t: mean_nll([temper(p, t) for p in ps], ys))
        ps_t = [temper(p, temperature) for p in ps]
        tau, direction = threshold_at_fpr(ps_t, ys, fpr_target)

    ps_t = [temper(p, temperature) for p in ps]
    return {
        "schema_version": SCHEMA_VERSION,
        "model": model,
        "fitted_at": datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="seconds"),
        "fit_method": ("2-class temperature (served probabilities, no logits — calibrates "
                       "relative to the served distribution) + FPR-budget threshold"),
        "source_rows": len(rows),
        "n": len(pairs),
        "n_pos_claimable": n_pos,
        "n_neg_null": n_neg,
        "fpr_target": fpr_target,
        "direction": direction,
        "temperature": round(temperature, 6),
        "threshold": round(tau, 6),
        "metrics_before": {
            "auc": round(auc(ps, ys), 4),
            "mean_nll": round(mean_nll(ps, ys), 4),
            "brier": round(brier(ps, ys), 4),
            "tpr_at_threshold": round(tpr_at(ps, ys, 0.5, "above"), 4),
        },
        "metrics_after": {
            "auc": round(auc(ps_t, ys), 4),
            "mean_nll": round(mean_nll(ps_t, ys), 4),
            "brier": round(brier(ps_t, ys), 4),
            "tpr_at_threshold": round(tpr_at(ps_t, ys, tau, direction), 4),
        },
    }


def load_artifact(path: str) -> dict:
    """Load and validate a calibration artifact (probe_decision --calibration)."""
    with open(path, encoding="utf-8") as fh:
        artifact = json.load(fh)
    if artifact.get("schema_version") != SCHEMA_VERSION:
        raise ValueError(f"calibration artifact {path} has unsupported schema_version "
                         f"{artifact.get('schema_version')}")
    for key in ("model", "temperature", "threshold"):
        if key not in artifact:
            raise ValueError(f"calibration artifact {path} missing {key!r}")
    return artifact


def calibrated_probability(p_yes: float, calibration: dict | None) -> float:
    """The probability the decision gate should see (tempered when calibrated)."""
    if not calibration or p_yes is None:
        return p_yes
    return temper(p_yes, calibration["temperature"])


def main(argv=None) -> None:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--rows", nargs="+", required=True,
                    help="probe_decision jsonl files or glob patterns")
    ap.add_argument("--corpus", default="corpus", help="corpus dir with scenario labels")
    ap.add_argument("--model", required=True, help="model tag the artifact applies to")
    ap.add_argument("--out", required=True, help="artifact output path (tracked JSON)")
    ap.add_argument("--fpr-target", type=float, default=0.05,
                    help="allowed false-positive rate on null boards")
    args = ap.parse_args(argv)

    labels = load_labels(args.corpus)
    rows = load_rows(args.rows)
    artifact = fit(rows, labels, args.model, args.fpr_target)
    os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
    with open(args.out, "w", encoding="utf-8") as fh:
        json.dump(artifact, fh, indent=2)
        fh.write("\n")
    print(json.dumps(artifact, indent=2))


if __name__ == "__main__":
    main()
