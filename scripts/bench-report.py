#!/usr/bin/env python3
# ===========================================================================
# nodera bench-report — turn one JMH run into a report somebody can act on.
#
# `./gradlew :peer:jmh` produces a JSON array of scores. A score on its own is
# not information: it cannot say whether 40 us is fast, whether it got slower
# since Tuesday, or which of the four lanes to open first. This script adds the
# three things that make it actionable:
#
#   1. RANK      — every benchmark sorted by what it actually costs, so the
#                  bottleneck is the top row rather than an opinion.
#   2. SCALING   — how cost grows with the @Param it was measured against.
#                  Cost that grows faster than its input is the only reliable
#                  early warning of an algorithm that will fall over at network
#                  scale, and it is invisible in a single-size measurement.
#   3. BASELINE  — the delta against fixtures/bench/baseline.json, so a change
#                  that makes the peer slower fails a check instead of being
#                  noticed a release later.
#
#   scripts/bench-report.py --results java/peer/build/results/jmh/results.json \
#                           --out build/reports/nodera/BENCHMARKS.md
#   scripts/bench-report.py ... --check            # non-zero exit on regression
#   scripts/bench-report.py ... --write-baseline   # accept the current numbers
#   scripts/bench-report.py ... --summary          # append to the CI job summary
#
# Pure stdlib on purpose: a benchmark gate that needs its own dependency install
# is a gate that breaks for reasons unrelated to the code it measures.
# ===========================================================================
import argparse
import json
import os
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DEFAULT_RESULTS = ROOT / "java/peer/build/results/jmh/results.json"
DEFAULT_OUT = ROOT / "build/reports/nodera/BENCHMARKS.md"
DEFAULT_BASELINE = ROOT / "fixtures/bench/baseline.json"

# One lane per benchmark class. The lane is what the report groups by, because
# "discovery got slower" is a sentence somebody can act on and "DiscoveryBenchmark
# .directoryOnline:1024 got slower" is a line item inside it.
LANES = {
    "DiscoveryBenchmark": ("peer discovery", "Finding peers: route parse, directory ingest, liveness, warm-start cache, election."),
    "ChunkSyncBenchmark": ("chunk synchronisation", "Moving a region: snapshot to blob to pieces to selection to reassembly to root."),
    "WireBenchmark": ("wire codec", "Canonical encode/decode + hashing — multiplied by every other lane's message rate."),
    "RuntimeBenchmark": ("internal runtime latency", "What the always-on worker spends per second while nothing is wrong."),
}

# Everything is normalised to microseconds per operation so lanes are comparable.
TO_MICROS = {
    "ns/op": 1e-3,
    "us/op": 1.0,
    "ms/op": 1e3,
    "s/op": 1e6,
}

# A benchmark is only called a regression when it is BOTH slower than the
# baseline by this fraction and outside the run's own error bars. JMH noise on a
# shared CI runner is routinely 10-15%; a gate that fires on that teaches people
# to ignore it.
REGRESSION_THRESHOLD = 0.25

# Cost growing more than this much faster than its input is reported as
# superlinear. 1.35 leaves room for constant factors and cache effects while
# still catching an O(n^2) walk hiding behind a small n.
SUPERLINEAR_FACTOR = 1.35

# The scaling table is a shortlist, not an inventory: past ~20 rows nobody reads it.
MAX_SCALING_ROWS = 20


def load_results(path):
    if not path.exists():
        sys.exit(f"bench-report: no JMH results at {path} — run `./gradlew :peer:jmh` first")
    with path.open(encoding="utf-8") as handle:
        raw = json.load(handle)
    if not raw:
        sys.exit(f"bench-report: {path} is an empty result set — the run measured nothing")
    return [parse_entry(entry) for entry in raw]


