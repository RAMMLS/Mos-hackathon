import argparse
import json
import sys
import time
import urllib.error
import urllib.request
import uuid
from collections import Counter
from pathlib import Path

from benchmark_checker import check


DEFAULT_SERVICE_URL = "http://localhost:8080/api/trace"
DEFAULT_MANIFEST = Path("data/tz_update_2026_09_19/benchmark_cases.json")
DEFAULT_OUT_DIR = Path("results/benchmark-suite")
OFFICIAL_CASE = {
    "slug": "00_official_dataset",
    "title": "Official hackathon dataset",
    "city": "Moscow",
    "expected_decision": "unknown",
    "geojson": "data/Датасет/!!!_Датасет.geojson",
}


def load_cases(manifest_path, include_official):
    cases = []
    if include_official and Path(OFFICIAL_CASE["geojson"]).exists():
        cases.append(dict(OFFICIAL_CASE))
    if manifest_path.exists():
        cases.extend(json.loads(manifest_path.read_text(encoding="utf-8")))
    return cases


def post_geojson(service_url, input_path, timeout_seconds):
    boundary = f"----heatnetwork-{uuid.uuid4().hex}"
    file_bytes = Path(input_path).read_bytes()
    filename = Path(input_path).name
    body = b"".join(
        [
            f"--{boundary}\r\n".encode("utf-8"),
            f'Content-Disposition: form-data; name="file"; filename="{filename}"\r\n'.encode("utf-8"),
            b"Content-Type: application/geo+json\r\n\r\n",
            file_bytes,
            b"\r\n",
            f"--{boundary}--\r\n".encode("utf-8"),
        ]
    )
    request = urllib.request.Request(
        service_url,
        data=body,
        headers={"Content-Type": f"multipart/form-data; boundary={boundary}"},
        method="POST",
    )
    with urllib.request.urlopen(request, timeout=timeout_seconds) as response:
        return response.status, response.read()


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding="utf-8")


def classify_topology(report):
    metrics = report.get("geometry_metrics", {})
    connected = metrics.get("connected_oks_count", 0)
    total = metrics.get("total_oks_count", 0)
    per_oks = report.get("per_oks", [])
    segment_usage = Counter()
    for item in per_oks:
        for segment_id in item.get("path_segment_ids", []):
            segment_usage[segment_id] += 1

    shared_segment_count = sum(1 for count in segment_usage.values() if count > 1)
    max_segment_usage = max(segment_usage.values(), default=0)
    tie_in_count = metrics.get("tie_in_count", 0)

    if connected < total:
        decision = "refusal_candidate"
    elif shared_segment_count == 0:
        decision = "separate_pipes"
    elif metrics.get("existing_chamber_tie_in_count", 0) > 1 or tie_in_count > 1:
        decision = "mixed_or_borderline"
    else:
        decision = "shared_pipe"

    return {
        "actual_decision": decision,
        "shared_segment_count": shared_segment_count,
        "max_segment_usage": max_segment_usage,
        "tie_in_count": tie_in_count,
    }


def expectation_matches(expected, topology, report):
    if expected in (None, "", "unknown"):
        return True
    if report.get("status") != "VALID":
        return False

    actual = topology["actual_decision"]
    metrics = report.get("geometry_metrics", {})
    all_connected = metrics.get("connected_oks_count", 0) == metrics.get("total_oks_count", 0)
    if expected == "separate_pipes":
        return all_connected and topology["shared_segment_count"] == 0
    if expected == "connect_all":
        return all_connected
    if expected == "shared_pipe":
        return all_connected and topology["shared_segment_count"] > 0
    if expected == "mixed_or_borderline":
        return actual in ("mixed_or_borderline", "shared_pipe", "separate_pipes")
    if expected == "refusal_candidate":
        return metrics.get("connected_oks_count", 0) < metrics.get("total_oks_count", 0)
    return actual == expected


def short_violation_list(report, limit=5):
    return [
        {
            "code": item.get("code"),
            "objects": item.get("object_ids", []),
            "message": item.get("message"),
        }
        for item in report.get("violations", [])[:limit]
    ]


