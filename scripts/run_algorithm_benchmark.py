import argparse
import json
import socket
import time
import urllib.error
import urllib.parse
from pathlib import Path

from benchmark_checker import check
from run_benchmark_suite import post_geojson, write_json
from run_manifest import build_run_manifest, write_run_manifest


DEFAULT_ALGORITHMS = [
    "B0-GRID", "B0-CORRIDOR", "B1", "B2-U", "B2-Q",
    "B2-C", "B2-C-J", "B3", "R1", "R2", "X0",
]


def solver_url(base_url, algorithm, timeout, entry_strategy="AUTO",
               ruleset="DOCUMENT_NEAREST_V1"):
    separator = "&" if "?" in base_url else "?"
    budget_ms = max(1_000, int(max(1, timeout - 5) * 1_000))
    return (f"{base_url}{separator}algorithm={urllib.parse.quote(algorithm)}"
            f"&budgetMs={budget_ms}"
            f"&entryStrategy={urllib.parse.quote(entry_strategy)}"
            f"&ruleset={urllib.parse.quote(ruleset)}")


def unavailable_result(algorithm, elapsed, exc):
    payload = {}
    try:
        payload = json.loads(exc.read().decode("utf-8"))
    except (ValueError, UnicodeDecodeError):
        pass
    return {
        "algorithm": algorithm,
        "status": payload.get("status", f"HTTP_{exc.code}"),
        "failure_class": payload.get("status", "HTTP_ERROR"),
        "message": payload.get("message", str(exc)),
        "elapsed_seconds": round(elapsed, 3),
        "contract_valid": False,
    }