def parse_entry(entry):
    metric = entry["primaryMetric"]
    unit = metric["scoreUnit"]
    scale = TO_MICROS.get(unit)
    if scale is None:
        # Throughput modes would need inverting before they could be compared to
        # a latency baseline; refuse rather than silently mixing the two.
        sys.exit(f"bench-report: unsupported score unit {unit!r} — benchmarks must report time per op")
    fqn = entry["benchmark"]
    cls, method = fqn.rsplit(".", 1)
    simple = cls.rsplit(".", 1)[-1]
    params = entry.get("params") or {}
    return {
        "key": key_of(fqn, params),
        "class": simple,
        "method": method,
        "params": params,
        "micros": metric["score"] * scale,
        "error": (metric.get("scoreError") or 0.0) * scale,
        "unit": unit,
        "jdk": entry.get("jdkVersion", "?"),
        "vm": entry.get("vmVersion", "?"),
        "forks": entry.get("forks"),
        "iterations": entry.get("measurementIterations"),
    }


def key_of(fqn, params):
    if not params:
        return fqn
    joined = ",".join(f"{name}={value}" for name, value in sorted(params.items()))
    return f"{fqn}[{joined}]"


def lane_of(result):
    return LANES.get(result["class"], ("other", ""))[0]


def fmt_micros(value):
    if value >= 1000:
        return f"{value / 1000:,.3f} ms"
    if value < 1:
        return f"{value * 1000:,.1f} ns"
    return f"{value:,.3f} us"


def scaling_rows(results):
    """How each benchmark's cost grows with each numeric @Param it was run over.

    The comparison is cost-ratio vs input-ratio between the smallest and largest
    value of one parameter, holding every other parameter fixed. A factor of 1.0
    is linear (twice the input, twice the cost); above SUPERLINEAR_FACTOR the
    code is doing more than proportional work and will degrade as the network
    grows.
    """
    rows = []
    by_method = {}
    for result in results:
        by_method.setdefault((result["class"], result["method"]), []).append(result)

    for (cls, method), group in sorted(by_method.items()):
        param_names = sorted({name for r in group for name in r["params"]})
        for name in param_names:
            others = lambda r: tuple(sorted((k, v) for k, v in r["params"].items() if k != name))
            for fixed in sorted({others(r) for r in group}):
                slice_ = [r for r in group if others(r) == fixed]
                numeric = []
                for r in slice_:
                    try:
                        numeric.append((float(r["params"][name]), r))
                    except (TypeError, ValueError):
                        numeric = []
                        break
                if len(numeric) < 2:
                    continue
                numeric.sort()
                (low_in, low), (high_in, high) = numeric[0], numeric[-1]
                if low_in <= 0 or low["micros"] <= 0:
                    continue
                input_ratio = high_in / low_in
                cost_ratio = high["micros"] / low["micros"]
                factor = cost_ratio / input_ratio if input_ratio else 0.0
                rows.append({
                    "class": cls,
                    "method": method,
                    "param": name,
                    "fixed": ", ".join(f"{k}={v}" for k, v in fixed) or "—",
                    "from": low_in,
                    "to": high_in,
                    "input_ratio": input_ratio,
                    "cost_ratio": cost_ratio,
                    "factor": factor,
                    "low_micros": low["micros"],
                    "high_micros": high["micros"],
                })
    # A parameter a benchmark does not consume produces a flat line and a meaningless "sublinear"
    # verdict — `manifestRoot` does not read the holder count. Rows whose cost barely moves are
    # dropped so the table shows relationships instead of noise.
    rows = [row for row in rows if abs(row["cost_ratio"] - 1.0) >= 0.2]
    rows.sort(key=lambda r: r["factor"], reverse=True)
    return rows[:MAX_SCALING_ROWS]


def compare(results, baseline):
    """Attach the baseline delta to every result that has one."""
    for result in results:
        prior = baseline.get(result["key"])
        if not prior:
            result["delta"] = None
            continue
        before = prior["micros"]
        if before <= 0:
            result["delta"] = None
            continue
        result["baseline_micros"] = before
        result["delta"] = (result["micros"] - before) / before
    return results


def regressions(results):
    out = []
    for result in results:
        delta = result.get("delta")
        if delta is None or delta <= REGRESSION_THRESHOLD:
            continue
        # Outside its own error bars, or it is noise wearing a regression's coat.
        slowdown = result["micros"] - result["baseline_micros"]
        if slowdown <= result["error"]:
            continue
        out.append(result)
    return sorted(out, key=lambda r: r["delta"], reverse=True)