def evaluate_expectations(case, report, result_path, elapsed_seconds):
    expectations = case.get("expectations") or {}
    metrics = report.get("geometry_metrics", {})
    cost = report.get("recomputed_cost_components", {})
    failures = []

    exact_metrics = {
        "connected_oks_count": metrics.get("connected_oks_count"),
        "total_oks_count": metrics.get("total_oks_count"),
    }
    for key, actual in exact_metrics.items():
        if key in expectations and actual != expectations[key]:
            failures.append(f"{key}: expected {expectations[key]}, got {actual}")

    upper_bounds = {
        "max_new_network_length": cost.get("new_network_length"),
        "max_calculated_cost": cost.get("calculated_cost"),
        "max_score": cost.get("score"),
        "max_elapsed_seconds": elapsed_seconds,
    }
    for key, actual in upper_bounds.items():
        if key not in expectations:
            continue
        if actual is None or actual > expectations[key]:
            failures.append(f"{key}: limit {expectations[key]}, got {actual}")

    if expectations.get("forbid_tie_in_output"):
        result = json.loads(Path(result_path).read_text(encoding="utf-8"))
        tie_in_ids = [
            (feature.get("properties") or {}).get("id", "<missing-id>")
            for feature in result.get("features", [])
            if (feature.get("properties") or {}).get("object_type") == "tie_in"
        ]
        if tie_in_ids:
            failures.append(f"forbid_tie_in_output: found {', '.join(tie_in_ids[:5])}")

    return failures


def run_case(case, service_url, out_dir, timeout_seconds, skip_solver):
    slug = case["slug"]
    input_path = Path(case["geojson"])
    case_dir = out_dir / slug
    result_path = case_dir / "result.geojson"
    report_path = case_dir / "checker-report.json"
    started = time.perf_counter()

    if not input_path.exists():
        return {
            "slug": slug,
            "title": case.get("title", slug),
            "expected_decision": case.get("expected_decision", "unknown"),
            "status": "ERROR",
            "error": f"input file not found: {input_path}",
        }

    if not skip_solver:
        try:
            status, payload = post_geojson(service_url, input_path, timeout_seconds)
        except (urllib.error.URLError, TimeoutError, OSError) as exc:
            return {
                "slug": slug,
                "title": case.get("title", slug),
                "expected_decision": case.get("expected_decision", "unknown"),
                "status": "ERROR",
                "error": f"solver request failed: {exc}",
            }
        if status != 200:
            return {
                "slug": slug,
                "title": case.get("title", slug),
                "expected_decision": case.get("expected_decision", "unknown"),
                "status": "ERROR",
                "error": f"solver returned HTTP {status}",
            }
        case_dir.mkdir(parents=True, exist_ok=True)
        result_path.write_bytes(payload)
    elif not result_path.exists():
        return {
            "slug": slug,
            "title": case.get("title", slug),
            "expected_decision": case.get("expected_decision", "unknown"),
            "status": "ERROR",
            "error": f"result not found for --skip-solver: {result_path}",
        }

    report = check(input_path, result_path, f"suite-{slug}-{int(time.time())}")
    write_json(report_path, report)
    topology = classify_topology(report)
    expected = case.get("expected_decision", "unknown")
    decision_match = expectation_matches(expected, topology, report)
    metrics = report.get("geometry_metrics", {})
    cost = report.get("recomputed_cost_components", {})
    elapsed = round(time.perf_counter() - started, 3)
    expectation_failures = evaluate_expectations(case, report, result_path, elapsed)

    return {
        "slug": slug,
        "title": case.get("title", slug),
        "city": case.get("city"),
        "input": str(input_path),
        "result": str(result_path),
        "checker_report": str(report_path),
        "status": report.get("status"),
        "contract_valid": report.get("contract_valid", False),
        "expected_decision": expected,
        "actual_decision": topology["actual_decision"],
        "decision_match": decision_match,
        "expectations_match": not expectation_failures,
        "expectation_failures": expectation_failures,
        "connected_oks": metrics.get("connected_oks_count", 0),
        "total_oks": metrics.get("total_oks_count", 0),
        "new_network_length": cost.get("new_network_length"),
        "calculated_cost": cost.get("calculated_cost"),
        "score": cost.get("score"),
        "shared_segment_count": topology["shared_segment_count"],
        "max_segment_usage": topology["max_segment_usage"],
        "tie_in_count": topology["tie_in_count"],
        "violation_count": len(report.get("violations", [])),
        "sample_violations": short_violation_list(report),
        "elapsed_seconds": elapsed,
    }