def run_algorithm(algorithm, input_path, service_url, out_dir, timeout, entry_strategy="AUTO",
                  ruleset="DOCUMENT_NEAREST_V1"):
    algorithm_dir = out_dir / algorithm.lower().replace("-", "_")
    result_path = algorithm_dir / "result.geojson"
    report_path = algorithm_dir / "checker-report.json"
    started = time.perf_counter()
    try:
        status, payload = post_geojson(
            solver_url(service_url, algorithm, timeout, entry_strategy, ruleset), input_path, timeout)
    except urllib.error.HTTPError as exc:
        return unavailable_result(algorithm, time.perf_counter() - started, exc)
    except (TimeoutError, socket.timeout) as exc:
        return {
            "algorithm": algorithm,
            "status": "TIMEOUT",
            "failure_class": "DEADLINE_EXCEEDED",
            "message": str(exc),
            "elapsed_seconds": round(time.perf_counter() - started, 3),
            "contract_valid": False,
        }
    except urllib.error.URLError as exc:
        timed_out = isinstance(exc.reason, (TimeoutError, socket.timeout))
        return {
            "algorithm": algorithm,
            "status": "TIMEOUT" if timed_out else "NETWORK_ERROR",
            "failure_class": "DEADLINE_EXCEEDED" if timed_out else "SERVICE_UNREACHABLE",
            "message": str(exc),
            "elapsed_seconds": round(time.perf_counter() - started, 3),
            "contract_valid": False,
        }
    except OSError as exc:
        return {
            "algorithm": algorithm,
            "status": "IO_ERROR",
            "failure_class": "LOCAL_IO_ERROR",
            "message": str(exc),
            "elapsed_seconds": round(time.perf_counter() - started, 3),
            "contract_valid": False,
        }

    if status != 200:
        return {
            "algorithm": algorithm,
            "status": f"HTTP_{status}",
            "failure_class": "HTTP_ERROR",
            "elapsed_seconds": round(time.perf_counter() - started, 3),
            "contract_valid": False,
        }

    algorithm_dir.mkdir(parents=True, exist_ok=True)
    result_path.write_bytes(payload)
    report = check(
        input_path, result_path, f"algorithm-{algorithm}-{int(time.time())}", ruleset)
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
        "failure_class": None if report.get("contract_valid", False) else "CHECKER_REJECTED",
        "contract_valid": report.get("contract_valid", False),
        "connected_oks": geometry.get("connected_oks_count"),
        "total_oks": geometry.get("total_oks_count"),
        "new_network_length": costs.get("new_network_length"),
        # The checker component already includes chambers and tie-ins in this total.
        "construction_cost": costs.get("construction_cost"),
        "unconnected_penalty": costs.get("unconnected_penalty"),
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
        f"- ruleset: `{summary['ruleset']}`",
        f"- run configuration: `{summary['run_manifest']}`",
        f"- configuration id: `{summary['configuration_id']}`",
        f"- fastest among best-quality full solutions: `{summary.get('best_valid_algorithm') or '-'}`",
        f"- best-quality full solutions: `{','.join(summary.get('best_quality_algorithms', [])) or '-'}`",
        f"- best_coverage_algorithm: `{summary.get('best_coverage_algorithm') or '-'}`", "",
        "| rank | algorithm | status | oks | length_m | cost_rub | score | time_s | violations |",
        "| ---: | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |",
    ]
    valid = sorted(
        (item for item in summary["algorithms"] if item.get("benchmark_eligible")),
        key=lambda item: (
            item.get("score", float("inf")),
            item.get("elapsed_seconds", float("inf")),
            item["algorithm"],
        ),
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
    parser.add_argument(
        "--entry-strategy",
        choices=["AUTO", "DIRECT_ALLOWED", "PORTAL_ONLY"],
        default="AUTO",
        help="Force one endpoint-entry policy for an A/B benchmark.",
    )
    parser.add_argument(
        "--ruleset",
        choices=["DOCUMENT_NEAREST_V1", "EXPERIMENTAL_ANY_BOUNDARY_V1"],
        default="DOCUMENT_NEAREST_V1",
        help="Geometry ruleset shared by solver and independent checker.",
    )
    args = parser.parse_args()

    input_path = Path(args.input)
    out_dir = Path(args.out_dir)
    algorithms = args.algorithms or DEFAULT_ALGORITHMS
    run_manifest_path = out_dir / "run-manifest.json"
    run_manifest = build_run_manifest(
        Path(__file__).resolve().parents[1],
        [input_path],
        algorithms,
        {
            "runner": "run_algorithm_benchmark.py",
            "service_url": args.service_url,
            "timeout_seconds": args.timeout,
            "entry_strategy": args.entry_strategy,
            "ruleset": args.ruleset,
        },
    )
    write_run_manifest(run_manifest_path, run_manifest)
    results = []
    for algorithm in algorithms:
        print(f"running {algorithm}", flush=True)
        result = run_algorithm(
            algorithm, input_path, args.service_url, out_dir, args.timeout,
            args.entry_strategy, args.ruleset)
        results.append(result)
        print(f"  {result['status']} score={result.get('score', '-')}", flush=True)

    valid = sorted(
        (item for item in results if item.get("benchmark_eligible")),
        key=lambda item: (
            item.get("score", float("inf")),
            item.get("elapsed_seconds", float("inf")),
            item["algorithm"],
        ),
    )
    coverage_ranking = sorted(
        (item for item in results if item.get("contract_valid")),
        key=lambda item: (
            -(item.get("connected_oks") if item.get("connected_oks") is not None else -1),
            item.get("score", float("inf")),
            item["algorithm"],
        ),
    )
    summary = {
        "generated_at": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
        "input": str(input_path), "service_url": args.service_url,
        "ruleset": args.ruleset,
        "run_manifest": str(run_manifest_path),
        "configuration_id": run_manifest["configuration_id"],
        "best_valid_algorithm": valid[0]["algorithm"] if valid else None,
        "best_quality_algorithms": [
            item["algorithm"] for item in valid
            if abs(item.get("score", float("inf")) - valid[0]["score"]) <= 1e-9
        ] if valid else [],
        "best_coverage_algorithm": coverage_ranking[0]["algorithm"] if coverage_ranking else None,
        "valid_count": len(valid), "algorithm_count": len(results), "algorithms": results,
    }
    write_json(out_dir / "summary.json", summary)
    (out_dir / "summary.md").write_text(render_markdown(summary), encoding="utf-8")
    print(out_dir / "summary.md", flush=True)
    return 0 if valid else 2


if __name__ == "__main__":
    raise SystemExit(main())
