#!/usr/bin/env python3
"""Compares JMH JSON results cell by cell.

Usage: python3 -I compare.py --base <dir|json>... --new <dir|json>... [--limit 10] [--md out.md]
A cell is benchmark + params. Prints base score, new score, new/base, error %, and flags
cells where new/base exceeds 1 + limit/100 (REGRESSION) or the error exceeds 10% of the score (NOISY).
Exit code 1 if any cell regressed, so a shell loop can stop on it.
"""
import argparse
import glob
import json
import os
import sys


def load(paths):
    cells = {}
    for p in paths:
        files = sorted(glob.glob(os.path.join(p, "*.json"))) if os.path.isdir(p) else [p]
        for f in files:
            with open(f) as fh:
                try:
                    rows = json.load(fh)
                except json.JSONDecodeError:
                    continue
            for r in rows if isinstance(rows, list) else []:
                params = ",".join(f"{k}={v}" for k, v in sorted((r.get("params") or {}).items()))
                key = f"{r['benchmark'].rsplit('.', 2)[-2]}.{r['benchmark'].rsplit('.', 1)[-1]} {params}"
                m = r["primaryMetric"]

                def _num(x):
                    try:
                        return float(x)
                    except (TypeError, ValueError):
                        return 0.0

                err = _num(m.get("scoreError"))
                if err != err:  # NaN (single-iteration smoke): no usable error
                    err = 0.0
                cells[key] = (_num(m["score"]), err, m["scoreUnit"])
    return cells


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", nargs="+", required=True)
    ap.add_argument("--new", nargs="+", required=True)
    ap.add_argument("--limit", type=float, default=10.0, help="regression limit in percent")
    ap.add_argument("--md", help="also write the table to this markdown file")
    a = ap.parse_args()
    base, new = load(a.base), load(a.new)
    lines = ["| cell | base | new | new/base | new err % | flag |", "|---|---|---|---|---|---|"]
    regressed = 0
    for key in sorted(new):
        n, ne, unit = new[key]
        err = 100.0 * ne / n if n else 0.0
        if key not in base:
            lines.append(f"| {key} | - | {n:.3f} {unit} | - | {err:.1f} | NEW |")
            continue
        b = base[key][0]
        ratio = n / b if b else float("inf")
        flags = []
        if ratio > 1 + a.limit / 100:
            flags.append("REGRESSION")
            regressed += 1
        if err > 10:
            flags.append("NOISY")
        lines.append(f"| {key} | {b:.3f} | {n:.3f} {unit} | {ratio:.3f} | {err:.1f} | {' '.join(flags)} |")
    missing = sorted(set(base) - set(new))
    out = "\n".join(lines)
    print(out)
    if a.md:
        with open(a.md, "w") as fh:
            fh.write(out + "\n")
    print(f"\n{len(new)} cells, {regressed} regressed > {a.limit}%, {len(missing)} baseline cells not rerun", file=sys.stderr)
    sys.exit(1 if regressed else 0)


if __name__ == "__main__":
    main()
