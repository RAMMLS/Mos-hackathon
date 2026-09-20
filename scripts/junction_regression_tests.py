import argparse
import json
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SCRIPTS = ROOT / "scripts"
if str(SCRIPTS) not in sys.path:
    sys.path.insert(0, str(SCRIPTS))

from benchmark_checker import check  # noqa: E402
from run_algorithm_benchmark import run_algorithm, write_json  # noqa: E402


def load(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def features_of_type(path, object_type):
    return [
        feature for feature in load(path)["features"]
        if (feature.get("properties") or {}).get("object_type") == object_type
    ]


def evaluate_case(name, input_path, out_dir, service_url, timeout):
    results = {
        algorithm: run_algorithm(
            algorithm, input_path, service_url, out_dir / name, timeout
        )
        for algorithm in ("B2-C", "B2-C-J")
    }
    for algorithm, result in results.items():
        if not result.get("benchmark_eligible"):
            raise AssertionError(f"{name}/{algorithm} is not checker-valid: {result}")
    return results


def main():
    parser = argparse.ArgumentParser(description="Regression tests for JUNCTION_IN_EDGE.")
    parser.add_argument("--service-url", default="http://localhost:8080/api/trace")
    parser.add_argument("--timeout", type=int, default=120)
    parser.add_argument("--out-dir", default="results/junction-regression")
    args = parser.parse_args()

    out_dir = Path(args.out_dir)
    beneficial = evaluate_case(
        "beneficial",
        ROOT / "data/benchmark_fixtures/junction_beneficial/input.geojson",
        out_dir,
        args.service_url,
        args.timeout,
    )
    negative = evaluate_case(
        "not-beneficial",
        ROOT / "data/benchmark_fixtures/junction_not_beneficial/input.geojson",
        out_dir,
        args.service_url,
        args.timeout,
    )

    if beneficial["B2-C-J"]["score"] >= beneficial["B2-C"]["score"]:
        raise AssertionError("beneficial fixture did not improve over frozen B2-C")
    beneficial_result = beneficial["B2-C-J"]["result"]
    technical_nodes = features_of_type(beneficial_result, "technical_node")
    junction_chambers = [
        feature for feature in features_of_type(beneficial_result, "heat_chamber")
        if str((feature.get("properties") or {}).get("id", "")).startswith("ch_branch_ch_junction_")
    ]
    shared_segments = [
        feature for feature in features_of_type(beneficial_result, "heat_network")
        if float((feature.get("properties") or {}).get("flow_tph") or 0.0) >= 10.0
        and int((feature.get("properties") or {}).get("diameter") or 0) >= 80
    ]
    if len(technical_nodes) != 1 or len(junction_chambers) != 1 or not shared_segments:
        raise AssertionError("junction export or shared-flow diameter recalculation is missing")

    mutation_dir = out_dir / "mutations"
    beneficial_payload = load(beneficial_result)
    missing_chamber = dict(beneficial_payload)
    missing_chamber["features"] = [
        feature for feature in beneficial_payload["features"]
        if not str((feature.get("properties") or {}).get("id", "")).startswith(
            "ch_branch_ch_junction_"
        )
    ]
    missing_chamber_path = mutation_dir / "missing-junction-chamber.geojson"
    write_json(missing_chamber_path, missing_chamber)
    missing_report = check(
        ROOT / "data/benchmark_fixtures/junction_beneficial/input.geojson",
        missing_chamber_path,
        "junction-missing-chamber",
    )
    missing_codes = {item["code"] for item in missing_report["violations"]}
    if "BRANCH_CHAMBER_MISSING" not in missing_codes:
        raise AssertionError("checker accepted a junction without a branch chamber")

    undersized = load(beneficial_result)
    for feature in undersized["features"]:
        props = feature.get("properties") or {}
        if str(props.get("id", "")).startswith("ch_branch_ch_junction_"):
            props["diameter"] = 65
    undersized_path = mutation_dir / "undersized-junction-chamber.geojson"
    write_json(undersized_path, undersized)
    undersized_report = check(
        ROOT / "data/benchmark_fixtures/junction_beneficial/input.geojson",
        undersized_path,
        "junction-undersized-chamber",
    )
    undersized_codes = {item["code"] for item in undersized_report["violations"]}
    if "CHAMBER_DIAMETER_TOO_SMALL" not in undersized_codes:
        raise AssertionError("checker accepted an undersized junction chamber")

    if negative["B2-C-J"]["score"] != negative["B2-C"]["score"]:
        raise AssertionError("non-beneficial fixture did not preserve the frozen control score")
    if features_of_type(negative["B2-C-J"]["result"], "technical_node"):
        raise AssertionError("non-beneficial fixture unexpectedly accepted a junction")

    report = {
        "status": "PASS",
        "beneficial": {
            "control_score": beneficial["B2-C"]["score"],
            "junction_score": beneficial["B2-C-J"]["score"],
            "technical_nodes": len(technical_nodes),
            "junction_chambers": len(junction_chambers),
            "shared_segment_diameter_verified": True,
            "missing_chamber_mutation_detected": True,
            "undersized_chamber_mutation_detected": True,
        },
        "not_beneficial": {
            "control_score": negative["B2-C"]["score"],
            "junction_score": negative["B2-C-J"]["score"],
            "technical_nodes": 0,
            "control_preserved": True,
        },
    }
    write_json(out_dir / "report.json", report)
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
