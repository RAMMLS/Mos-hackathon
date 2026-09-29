#!/usr/bin/env python3
"""Validate every prepared scene with the real Java parser and solver."""

from __future__ import annotations

import argparse
import json
import sys
from collections import Counter
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SCRIPTS = ROOT / "scripts"
if str(SCRIPTS) not in sys.path:
    sys.path.insert(0, str(SCRIPTS))

from run_algorithm_benchmark import run_algorithm  # noqa: E402


def validate_record(record, algorithm, service_url, work_dir, timeout):
    result = run_algorithm(
        algorithm,
        ROOT / record["input"],
        service_url,
        work_dir / record["scene_id"],
        timeout,
    )
    return {
        "scene_id": record["scene_id"],
        "split": record["split"],
        "seed": record["seed"],
        "research_bucket": record["research_bucket"],
        "input": record["input"],
        "status": result.get("status"),
        "contract_valid": bool(result.get("contract_valid")),
        "benchmark_eligible": bool(result.get("benchmark_eligible")),
        "connected_oks": result.get("connected_oks"),
        "total_oks": result.get("total_oks"),
        "score": result.get("score"),
        "elapsed_seconds": result.get("elapsed_seconds"),
        "message": result.get("message"),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="Validate the large routing scene corpus through the API.")
    parser.add_argument("--manifest", type=Path, default=ROOT / "data/rl_large/scene_manifest.json")
    parser.add_argument("--out", type=Path, default=ROOT / "results/rl-large-validation/b1-validation.json")
    parser.add_argument("--work-dir", type=Path, default=ROOT / "results/rl-large-validation/runs")
    parser.add_argument("--service-url", default="http://localhost:8080/api/trace")
    parser.add_argument("--algorithm", default="B1")
    parser.add_argument("--workers", type=int, default=6)
    parser.add_argument("--timeout", type=int, default=60)
    parser.add_argument(
        "--retry-errors-from",
        type=Path,
        help="Re-run only ERROR records from this report and merge the new results into it.",
    )
    args = parser.parse_args()

    manifest_path = args.manifest.resolve()
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    all_records = manifest["records"]
    previous = None
    records = all_records
    if args.retry_errors_from:
        previous = json.loads(args.retry_errors_from.read_text(encoding="utf-8"))
        retry_ids = {
            item["scene_id"] for item in previous["records"] if item.get("status") == "ERROR"
        }
        records = [record for record in all_records if record["scene_id"] in retry_ids]
        print(f"Retrying {len(records)} ERROR scenes from {args.retry_errors_from}", flush=True)
    results = []
    with ThreadPoolExecutor(max_workers=max(1, args.workers)) as executor:
        futures = {
            executor.submit(
                validate_record, record, args.algorithm, args.service_url, args.work_dir, args.timeout
            ): record
            for record in records
        }
        for completed, future in enumerate(as_completed(futures), start=1):
            result = future.result()
            results.append(result)
            print(
                f"[{completed}/{len(records)}] {result['scene_id']}: {result['status']} "
                f"oks={result.get('connected_oks')}/{result.get('total_oks')}",
                flush=True,
            )

    if previous is not None:
        replacements = {item["scene_id"]: item for item in results}
        results = [
            replacements.get(item["scene_id"], item) for item in previous["records"]
        ]
    results.sort(key=lambda item: item["scene_id"])
    eligible = [item for item in results if item["benchmark_eligible"]]
    summary = {
        "algorithm": args.algorithm,
        "scene_count": len(results),
        "contract_valid_count": sum(item["contract_valid"] for item in results),
        "benchmark_eligible_count": len(eligible),
        "ineligible_count": len(results) - len(eligible),
        "eligible_by_split": dict(sorted(Counter(item["split"] for item in eligible).items())),
        "eligible_by_split_and_bucket": {
            f"{split}/{bucket}": count
            for (split, bucket), count in sorted(
                Counter((item["split"], item["research_bucket"]) for item in eligible).items()
            )
        },
    }
    payload = {
        "validation_version": "osm_building_anchored_validation_v2",
        "manifest": str(manifest_path.relative_to(ROOT)),
        "service_url": args.service_url,
        "summary": summary,
        "records": results,
    }
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    print(args.out)
    return 0 if len(results) == len(all_records) and summary["contract_valid_count"] == len(all_records) else 2


if __name__ == "__main__":
    raise SystemExit(main())
