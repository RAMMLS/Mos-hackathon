import argparse
import json
import time
import urllib.error
import urllib.parse
from pathlib import Path

from benchmark_checker import check
from run_benchmark_suite import post_geojson, write_json


DEFAULT_ALGORITHMS = [
    "B0-GRID", "B0-CORRIDOR", "B1", "B2-U", "B2-Q",
    "B2-C", "B2-C-J", "B3", "R1", "R2", "X0",
]


def solver_url(base_url, algorithm):
    separator = "&" if "?" in base_url else "?"
    return f"{base_url}{separator}algorithm={urllib.parse.quote(algorithm)}"


def unavailable_result(algorithm, elapsed, exc):
    payload = {}
    try:
        payload = json.loads(exc.read().decode("utf-8"))
    except (ValueError, UnicodeDecodeError):
        pass
    return {
        "algorithm": algorithm,
        "status": payload.get("status", f"HTTP_{exc.code}"),
        "message": payload.get("message", str(exc)),
        "elapsed_seconds": round(elapsed, 3),
        "contract_valid": False,
    }


def run_algorithm(algorithm, input_path, service_url, out_dir, timeout):
    algorithm_dir = out_dir / algorithm.lower().replace("-", "_")
    result_path = algorithm_dir / "result.geojson"
    report_path = algorithm_dir / "checker-report.json"
    started = time.perf_counter()
    try:
        status, payload = post_geojson(solver_url(service_url, algorithm), input_path, timeout)
    except urllib.error.HTTPError as exc:
        return unavailable_result(algorithm, time.perf_counter() - started, exc)
    except (urllib.error.URLError, TimeoutError, OSError) as exc:
        return {
            "algorithm": algorithm,
            "status": "ERROR",
            "message": str(exc),
            "elapsed_seconds": round(time.perf_counter() - started, 3),
            "contract_valid": False,
        }

    if status != 200:
        return {
            "algorithm": algorithm,
            "status": f"HTTP_{status}",
            "elapsed_seconds": round(time.perf_counter() - started, 3),
            "contract_valid": False,
        }

    algorithm_dir.mkdir(parents=True, exist_ok=True)
    result_path.write_bytes(payload)
    report = check(input_path, result_path, f"algorithm-{algorithm}-{int(time.time())}")
    write_json(report_path, report)
    costs = report.get("recomputed_cost_components", {})
    geometry = report.get("geometry_metrics", {})
    summary_feature = next(
        ((feature.get("properties") or {}) for feature in json.loads(payload).get("features", [])
         if (feature.get("properties") or {}).get("object_type") == "variant_summary"),
        {},
    )
    return {
        "algorithm": algorithm,
        "status": report.get("status", "ERROR"),
        "contract_valid": report.get("contract_valid", False),
        "connected_oks": geometry.get("connected_oks_count"),
        "total_oks": geometry.get("total_oks_count"),
        "new_network_length": costs.get("new_network_length"),
        "calculated_cost": costs.get("calculated_cost"),
        "score": costs.get("score"),
        "violation_count": len(report.get("violations", [])),
        "elapsed_seconds": round(time.perf_counter() - started, 3),
        "result": str(result_path),
        "checker_report": str(report_path),
        "diagnostics": summary_feature.get("diagnostics", []),
        "benchmark_eligible": report.get("contract_valid", False)
        and geometry.get("connected_oks_count") == geometry.get("total_oks_count"),
    }


def render_markdown(summary):
    lines = [
        "# Algorithm benchmark", "",
        f"- input: `{summary['input']}`",
        f"- generated_at: `{summary['generated_at']}`",
        f"- best_valid_algorithm: `{summary.get('best_valid_algorithm') or '-'}`", "",
        "| rank | algorithm | status | oks | length_m | cost_rub | score | time_s | violations |",
        "| ---: | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |",
    ]
    valid = sorted(
        (item for item in summary["algorithms"] if item.get("benchmark_eligible")),
        key=lambda item: (item.get("score", float("inf")), item["algorithm"]),
    )
    ranks = {item["algorithm"]: index + 1 for index, item in enumerate(valid)}
    for item in summary["algorithms"]:
        oks = "-" if item.get("connected_oks") is None else f"{item['connected_oks']}/{item['total_oks']}"
        lines.append(
            "| {rank} | {algorithm} | {status} | {oks} | {length} | {cost} | {score} | {elapsed} | {violations} |".format(
                rank=ranks.get(item["algorithm"], "-"), algorithm=item["algorithm"],
                status=item["status"], oks=oks, length=item.get("new_network_length", "-"),
                cost=item.get("calculated_cost", "-"), score=item.get("score", "-"),
                elapsed=item.get("elapsed_seconds", "-"), violations=item.get("violation_count", "-"),
            )
        )
    lines.extend([
        "", "## Interpretation", "",
        "- Ranking includes only checker-valid outputs that connect every input OKS.",
        "- `R1` is the trained experimental PPO policy over concrete parameterized actions.",
        "- `R1-PILOT` is the preserved compatibility control over whole-solver proposals.",
        "- `R2` is the trained experimental destroy/repair operator controller.",
        "- `NOT_APPLICABLE` is expected for X0 when the input has more than 6 connection points.",
        "- `IMPLEMENTED_LITE` algorithms are executable research baselines, not paper-equivalent claims.",
    ])
    return "\n".join(lines) + "\n"


def main():
    parser = argparse.ArgumentParser(description="Compare every algorithm from experiment_plan.json.")
    parser.add_argument("--input", default="data/tz_update_2026_09_19/corrected_dataset.geojson")
    parser.add_argument("--out-dir", default="results/algorithm-benchmark")
    parser.add_argument("--service-url", default="http://localhost:8080/api/trace")
    parser.add_argument("--timeout", type=int, default=180)
    parser.add_argument("--algorithm", action="append", dest="algorithms")
    args = parser.parse_args()

    input_path = Path(args.input)
    out_dir = Path(args.out_dir)
    results = []
    for algorithm in args.algorithms or DEFAULT_ALGORITHMS:
        print(f"running {algorithm}", flush=True)
        result = run_algorithm(algorithm, input_path, args.service_url, out_dir, args.timeout)
        results.append(result)
        print(f"  {result['status']} score={result.get('score', '-')}", flush=True)

    valid = sorted(
        (item for item in results if item.get("benchmark_eligible")),
        key=lambda item: (item.get("score", float("inf")), item["algorithm"]),
    )
    summary = {
        "generated_at": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
        "input": str(input_path), "service_url": args.service_url,
        "best_valid_algorithm": valid[0]["algorithm"] if valid else None,
        "valid_count": len(valid), "algorithm_count": len(results), "algorithms": results,
    }
    write_json(out_dir / "summary.json", summary)
    (out_dir / "summary.md").write_text(render_markdown(summary), encoding="utf-8")
    print(out_dir / "summary.md", flush=True)
    return 0 if valid else 2


if __name__ == "__main__":
    raise SystemExit(main())
