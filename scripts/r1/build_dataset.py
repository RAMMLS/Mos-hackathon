import argparse
import hashlib
import json
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SCRIPTS = ROOT / "scripts"
if str(SCRIPTS) not in sys.path:
    sys.path.insert(0, str(SCRIPTS))

from run_algorithm_benchmark import run_algorithm  # noqa: E402


ALGORITHMS = ("B1", "B2-U", "B2-Q", "B2-C", "B2-C-J", "B3")


def sha256(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def load(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def scene_stats(geojson):
    points = [
        feature for feature in geojson["features"]
        if (feature.get("properties") or {}).get("object_type") == "oks_connection_point"
    ]
    return len(points), sum(float((item.get("properties") or {}).get("flow_tph") or 0.0) for item in points)


def write_subset(source, ids, path):
    selected = set(ids)
    features = []
    for feature in source["features"]:
        props = feature.get("properties") or {}
        if props.get("object_type") == "oks_connection_point" and str(props.get("id")) not in selected:
            continue
        features.append(feature)
    value = {"type": "FeatureCollection", "features": features}
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False), encoding="utf-8")
    return value


def evaluate_scene(scene_id, parent_scene_id, split, input_path, service_url, out_dir, timeout):
    geojson = load(input_path)
    oks_count, total_flow = scene_stats(geojson)
    results = {}
    for algorithm in ALGORITHMS:
        result = run_algorithm(
            algorithm,
            Path(input_path),
            service_url,
            out_dir / "solver-runs" / scene_id,
            timeout,
        )
        results[algorithm] = result
        print(f"  {scene_id} {algorithm}: {result['status']} score={result.get('score', '-')}", flush=True)
    initial = results["B1"]
    if not initial.get("benchmark_eligible"):
        raise RuntimeError(f"B1 seed is not valid for {scene_id}: {initial}")
    proposals = {}
    for algorithm in ALGORITHMS[1:]:
        result = results[algorithm]
        result_path = result.get("result")
        proposals[algorithm] = {
            "status": result["status"],
            "benchmark_eligible": bool(result.get("benchmark_eligible")),
            "score": result.get("score"),
            "calculated_cost": result.get("calculated_cost"),
            "new_network_length": result.get("new_network_length"),
            "result_hash": sha256(result_path) if result_path else None,
        }
    return {
        "scene_id": scene_id,
        "parent_scene_id": parent_scene_id,
        "split": split,
        "input": str(input_path),
        "input_hash": sha256(input_path),
        "oks_count": oks_count,
        "total_flow_tph": round(total_flow, 4),
        "initial_algorithm": "B1",
        "initial_score": initial["score"],
        "proposals": proposals,
    }


def main():
    parser = argparse.ArgumentParser(description="Build real-solver scenes for the R1 pilot.")
    parser.add_argument("--service-url", default="http://localhost:8080/api/trace")
    parser.add_argument("--timeout", type=int, default=180)
    parser.add_argument("--out", default="data/r1_pilot/pilot_dataset.json")
    parser.add_argument("--work-dir", default="results/r1-pilot")
    args = parser.parse_args()

    work_dir = Path(args.work_dir)
    source_path = ROOT / "data/tz_update_2026_09_19/corrected_dataset.geojson"
    source = load(source_path)
    ordered_ids = [
        str((feature.get("properties") or {}).get("id"))
        for feature in source["features"]
        if (feature.get("properties") or {}).get("object_type") == "oks_connection_point"
    ]
    subset_sizes = (3, 5, 8, 12)
    records = []
    for size in subset_sizes:
        path = work_dir / "scenes" / f"official_subset_{size}.geojson"
        write_subset(source, ordered_ids[:size], path)
        records.append(evaluate_scene(
            f"official_subset_{size}", "official_corrected_dataset", "train",
            path, args.service_url, work_dir, args.timeout,
        ))

    junction_beneficial = ROOT / "data/benchmark_fixtures/junction_beneficial/input.geojson"
    records.append(evaluate_scene(
        "junction_beneficial", "junction_beneficial", "train",
        junction_beneficial, args.service_url, work_dir, args.timeout,
    ))

    smoke = ROOT / "data/benchmark_fixtures/new_tz_smoke/input.geojson"
    records.append(evaluate_scene(
        "new_tz_smoke", "new_tz_smoke", "validation",
        smoke, args.service_url, work_dir, args.timeout,
    ))
    junction_negative = ROOT / "data/benchmark_fixtures/junction_not_beneficial/input.geojson"
    records.append(evaluate_scene(
        "junction_not_beneficial", "junction_not_beneficial", "validation",
        junction_negative, args.service_url, work_dir, args.timeout,
    ))
    kommunarka = ROOT / "data/research_examples/02_shared_pipe_moscow_kommunarka.geojson"
    records.append(evaluate_scene(
        "research_kommunarka", "research_kommunarka", "validation",
        kommunarka, args.service_url, work_dir, args.timeout,
    ))
    payload = {
        "dataset_version": "r1_pilot_dataset_v3",
        "description": (
            "Pilot only: real independently checked B1/B2/B3 proposals. "
            "Validation uses independent smoke and research parents."
        ),
        "records": records,
    }
    output = Path(args.out)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    print(output)


if __name__ == "__main__":
    main()
