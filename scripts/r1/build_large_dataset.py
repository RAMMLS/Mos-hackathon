#!/usr/bin/env python3
"""Build a larger checked solver-proposal dataset for R1 and R2 training."""

from __future__ import annotations

import argparse
import json
import sys
from collections import Counter, defaultdict
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SCRIPTS = ROOT / "scripts"
if str(SCRIPTS) not in sys.path:
    sys.path.insert(0, str(SCRIPTS))

from r1.build_dataset import evaluate_scene, sha256  # noqa: E402
from r1.core import assert_no_split_leakage, canonical_hash  # noqa: E402


TARGETS = {"train": 18, "validation": 5, "test": 5}  # per research bucket


def select_records(manifest, validation, excluded_scene_ids=()):
    excluded_scene_ids = set(excluded_scene_ids)
    valid_ids = {
        item["scene_id"] for item in validation["records"]
        if item.get("benchmark_eligible") and item["scene_id"] not in excluded_scene_ids
    }
    groups = defaultdict(list)
    for record in manifest["records"]:
        if record["scene_id"] in valid_ids:
            groups[(record["split"], record["research_bucket"])].append(record)

    selected = []
    for split in ("train", "validation", "test"):
        for bucket in ("separate_pipes", "shared_pipe", "fifty_fifty", "refusal"):
            candidates = sorted(groups[(split, bucket)], key=lambda item: item["scene_id"])
            target = TARGETS[split]
            if len(candidates) < target:
                raise ValueError(
                    f"not enough eligible scenes for {split}/{bucket}: {len(candidates)} < {target}"
                )
            by_seed = defaultdict(list)
            for candidate in candidates:
                by_seed[candidate["seed"]].append(candidate)
            chosen = []
            while len(chosen) < target:
                made_progress = False
                for seed in sorted(by_seed):
                    if by_seed[seed] and len(chosen) < target:
                        chosen.append(by_seed[seed].pop(0))
                        made_progress = True
                if not made_progress:
                    break
            selected.extend(chosen)
    return selected


def evaluate(record, service_url, work_dir, timeout):
    cache_path = work_dir / "records" / f"{record['scene_id']}.json"
    input_path = ROOT / record["input"]
    if cache_path.exists():
        cached = json.loads(cache_path.read_text(encoding="utf-8"))
        if cached.get("input_hash") == sha256(input_path):
            cached["input"] = record["input"]
            cached["seed"] = record["seed"]
            cached["city"] = record["city"]
            cached["research_bucket"] = record["research_bucket"]
            return cached
    result = evaluate_scene(
        record["scene_id"],
        record["parent_scene_id"],
        record["split"],
        input_path,
        service_url,
        work_dir,
        timeout,
    )
    result["input"] = record["input"]
    result["seed"] = record["seed"]
    result["city"] = record["city"]
    result["research_bucket"] = record["research_bucket"]
    cache_path.parent.mkdir(parents=True, exist_ok=True)
    cache_path.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description="Build the checked 112-scene R1/R2 dataset.")
    parser.add_argument("--manifest", type=Path, default=ROOT / "data/rl_large/scene_manifest.json")
    parser.add_argument(
        "--validation", type=Path,
        default=ROOT / "results/rl-large-validation/b1-validation.json",
    )
    parser.add_argument("--out", type=Path, default=ROOT / "data/r1_large/solver_dataset.json")
    parser.add_argument("--work-dir", type=Path, default=ROOT / "results/r1-large-dataset")
    parser.add_argument("--service-url", default="http://localhost:8080/api/trace")
    parser.add_argument("--workers", type=int, default=4)
    parser.add_argument("--timeout", type=int, default=120)
    parser.add_argument(
        "--exclude-scene",
        action="append",
        default=[],
        help="Scene rejected by a newer validation run; may be supplied more than once.",
    )
    args = parser.parse_args()

    manifest = json.loads(args.manifest.read_text(encoding="utf-8"))
    validation = json.loads(args.validation.read_text(encoding="utf-8"))
    selected = select_records(manifest, validation, args.exclude_scene)
    records = []
    with ThreadPoolExecutor(max_workers=max(1, args.workers)) as executor:
        futures = {
            executor.submit(evaluate, record, args.service_url, args.work_dir, args.timeout): record
            for record in selected
        }
        for completed, future in enumerate(as_completed(futures), start=1):
            record = future.result()
            records.append(record)
            print(f"[{completed}/{len(selected)}] {record['scene_id']}", flush=True)

    records.sort(key=lambda item: (item["split"], item["research_bucket"], item["scene_id"]))
    assert_no_split_leakage(records)
    payload = {
        "dataset_version": "r1_building_anchored_dataset_v2",
        "description": (
            "Checked B1/B2/B3 proposals on 112 geographically separated OSM routing scenes. "
            "Consumers are anchored to real OSM buildings; demand and seed network are synthetic proxies."
        ),
        "source_manifest": str(args.manifest.relative_to(ROOT)),
        "source_manifest_hash": canonical_hash(manifest),
        "selection_policy": (
            "B1 connects all OKS; 18/5/5 scenes per bucket for train/validation/test; "
            "round-robin selection across geographic seeds"
        ),
        "excluded_scenes": sorted(args.exclude_scene),
        "records": records,
        "summary": {
            "record_count": len(records),
            "split_counts": dict(sorted(Counter(item["split"] for item in records).items())),
            "bucket_counts": dict(sorted(Counter(item["research_bucket"] for item in records).items())),
            "parent_count": len({item["parent_scene_id"] for item in records}),
        },
    }
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(payload["summary"], ensure_ascii=False, indent=2))
    print(args.out)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
