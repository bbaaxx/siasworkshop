"""Calibration tests for the coverage gate (calibrate_coverage + probe_decision
integration). No network: every fixture is synthetic rows or the checked-in artifact.

Run: python3 test_calibrate_coverage.py
"""
from __future__ import annotations

import json
import math
import os
import tempfile

import calibrate_coverage
import probe_decision

HERE = os.path.dirname(os.path.abspath(__file__))
ARTIFACT = os.path.join(HERE, "calibration", "coverage-kev-4b-ternary.json")

FAILURES = 0


def check(cond, label, detail=""):
    global FAILURES
    status = "PASS" if cond else "FAIL"
    if not cond:
        FAILURES += 1
    print(f"[{status}] {label}" + (f" — {detail}" if (detail and not cond) else ""))


# --- temper ------------------------------------------------------------------------

def test_temper_identity_and_shape():
    check(calibrate_coverage.temper(0.47, 1.0) == 0.47, "temper: T=1 is the identity")
    sharp = calibrate_coverage.temper(0.47, 0.5)
    check(sharp < 0.47, "temper: T<1 sharpens toward 0/1", str(sharp))
    soft = calibrate_coverage.temper(0.47, 4.0)
    check(soft > 0.47, "temper: T>1 softens toward 0.5", str(soft))
    low = calibrate_coverage.temper(0.0, 4.0)
    high = calibrate_coverage.temper(1.0, 4.0)
    check(not math.isnan(low) and not math.isnan(high) and 0.0 <= low < 0.01 and 0.99 < high <= 1.0,
          "temper: degenerate p clamps without NaN", f"low={low} high={high}")


def test_temperature_grid_matches_kev():
    grid = calibrate_coverage.temperature_grid()
    check(len(grid) == 121, "grid: 121 points", str(len(grid)))
    check(abs(grid[0] - 0.25) < 1e-12 and abs(grid[-1] - 4.0) < 1e-12,
          "grid: 0.25..4.0 log span", f"{grid[0]}..{grid[-1]}")
    check(all(grid[i] < grid[i + 1] for i in range(len(grid) - 1)), "grid: strictly increasing")


# --- threshold ---------------------------------------------------------------------

def test_threshold_above_direction():
    ps = [0.9, 0.8, 0.7, 0.2, 0.1]
    ys = [1, 1, 1, 0, 0]
    tau, direction = calibrate_coverage.threshold_at_fpr(ps, ys, 0.05)
    check(direction == "above", "separated signal keeps the above direction", direction)
    check(tpr := calibrate_coverage.tpr_at(ps, ys, tau, direction) == 1.0, "above: full TPR", str(tpr))


def test_threshold_below_direction_recovers_inverted_signal():
    # kev-4b-ternary on our corpus, 2026-10-21 fit: claimable boards sit at ~0.4735,
    # the null board answers a constant 0.5631 — cleanly separated, INVERTED.
    ps = [0.453, 0.47, 0.4735, 0.499, 0.5631, 0.5631]
    ys = [1, 1, 1, 1, 0, 0]
    tau, direction = calibrate_coverage.threshold_at_fpr(ps, ys, 0.05)
    check(direction == "below", "inverted signal flips the gate direction", direction)
    check(0.499 <= tau < 0.5631, "below: threshold sits in the gap", str(tau))
    check(calibrate_coverage.tpr_at(ps, ys, tau, direction) == 1.0, "below: full TPR")


def test_threshold_fpr_budget_is_respected():
    ps = [0.9, 0.8, 0.75, 0.7, 0.65, 0.6]
    ys = [1, 1, 0, 0, 0, 0]
    tau, direction = calibrate_coverage.threshold_at_fpr(ps, ys, 0.34)
    check(direction == "above", "mixed signal: above direction", direction)
    neg = [p for p, y in zip(ps, ys) if not y]
    fpr = sum(1 for n in neg if n >= tau) / len(neg)
    check(fpr <= 0.34 + 1e-9, "above: FPR within budget", f"fpr={fpr}")


# --- fit ---------------------------------------------------------------------------

def _write_corpus(tmp, labels: dict) -> str:
    corpus = os.path.join(tmp, "corpus")
    os.mkdir(corpus)
    for name, claimable in labels.items():
        with open(os.path.join(corpus, f"{name}.json"), "w", encoding="utf-8") as fh:
            json.dump({"name": name,
                       "assert": {"null_ground_truth": not claimable}}, fh)
    return corpus


def _write_rows(tmp, rows) -> str:
    path = os.path.join(tmp, "rows.jsonl")
    with open(path, "w", encoding="utf-8") as fh:
        for row in rows:
            fh.write(json.dumps(row) + "\n")
    return path


