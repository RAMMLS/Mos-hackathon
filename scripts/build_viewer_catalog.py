#!/usr/bin/env python3
"""Publish a compact dataset catalog consumed by the web viewer."""

from __future__ import annotations

import argparse
import json
from collections import Counter
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def main() -> int:
    parser = argparse.ArgumentParser(description="Build the viewer dataset catalog.")
    parser.add_argument("--manifest", type=Path, default=ROOT / "data/rl_large/scene_manifest.json")
    parser.add_argument("--dataset", type=Path, default=ROOT / "data/r1_large/solver_dataset.json")
    parser.add_argument(
        "--validation", type=Path,
        default=ROOT / "results/rl-large-validation/b1-validation.json",
    )
    parser.add_argument("--out", type=Path, default=ROOT / "data/rl_large/catalog.json")
    args = parser.parse_args()

    manifest = json.loads(args.manifest.read_text(encoding="utf-8"))
    dataset = json.loads(args.dataset.read_text(encoding="utf-8"))
    validation = json.loads(args.validation.read_text(encoding="utf-8"))
    labels = {record["scene_id"]: record for record in dataset["records"]}
    checks = {record["scene_id"]: record for record in validation["records"]}

    records = []
    for source in manifest["records"]:
        label = labels.get(source["scene_id"])
        check = checks.get(source["scene_id"], {})
        algorithms = {}
        if label:
            algorithms["B1"] = {
                "status": "VALID",
                "benchmark_eligible": True,
                "score": label["initial_score"],
            }
            algorithms.update(label.get("proposals") or {})
        records.append({
            "scene_id": source["scene_id"],
            "split": source["split"],
            "seed": source["seed"],
            "city": source["city"],
            "research_bucket": source["research_bucket"],
            "input": source["input"],
            "feature_count": source["feature_count"],
            "restriction_count": source["restriction_count"],
            "oks_count": source["oks_count"],
            "selected_for_rl": label is not None,
            "total_flow_tph": label.get("total_flow_tph") if label else None,
            "b1": {
                "status": check.get("status"),
                "contract_valid": bool(check.get("contract_valid")),
                "benchmark_eligible": bool(check.get("benchmark_eligible")),
                "connected_oks": check.get("connected_oks"),
                "total_oks": check.get("total_oks"),
                "score": check.get("score"),
            },
            "algorithms": algorithms,
        })

    selected = [record for record in records if record["selected_for_rl"]]
    summary = {
        "scene_count": len(records),
        "parser_accepted_count": len(records),
        "contract_valid_count": sum(record["b1"]["contract_valid"] for record in records),
        "benchmark_eligible_count": sum(record["b1"]["benchmark_eligible"] for record in records),
        "rl_scene_count": len(selected),
        "split_counts": dict(sorted(Counter(record["split"] for record in selected).items())),
        "bucket_counts": dict(sorted(Counter(record["research_bucket"] for record in selected).items())),
        "cities": sorted({record["city"] for record in records}),
    }
    payload = {
        "catalog_version": "heat_route_viewer_catalog_v2",
        "dataset_version": manifest["dataset_version"],
        "summary": summary,
        "records": records,
    }
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(payload, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    print(args.out)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
