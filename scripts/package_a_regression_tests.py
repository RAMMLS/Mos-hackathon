#!/usr/bin/env python3
"""Regression pack for the trust-restoration tasks T02-T06."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SCRIPTS = ROOT / "scripts"
if str(SCRIPTS) not in sys.path:
    sys.path.insert(0, str(SCRIPTS))

from run_algorithm_benchmark import run_algorithm, write_json  # noqa: E402
from run_manifest import build_run_manifest, write_run_manifest  # noqa: E402


CASES = [
    ("kazan-kaban-1", "data/rl_large/scenes/fifty_fifty/094_kazan_kaban_-3_-1_f8ebdc33.geojson"),
    ("kazan-kaban-2", "data/rl_large/scenes/fifty_fifty/091_kazan_kaban_-3_-2_8f4abbd2.geojson"),
    ("moscow-nekrasovka", "data/rl_large/scenes/separate_pipes/072_moscow_nekrasovka_+0_-2_52cb6138.geojson"),
]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--service-url", default="http://127.0.0.1:8080/api/trace")
    parser.add_argument("--timeout", type=int, default=300)
    parser.add_argument("--out-dir", type=Path, default=ROOT / "results/package-a-regressions")
    args = parser.parse_args()

    inputs = [ROOT / relative for _, relative in CASES]
    algorithms = ["B2-C", "B2-C-J", "B2-U", "B3"]
    manifest = build_run_manifest(
        ROOT,
        inputs,
        algorithms,
        {
            "runner": "package_a_regression_tests.py",
            "service_url": args.service_url,
            "timeout_seconds": args.timeout,
        },
    )
    write_run_manifest(args.out_dir / "run-manifest.json", manifest)

    records = []
    failures = []
    for case_name, relative in CASES:
        input_path = ROOT / relative
        by_algorithm = {}
        for algorithm in algorithms:
            result = run_algorithm(
                algorithm, input_path, args.service_url,
                args.out_dir / "runs" / case_name, args.timeout,
            )
            by_algorithm[algorithm] = result
            records.append({"case": case_name, "input": relative, **result})
            if not result.get("benchmark_eligible"):
                failures.append(f"{case_name}/{algorithm}: {result.get('status')}")

        b2_u = by_algorithm["B2-U"]
        b2_c_j = by_algorithm["B2-C-J"]
        b3 = by_algorithm["B3"]
        verified_seeds = [item for item in (b2_u, b2_c_j) if item.get("benchmark_eligible")]
        if verified_seeds and b3.get("benchmark_eligible"):
            best_seed_score = min(item["score"] for item in verified_seeds)
            if b3["score"] > best_seed_score + 0.000001:
                failures.append(
                    f"{case_name}/B3 lost verified seed: {b3['score']} > {best_seed_score}"
                )

    payload = {
        "status": "PASS" if not failures else "FAIL",
        "configuration_id": manifest["configuration_id"],
        "failures": failures,
        "records": records,
    }
    write_json(args.out_dir / "summary.json", payload)
    print(json.dumps(payload, ensure_ascii=False, indent=2))
    return 0 if not failures else 2


if __name__ == "__main__":
    raise SystemExit(main())