def test_fit_produces_consumable_artifact():
    tmp = tempfile.mkdtemp(prefix="siasworkshop-cal-test-")
    corpus = _write_corpus(tmp, {"claimable_board": True, "null_board": False})
    rows = ([{"scenario": "claimable_board", "p_yes": 0.47}] * 8
            + [{"scenario": "null_board", "p_yes": 0.5631}] * 4)
    path = _write_rows(tmp, rows)
    labels = calibrate_coverage.load_labels(corpus)
    artifact = calibrate_coverage.fit(calibrate_coverage.load_rows([path]), labels,
                                      "synthetic", 0.05)
    for key in ("schema_version", "model", "temperature", "threshold", "direction",
                "metrics_before", "metrics_after", "n_pos_claimable", "n_neg_null"):
        check(key in artifact, f"artifact carries {key}")
    check(artifact["direction"] == "below", "fit detects the inversion", artifact["direction"])
    check(artifact["n"] == 12 and artifact["n_neg_null"] == 4, "fit counts both classes")
    check(artifact["metrics_after"]["tpr_at_threshold"] == 1.0,
          "calibrated gate claims every claimable board",
          str(artifact["metrics_after"]))


def test_fit_single_class_falls_back_to_default():
    tmp = tempfile.mkdtemp(prefix="siasworkshop-cal-test-")
    corpus = _write_corpus(tmp, {"only_claimable": True})
    rows = [{"scenario": "only_claimable", "p_yes": 0.7}] * 4
    path = _write_rows(tmp, rows)
    artifact = calibrate_coverage.fit(calibrate_coverage.load_rows([path]),
                                      calibrate_coverage.load_labels(corpus), "synthetic", 0.05)
    check(artifact["direction"] == "above" and artifact["threshold"] == 0.5
          and artifact["temperature"] == 1.0, "single class: raw 0.5 gate, stated plainly")


# --- artifact + gate integration ----------------------------------------------------

def test_checked_in_artifact_loads_and_separates():
    artifact = calibrate_coverage.load_artifact(ARTIFACT)
    check(artifact["model"] == "kev-4b-ternary", "artifact model tag")
    check(artifact["direction"] == "below", "kev artifact direction is below (inverted signal)",
          artifact.get("direction", "<missing>"))
    check(artifact["metrics_after"]["tpr_at_threshold"] == 1.0,
          "kev artifact claims every claimable board at its threshold",
          str(artifact["metrics_after"]))
    claimable_probe = {"claim": {"probs": {"build:mazons:1": 0.9}},
                       "coverage": {"p_yes": 0.4735}}
    null_probe = {"claim": {"probs": {"comfort:light:x": 0.9}},
                  "coverage": {"p_yes": 0.5631}}
    decision, _, p_yes, _ = probe_decision.derive_decision(claimable_probe, artifact)
    check(decision == "claim" and p_yes == 0.4735,
          "calibrated gate CLAIMS a claimable board the raw 0.5 gate would rest on")
    decision, _, _, _ = probe_decision.derive_decision(null_probe, artifact)
    check(decision == "rest", "calibrated gate RESTS the null board")
    # and without an artifact the behavior is exactly the historical raw gate
    decision, _, _, _ = probe_decision.derive_decision(claimable_probe, None)
    check(decision == "rest", "uncalibrated gate keeps the raw <0.5 rest semantics")


def test_load_artifact_validates():
    tmp = tempfile.mkdtemp(prefix="siasworkshop-cal-test-")
    bad = os.path.join(tmp, "bad.json")
    with open(bad, "w", encoding="utf-8") as fh:
        json.dump({"schema_version": 99}, fh)
    try:
        calibrate_coverage.load_artifact(bad)
        check(False, "load_artifact rejects a bad schema_version")
    except ValueError:
        check(True, "load_artifact rejects a bad schema_version")
    missing = os.path.join(tmp, "missing.json")
    with open(missing, "w", encoding="utf-8") as fh:
        json.dump({"schema_version": 1, "model": "x"}, fh)
    try:
        calibrate_coverage.load_artifact(missing)
        check(False, "load_artifact rejects missing keys")
    except ValueError:
        check(True, "load_artifact rejects missing keys")


def main() -> int:
    test_temper_identity_and_shape()
    test_temperature_grid_matches_kev()
    test_threshold_above_direction()
    test_threshold_below_direction_recovers_inverted_signal()
    test_threshold_fpr_budget_is_respected()
    test_fit_produces_consumable_artifact()
    test_fit_single_class_falls_back_to_default()
    test_checked_in_artifact_loads_and_separates()
    test_load_artifact_validates()
    print(f"\ncalibration tests: {'PASS' if FAILURES == 0 else f'{FAILURES} failures'}")
    return 1 if FAILURES else 0


if __name__ == "__main__":
    raise SystemExit(main())