def render_markdown(summary):
    lines = [
        "# Benchmark suite",
        "",
        f"- generated_at: `{summary['generated_at']}`",
        f"- service_url: `{summary['service_url']}`",
        f"- cases: `{summary['case_count']}`",
        f"- passed_contract: `{summary['passed_contract_count']}/{summary['case_count']}`",
        f"- matched_decision: `{summary['matched_decision_count']}/{summary['case_count']}`",
        f"- passed_expectations: `{summary['passed_expectations_count']}/{summary['case_count']}`",
        "",
        "| case | expected | actual | contract | decision | limits | oks | length_m | cost | score | elapsed_s | violations |",
        "| --- | --- | --- | --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |",
    ]
    for case in summary["cases"]:
        lines.append(
            "| {slug} | {expected} | {actual} | {contract} | {decision} | {limits} | {oks} | {length} | {cost} | {score} | {elapsed} | {violations} |".format(
                slug=case["slug"],
                expected=case.get("expected_decision"),
                actual=case.get("actual_decision", "-"),
                contract="OK" if case.get("contract_valid") else case.get("status", "ERROR"),
                decision="OK" if case.get("decision_match") else "FAIL",
                limits="OK" if case.get("expectations_match") else "FAIL",
                oks=f"{case.get('connected_oks', 0)}/{case.get('total_oks', 0)}",
                length=case.get("new_network_length", "-"),
                cost=case.get("calculated_cost", "-"),
                score=case.get("score", "-"),
                elapsed=case.get("elapsed_seconds", "-"),
                violations=case.get("violation_count", "-"),
            )
        )
    lines.append("")
    lines.append("## Notes")
    lines.append("")
    lines.append("- `contract` is the independent geometry/cost checker result.")
    lines.append("- `decision` compares the observed topology with the case-level expectation from the manifest.")
    lines.append("- `connect_all` means every input oks_connection_point must have a valid path to a heat_chamber.")
    lines.append("- `limits` checks case-specific quality and runtime regression bounds from the manifest.")
    lines.append("- Legacy research manifests can still be run explicitly with `--manifest data/research_examples/index.json`.")
    return "\n".join(lines) + "\n"


def main():
    parser = argparse.ArgumentParser(description="Run solver and independent checker over the benchmark case suite.")
    parser.add_argument("--manifest", default=str(DEFAULT_MANIFEST), help="Benchmark manifest JSON")
    parser.add_argument("--out-dir", default=str(DEFAULT_OUT_DIR), help="Output directory for results and reports")
    parser.add_argument("--service-url", default=DEFAULT_SERVICE_URL, help="Solver API URL")
    parser.add_argument("--timeout", type=int, default=180, help="HTTP timeout per case in seconds")
    parser.add_argument("--case", action="append", dest="case_slugs", help="Run only this case slug; can be repeated")
    parser.add_argument("--include-official", action="store_true", help="Also run the official dataset before research cases")
    parser.add_argument("--skip-solver", action="store_true", help="Only re-check existing per-case result.geojson files")
    parser.add_argument("--list", action="store_true", help="List cases and exit")
    parser.add_argument("--no-fail", action="store_true", help="Do not return non-zero exit code on benchmark failure")
    args = parser.parse_args()

    manifest_path = Path(args.manifest)
    out_dir = Path(args.out_dir)
    cases = load_cases(manifest_path, args.include_official)
    if args.case_slugs:
        selected = set(args.case_slugs)
        cases = [case for case in cases if case["slug"] in selected]

    if args.list:
        for case in cases:
            print(f"{case['slug']}: {case.get('expected_decision', 'unknown')} - {case.get('title', case['slug'])}")
        return 0

    if not cases:
        print("No benchmark cases selected.", file=sys.stderr)
        return 2

    results = []
    for case in cases:
        print(f"running {case['slug']} ({case.get('expected_decision', 'unknown')})", flush=True)
        results.append(run_case(case, args.service_url, out_dir, args.timeout, args.skip_solver))

    case_count = len(results)
    passed_contract = sum(1 for item in results if item.get("contract_valid"))
    matched_decision = sum(1 for item in results if item.get("decision_match"))
    passed_expectations = sum(1 for item in results if item.get("expectations_match"))
    summary = {
        "generated_at": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
        "service_url": args.service_url,
        "manifest": str(manifest_path),
        "case_count": case_count,
        "passed_contract_count": passed_contract,
        "matched_decision_count": matched_decision,
        "passed_expectations_count": passed_expectations,
        "cases": results,
    }
    write_json(out_dir / "summary.json", summary)
    (out_dir / "summary.md").write_text(render_markdown(summary), encoding="utf-8")

    print(
        f"contract: {passed_contract}/{case_count}; decision: {matched_decision}/{case_count}; "
        f"limits: {passed_expectations}/{case_count}",
        flush=True,
    )
    print(out_dir / "summary.md", flush=True)

    failed = [
        item
        for item in results
        if item.get("status") == "ERROR"
        or not item.get("contract_valid")
        or not item.get("decision_match")
        or not item.get("expectations_match")
    ]
    if failed and not args.no_fail:
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