def render(results, baseline, scaling):
    results = sorted(results, key=lambda r: r["micros"], reverse=True)
    total = len(results)
    jdk = results[0]["jdk"]
    vm = results[0]["vm"]
    lines = []
    add = lines.append

    add("# Peer benchmark report")
    add("")
    add(f"Generated from `java/peer/build/results/jmh/results.json` — **{total} measurements**, "
        f"JDK {jdk} ({vm}), {results[0]['forks']} fork(s) x {results[0]['iterations']} iterations.")
    add("")
    add("Every number is average time per operation, normalised to microseconds. Lower is better. "
        "`Δ base` compares against `fixtures/bench/baseline.json`; a blank means the benchmark is "
        "new since the baseline was written.")
    add("")

    add("## 1. Where the time goes")
    add("")
    add("The ten most expensive operations in the peer system, whatever lane they are in. This is "
        "the ranked bottleneck list: optimisation work that does not start at the top of this table "
        "needs a reason.")
    add("")
    add("| # | benchmark | params | cost | ± error | lane | Δ base |")
    add("|---:|---|---|---:|---:|---|---:|")
    for i, r in enumerate(results[:10], start=1):
        add(f"| {i} | `{r['class']}.{r['method']}` | {fmt_params(r)} | {fmt_micros(r['micros'])} | "
            f"{fmt_micros(r['error'])} | {lane_of(r)} | {fmt_delta(r)} |")
    add("")

    if scaling:
        add("## 2. How cost grows with load")
        add("")
        add("Cost ratio divided by input ratio between the smallest and the largest value of each "
            f"parameter. **1.0 is linear.** Anything above {SUPERLINEAR_FACTOR} is doing more than "
            "proportional work and is a structural problem, not a tuning one — it gets worse as the "
            "network grows, which is exactly when it is hardest to fix. Parameters a benchmark "
            "does not consume are omitted — a flat line is not a growth rate.")
        add("")
        add("| benchmark | param | range | cost | growth | verdict |")
        add("|---|---|---|---|---:|:--|")
        for row in scaling:
            verdict = "🔴 superlinear" if row["factor"] > SUPERLINEAR_FACTOR else (
                "🟢 sublinear" if row["factor"] < 0.75 else "🟡 linear")
            add(f"| `{row['class']}.{row['method']}` | `{row['param']}` | "
                f"{row['from']:g} → {row['to']:g} | {fmt_micros(row['low_micros'])} → "
                f"{fmt_micros(row['high_micros'])} | {row['factor']:.2f}x | {verdict} |")
        add("")

    add("## 3. Every measurement, by lane")
    add("")
    for cls, (lane, blurb) in LANES.items():
        lane_results = [r for r in results if r["class"] == cls]
        if not lane_results:
            continue
        add(f"### {lane}")
        add("")
        add(blurb)
        add("")
        add("| benchmark | params | cost | ± error | Δ base |")
        add("|---|---|---:|---:|---:|")
        for r in lane_results:
            add(f"| `{r['method']}` | {fmt_params(r)} | {fmt_micros(r['micros'])} | "
                f"{fmt_micros(r['error'])} | {fmt_delta(r)} |")
        add("")

    unknown = [r for r in results if r["class"] not in LANES]
    if unknown:
        add("### unclassified")
        add("")
        add("These benchmark classes have no lane in `scripts/bench-report.py`. Add one, or the "
            "report will keep under-reporting the system it claims to cover.")
        add("")
        for r in unknown:
            add(f"- `{r['class']}.{r['method']}` — {fmt_micros(r['micros'])}")
        add("")

    bad = regressions(results)
    add("## 4. Regressions against the baseline")
    add("")
    if not baseline:
        add("No baseline recorded yet. Write one with "
            "`scripts/bench-report.py --write-baseline` from a run you trust.")
    elif not bad:
        add(f"None. Every benchmark is within {int(REGRESSION_THRESHOLD * 100)}% of "
            f"`fixtures/bench/baseline.json` (or inside its own error bars).")
    else:
        add(f"**{len(bad)} benchmark(s) got slower** by more than "
            f"{int(REGRESSION_THRESHOLD * 100)}% AND by more than their own measurement error:")
        add("")
        add("| benchmark | params | baseline | now | Δ |")
        add("|---|---|---:|---:|---:|")
        for r in bad:
            add(f"| `{r['class']}.{r['method']}` | {fmt_params(r)} | "
                f"{fmt_micros(r['baseline_micros'])} | {fmt_micros(r['micros'])} | "
                f"+{r['delta'] * 100:.1f}% |")
    add("")

    missing = sorted(set(baseline) - {r["key"] for r in results})
    if missing:
        add("## 5. In the baseline but not in this run")
        add("")
        add("A benchmark that stops running is a benchmark that stops protecting anything. These "
            "were measured when the baseline was written and were not measured now — either the "
            "run was filtered (`-Pbench.include`) or the benchmark was deleted.")
        add("")
        for key in missing:
            add(f"- `{key}`")
        add("")

    return "\n".join(lines) + "\n"


