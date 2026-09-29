#!/usr/bin/env python3
"""Compare routing algorithms on a deterministic stratified scene sample."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import statistics
import sys
import time
from collections import defaultdict
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SCRIPTS = ROOT / "scripts"
if str(SCRIPTS) not in sys.path:
    sys.path.insert(0, str(SCRIPTS))

from run_algorithm_benchmark import run_algorithm, write_json  # noqa: E402
from run_manifest import build_run_manifest, write_run_manifest  # noqa: E402


DEFAULT_ALGORITHMS = ["PORTFOLIO", "B1", "B2-C-J", "B3", "R1", "R2"]


def stable_order(record):
    digest = hashlib.sha256(record["scene_id"].encode("utf-8")).hexdigest()
    return digest, record["scene_id"]


def select_records(records, split, sample_per_bucket, selected_for_rl_only=False,
                   scene_ids=None):
    requested_ids = set(scene_ids or [])
    grouped = defaultdict(list)
    for record in records:
        if requested_ids and record.get("scene_id") not in requested_ids:
            continue
        if selected_for_rl_only and not record.get("selected_for_rl", False):
            continue
        if split and record.get("split") != split:
            continue
        grouped[record.get("research_bucket", "unknown")].append(record)
    selected = []
    for bucket in sorted(grouped):
        selected.extend(sorted(grouped[bucket], key=stable_order)[:sample_per_bucket])
    return selected


def percentile(values, quantile):
    if not values:
        return None
    ordered = sorted(values)
    index = min(len(ordered) - 1, max(0, math.ceil(quantile * len(ordered)) - 1))
    return ordered[index]


def winner_key(result):
    connected = result.get("connected_oks")
    score = result.get("score")
    return (
        -(connected if isinstance(connected, int) else -1),
        score if isinstance(score, (int, float)) else math.inf,
        result["algorithm"],
    )


def aggregate(scene_results, algorithms):
    stats = {
        algorithm: {
            "algorithm": algorithm,
            "runs": 0,
            "contract_valid": 0,
            "full_connection": 0,
            "wins": 0,
            "coverage_all": [],
            "coverage_valid": [],
            "connected_total": 0,
            "oks_total": 0,
            "scores": [],
            "construction_costs": [],
            "penalties": [],
            "gaps_percent": [],
            "times": [],
        }
        for algorithm in algorithms
    }
    for scene in scene_results:
        valid = [item for item in scene["algorithms"] if item.get("contract_valid")]
        if valid:
            best_connected = max(item.get("connected_oks") or 0 for item in valid)
            best_score = min(
                item.get("score", math.inf)
                for item in valid
                if (item.get("connected_oks") or 0) == best_connected
            )
            winners = [
                item for item in valid
                if best_connected > 0
                and (item.get("connected_oks") or 0) == best_connected
                and abs(item.get("score", math.inf) - best_score) <= 0.005
            ]
            for item in winners:
                stats[item["algorithm"]]["wins"] += 1
        else:
            best_connected = -1
            best_score = math.inf

        for item in scene["algorithms"]:
            target = stats[item["algorithm"]]
            target["runs"] += 1
            total = scene.get("oks_count") or item.get("total_oks") or 0
            connected = (item.get("connected_oks") or 0) if item.get("contract_valid") else 0
            if total:
                target["coverage_all"].append(connected / total)
                target["connected_total"] += connected
                target["oks_total"] += total
            elapsed = item.get("elapsed_seconds")
            if isinstance(elapsed, (int, float)):
                target["times"].append(elapsed)
            if not item.get("contract_valid"):
                continue
            target["contract_valid"] += 1
            connected = item.get("connected_oks") or 0
            total = item.get("total_oks") or 0
            if total:
                target["coverage_valid"].append(connected / total)
            if connected == total and total > 0:
                target["full_connection"] += 1
            score = item.get("score")
            if isinstance(score, (int, float)):
                target["scores"].append(score)
                if connected == best_connected and connected > 0 and math.isfinite(best_score) and best_score > 0:
                    target["gaps_percent"].append(100.0 * (score / best_score - 1.0))
            construction_cost = item.get("construction_cost")
            if isinstance(construction_cost, (int, float)):
                target["construction_costs"].append(construction_cost)
            penalty = item.get("unconnected_penalty")
            if isinstance(penalty, (int, float)):
                target["penalties"].append(penalty)

    result = []
    for algorithm in algorithms:
        item = stats[algorithm]
        result.append({
            "algorithm": algorithm,
            "runs": item["runs"],
            "contract_valid_count": item["contract_valid"],
            "full_connection_count": item["full_connection"],
            "win_count": item["wins"],
            "coverage_macro_all_percent": round(
                100.0 * statistics.mean(item["coverage_all"]), 2
            ) if item["coverage_all"] else None,
            "coverage_macro_valid_percent": round(
                100.0 * statistics.mean(item["coverage_valid"]), 2
            ) if item["coverage_valid"] else None,
            "coverage_micro_all_percent": round(
                100.0 * item["connected_total"] / item["oks_total"], 2
            ) if item["oks_total"] else None,
            "mean_score": round(statistics.mean(item["scores"]), 3) if item["scores"] else None,
            "mean_construction_cost": round(statistics.mean(item["construction_costs"]), 2)
            if item["construction_costs"] else None,
            "mean_unconnected_penalty": round(statistics.mean(item["penalties"]), 2)
            if item["penalties"] else None,
            "mean_gap_percent": round(statistics.mean(item["gaps_percent"]), 2)
            if item["gaps_percent"] else None,
            "mean_elapsed_seconds": round(statistics.mean(item["times"]), 3)
            if item["times"] else None,
            "p95_elapsed_seconds": round(percentile(item["times"], 0.95), 3)
            if item["times"] else None,
        })
    return result


def render_markdown(payload):
    lines = [
        "# Multi-scene algorithm benchmark",
        "",
        f"- generated_at: `{payload['generated_at']}`",
        f"- manifest: `{payload['manifest']}`",
        f"- run configuration: `{payload['run_manifest']}`",
        f"- configuration id: `{payload['configuration_id']}`",
        f"- split: `{payload['split']}`",
        f"- scenes: `{payload['scene_count']}`",
        "",
        "| algorithm | certified | full | wins | macro all % | macro valid % | micro all % | mean score | gap % | mean s | p95 s |",
        "| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |",
    ]
    ranking = sorted(
        payload["aggregate"],
        key=lambda item: (
            -item["full_connection_count"],
            -item["win_count"],
            -(item["coverage_macro_all_percent"] or -1),
            item["mean_gap_percent"] if item["mean_gap_percent"] is not None else math.inf,
            item["mean_elapsed_seconds"] if item["mean_elapsed_seconds"] is not None else math.inf,
        ),
    )
    for item in ranking:
        lines.append(
            "| {algorithm} | {valid}/{runs} | {full}/{runs} | {wins} | {macro_all} | {macro_valid} | {micro_all} | {score} | {gap} | {mean_time} | {p95} |".format(
                algorithm=item["algorithm"], valid=item["contract_valid_count"], runs=item["runs"],
                full=item["full_connection_count"], wins=item["win_count"],
                macro_all=item["coverage_macro_all_percent"],
                macro_valid=item["coverage_macro_valid_percent"],
                micro_all=item["coverage_micro_all_percent"], score=item["mean_score"],
                gap=item["mean_gap_percent"], mean_time=item["mean_elapsed_seconds"],
                p95=item["p95_elapsed_seconds"],
            )
        )
    lines.extend([
        "",
        "Ranking prioritizes full connection, then scene wins, coverage, score gap, and runtime.",
        "A win may be shared when scores differ by at most 0.005 after matching the best connection count.",
        "Macro all counts an uncertified request as zero connected; macro valid excludes it; micro all is target-weighted.",
        "",
        "## Cost decomposition",
        "",
        "| algorithm | mean construction RUB | mean unconnected penalty RUB |",
        "| --- | ---: | ---: |",
    ])
    for item in ranking:
        lines.append(
            "| {algorithm} | {construction} | {penalty} |".format(
                algorithm=item["algorithm"],
                construction=item["mean_construction_cost"],
                penalty=item["mean_unconnected_penalty"],
            )
        )
    lines.extend([
        "",
        "## Scenes",
        "",
        "| scene | bucket | OKS | winner | score |",
        "| --- | --- | ---: | --- | ---: |",
    ])
    for scene in payload["scenes"]:
        valid = [item for item in scene["algorithms"] if item.get("contract_valid")]
        winner = min(valid, key=winner_key) if valid and max(
            (item.get("connected_oks") or 0) for item in valid
        ) > 0 else {}
        lines.append(
            f"| {scene['scene_id']} | {scene['research_bucket']} | {scene['oks_count']} | "
            f"{winner.get('algorithm', '-')} | {winner.get('score', '-')} |"
        )
    return "\n".join(lines) + "\n"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, default=ROOT / "data/rl_large/scene_manifest.json")
    parser.add_argument("--out-dir", type=Path, default=ROOT / "results/multi-scene-algorithm-benchmark")
    parser.add_argument("--service-url", default="http://localhost:8080/api/trace")
    parser.add_argument("--timeout", type=int, default=300)
    parser.add_argument("--split", default="test")
    parser.add_argument("--sample-per-bucket", type=int, default=1)
    parser.add_argument("--selected-for-rl-only", action="store_true")
    parser.add_argument("--workers", type=int, default=1)
    parser.add_argument(
        "--ruleset",
        choices=["DOCUMENT_NEAREST_V1", "EXPERIMENTAL_ANY_BOUNDARY_V1"],
        default="DOCUMENT_NEAREST_V1",
    )
    parser.add_argument("--scene-id", action="append", dest="scene_ids")
    parser.add_argument("--algorithm", action="append", dest="algorithms")
    args = parser.parse_args()

    manifest = json.loads(args.manifest.read_text(encoding="utf-8"))
    records = select_records(
        manifest["records"], args.split, max(1, args.sample_per_bucket),
        args.selected_for_rl_only, args.scene_ids,
    )
    if not records:
        parser.error("no scenes matched the requested split/filter")
    algorithms = args.algorithms or DEFAULT_ALGORITHMS
    run_manifest_path = args.out_dir / "run-manifest.json"
    run_manifest = build_run_manifest(
        ROOT,
        [args.manifest, *[ROOT / record["input"] for record in records]],
        algorithms,
        {
            "runner": "run_multi_scene_algorithm_benchmark.py",
            "service_url": args.service_url,
            "timeout_seconds": args.timeout,
            "split": args.split,
            "sample_per_bucket": args.sample_per_bucket,
            "selected_for_rl_only": args.selected_for_rl_only,
            "workers": max(1, args.workers),
            "ruleset": args.ruleset,
            "seed": "sha256_scene_id_order_v1",
            "scene_ids_filter": args.scene_ids or [],
            "scene_ids": [record["scene_id"] for record in records],
        },
    )
    write_run_manifest(run_manifest_path, run_manifest)
    def run_scene(scene_index, record):
        print(f"scene {scene_index}/{len(records)} {record['scene_id']}", flush=True)
        algorithms_results = []
        for algorithm in algorithms:
            result = run_algorithm(
                algorithm,
                ROOT / record["input"],
                args.service_url,
                args.out_dir / "runs" / record["scene_id"],
                args.timeout,
                "AUTO",
                args.ruleset,
            )
            algorithms_results.append(result)
            print(
                f"  {algorithm}: {result.get('status')} "
                f"oks={result.get('connected_oks')}/{result.get('total_oks')} "
                f"score={result.get('score')}",
                flush=True,
            )
        return {
            "scene_id": record["scene_id"],
            "research_bucket": record["research_bucket"],
            "city": record.get("city"),
            "oks_count": record["oks_count"],
            "input": record["input"],
            "algorithms": algorithms_results,
        }

    indexed_results = {}
    worker_count = max(1, args.workers)
    if worker_count == 1:
        for scene_index, record in enumerate(records, start=1):
            indexed_results[scene_index] = run_scene(scene_index, record)
    else:
        with ThreadPoolExecutor(max_workers=worker_count) as executor:
            futures = {
                executor.submit(run_scene, scene_index, record): scene_index
                for scene_index, record in enumerate(records, start=1)
            }
            for future in as_completed(futures):
                scene_index = futures[future]
                indexed_results[scene_index] = future.result()
    scene_results = [indexed_results[index] for index in sorted(indexed_results)]

    payload = {
        "generated_at": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
        "manifest": str(args.manifest),
        "run_manifest": str(run_manifest_path),
        "configuration_id": run_manifest["configuration_id"],
        "split": args.split,
        "selected_for_rl_only": args.selected_for_rl_only,
        "workers": worker_count,
        "sample_per_bucket": args.sample_per_bucket,
        "scene_count": len(scene_results),
        "algorithms": algorithms,
        "aggregate": aggregate(scene_results, algorithms),
        "scenes": scene_results,
    }
    args.out_dir.mkdir(parents=True, exist_ok=True)
    write_json(args.out_dir / "summary.json", payload)
    (args.out_dir / "summary.md").write_text(render_markdown(payload), encoding="utf-8")
    print(args.out_dir / "summary.md")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
