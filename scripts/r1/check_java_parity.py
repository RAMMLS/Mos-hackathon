import argparse
import json
import sys
from pathlib import Path

import numpy as np


ROOT = Path(__file__).resolve().parents[2]
SCRIPTS = ROOT / "scripts"
if str(SCRIPTS) not in sys.path:
    sys.path.insert(0, str(SCRIPTS))

from run_algorithm_benchmark import run_algorithm, write_json  # noqa: E402
from scripts.r1.core import ACTION_NAMES, LinearMaskedActorCritic, R1PilotEnvironment
from scripts.r1.ppo import collect_rollouts


ALGORITHMS = ("B1", "B2-U", "B2-Q", "B2-C", "B2-C-J", "B3", "R1-PILOT")


def input_stats(path):
    data = json.loads(Path(path).read_text(encoding="utf-8"))
    points = [
        feature for feature in data["features"]
        if (feature.get("properties") or {}).get("object_type") == "oks_connection_point"
    ]
    return len(points), sum(float((point.get("properties") or {}).get("flow_tph") or 0.0) for point in points)


def diagnostic_value(diagnostics, prefix):
    for item in diagnostics:
        if item.startswith(prefix):
            return item[len(prefix):]
    return None


def main():
    parser = argparse.ArgumentParser(description="Check Python/Java R1 pilot forward parity.")
    parser.add_argument("--input", default="data/tz_update_2026_09_19/corrected_dataset.geojson")
    parser.add_argument("--model", default="models/r1_pilot_v1/model.json")
    parser.add_argument("--service-url", default="http://localhost:8080/api/trace")
    parser.add_argument("--out-dir", default="results/r1-pilot/parity")
    parser.add_argument("--timeout", type=int, default=180)
    args = parser.parse_args()

    input_path = Path(args.input)
    out_dir = Path(args.out_dir)
    results = {}
    for algorithm in ALGORITHMS:
        print(f"running parity candidate {algorithm}", flush=True)
        result = run_algorithm(algorithm, input_path, args.service_url, out_dir / "runs", args.timeout)
        results[algorithm] = result
        if not result.get("benchmark_eligible"):
            raise RuntimeError(f"{algorithm} is not benchmark eligible: {result}")

    oks_count, total_flow = input_stats(input_path)
    scene = {
        "scene_id": "java_parity",
        "parent_scene_id": "java_parity",
        "split": "parity",
        "oks_count": oks_count,
        "total_flow_tph": total_flow,
        "initial_score": results["B1"]["score"],
        "proposals": {
            algorithm: {
                "status": results[algorithm]["status"],
                "benchmark_eligible": results[algorithm]["benchmark_eligible"],
                "score": results[algorithm]["score"],
                "result_hash": algorithm,
            }
            for algorithm in ACTION_NAMES
            if algorithm != "STOP"
        },
    }
    model, manifest = LinearMaskedActorCritic.load(args.model)
    environment = R1PilotEnvironment(scene)
    initial_state, initial_snapshot = environment.observe()
    python_logits, _, _ = model.forward(
        initial_state, initial_snapshot.features, initial_snapshot.mask
    )
    _, python_summary = collect_rollouts(
        model, [scene], np.random.default_rng(1), deterministic=True
    )
    python_sequence = [ACTION_NAMES[index] for index in python_summary[0]["selected_actions"]]
    java_diagnostics = results["R1-PILOT"]["diagnostics"]
    java_sequence = diagnostic_value(java_diagnostics, "R1-PILOT action_sequence=").split(",")
    java_model_hash = diagnostic_value(java_diagnostics, "R1-PILOT model_hash=")
    java_initial_state = np.asarray([
        float(value) for value in diagnostic_value(
            java_diagnostics, "R1-PILOT initial_state="
        ).split(",")
    ])
    state_match = bool(np.allclose(initial_state, java_initial_state, rtol=1.0e-12, atol=1.0e-12))
    java_logits = np.asarray([
        float(value) for value in diagnostic_value(
            java_diagnostics, "R1-PILOT initial_logits="
        ).split(",")
    ])
    logits_match = bool(np.allclose(python_logits, java_logits, rtol=1.0e-12, atol=1.0e-12))
    report = {
        "status": "MATCH" if python_sequence == java_sequence
        and java_model_hash == manifest["weights_hash"]
        and state_match and logits_match else "MISMATCH",
        "python_sequence": python_sequence,
        "java_sequence": java_sequence,
        "python_model_hash": manifest["weights_hash"],
        "java_model_hash": java_model_hash,
        "python_initial_state": initial_state.tolist(),
        "java_initial_state": java_initial_state.tolist(),
        "initial_state_match": state_match,
        "python_initial_logits": python_logits.tolist(),
        "java_initial_logits": java_logits.tolist(),
        "initial_logits_match": logits_match,
        "java_checker_status": results["R1-PILOT"]["status"],
        "java_connected_oks": results["R1-PILOT"]["connected_oks"],
        "java_total_oks": results["R1-PILOT"]["total_oks"],
        "java_score": results["R1-PILOT"]["score"],
        "java_violation_count": results["R1-PILOT"]["violation_count"],
        "java_elapsed_seconds": results["R1-PILOT"]["elapsed_seconds"],
    }
    write_json(out_dir / "parity-report.json", report)
    print(json.dumps(report, ensure_ascii=False, indent=2))
    if report["status"] != "MATCH":
        raise SystemExit(2)


if __name__ == "__main__":
    main()