def fmt_params(result):
    if not result["params"]:
        return "—"
    return ", ".join(f"`{k}={v}`" for k, v in sorted(result["params"].items()))


def fmt_delta(result):
    delta = result.get("delta")
    if delta is None:
        return "—"
    sign = "+" if delta >= 0 else ""
    mark = " 🔴" if delta > REGRESSION_THRESHOLD else (" 🟢" if delta < -REGRESSION_THRESHOLD else "")
    return f"{sign}{delta * 100:.1f}%{mark}"


def main():
    parser = argparse.ArgumentParser(description="Render the peer benchmark report from a JMH run.")
    parser.add_argument("--results", type=Path, default=DEFAULT_RESULTS)
    parser.add_argument("--out", type=Path, default=DEFAULT_OUT)
    parser.add_argument("--baseline", type=Path, default=DEFAULT_BASELINE)
    parser.add_argument("--check", action="store_true",
                        help="exit non-zero if any benchmark regressed against the baseline")
    parser.add_argument("--write-baseline", action="store_true",
                        help="replace the baseline with this run's numbers")
    parser.add_argument("--summary", action="store_true",
                        help="also append the report to $GITHUB_STEP_SUMMARY")
    args = parser.parse_args()

    results = load_results(args.results)
    baseline = {}
    if args.baseline.exists():
        with args.baseline.open(encoding="utf-8") as handle:
            baseline = json.load(handle).get("benchmarks", {})

    compare(results, baseline)
    scaling = scaling_rows(results)
    report = render(results, baseline, scaling)

    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(report, encoding="utf-8")
    print(f"bench-report: wrote {args.out} ({len(results)} measurements)")

    if args.write_baseline:
        payload = {
            "note": "Written by scripts/bench-report.py --write-baseline. Machine-dependent: treat "
                    "cross-machine deltas as advisory and same-machine deltas as evidence.",
            "jdk": results[0]["jdk"],
            "vm": results[0]["vm"],
            "benchmarks": {
                r["key"]: {"micros": round(r["micros"], 6), "error": round(r["error"], 6)}
                for r in sorted(results, key=lambda r: r["key"])
            },
        }
        args.baseline.parent.mkdir(parents=True, exist_ok=True)
        args.baseline.write_text(json.dumps(payload, indent=2, sort_keys=False) + "\n",
                                 encoding="utf-8")
        print(f"bench-report: wrote baseline {args.baseline} ({len(results)} benchmarks)")

    if args.summary:
        path = os.environ.get("GITHUB_STEP_SUMMARY")
        if path:
            with open(path, "a", encoding="utf-8") as handle:
                handle.write(report)

    slowest = max(results, key=lambda r: r["micros"])
    print(f"bench-report: slowest is {slowest['class']}.{slowest['method']} "
          f"{fmt_params(slowest)} at {fmt_micros(slowest['micros'])}")
    superlinear = [r for r in scaling if r["factor"] > SUPERLINEAR_FACTOR]
    for row in superlinear:
        print(f"bench-report: superlinear — {row['class']}.{row['method']} grows "
              f"{row['factor']:.2f}x faster than its `{row['param']}`")

    if args.check:
        bad = regressions(results)
        if bad:
            for r in bad:
                print(f"bench-report: REGRESSION {r['key']} "
                      f"{fmt_micros(r['baseline_micros'])} -> {fmt_micros(r['micros'])} "
                      f"(+{r['delta'] * 100:.1f}%)", file=sys.stderr)
            return 1
        if not baseline:
            print("bench-report: --check with no baseline file; nothing to compare against",
                  file=sys.stderr)
            return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
