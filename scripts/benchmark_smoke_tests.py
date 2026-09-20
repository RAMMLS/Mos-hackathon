import json
import tempfile
from pathlib import Path

from benchmark_checker import check
from run_benchmark_suite import evaluate_expectations


ROOT = Path(__file__).resolve().parents[1]
INPUT = ROOT / "data" / "benchmark_fixtures" / "new_tz_smoke" / "input.geojson"
RESULT = ROOT / "data" / "benchmark_fixtures" / "new_tz_smoke" / "valid_result.geojson"
INPUT_DATA = json.loads(INPUT.read_text(encoding="utf-8"))


def write_mutation(name, mutate):
    input_data = json.loads(INPUT.read_text(encoding="utf-8"))
    result_data = json.loads(RESULT.read_text(encoding="utf-8"))
    mutate(input_data, result_data)
    input_path = Path(tempfile.gettempdir()) / f"{name}-input.geojson"
    result_path = Path(tempfile.gettempdir()) / f"{name}-result.geojson"
    input_path.write_text(json.dumps(input_data, ensure_ascii=False), encoding="utf-8")
    result_path.write_text(json.dumps(result_data, ensure_ascii=False), encoding="utf-8")
    return input_path, result_path


def assert_invalid(name, input_path, result_path, expected_code):
    report = check(input_path, result_path, f"smoke-{name}")
    codes = {violation["code"] for violation in report["violations"]}
    if report["status"] != "INVALID" or expected_code not in codes:
        raise AssertionError(f"{name}: expected {expected_code}, got status={report['status']} codes={sorted(codes)}")
    return sorted(codes)


def remove_one_segment(input_data, result_data):
    result_data["features"] = [
        feature for feature in result_data["features"]
        if not (
            (feature.get("properties") or {}).get("object_type") == "heat_network"
            and (feature.get("properties") or {}).get("id") == "hn_1"
        )
    ]


def shrink_shared_diameter(input_data, result_data):
    for feature in result_data["features"]:
        props = feature.get("properties") or {}
        if props.get("object_type") == "heat_network" and props.get("id") == "hn_1":
            props["diameter"] = 50
            return
    raise AssertionError("hn_1 not found")


def add_hairpin_turn(input_data, result_data):
    for feature in result_data["features"]:
        props = feature.get("properties") or {}
        if props.get("object_type") == "heat_network" and props.get("id") == "hn_1":
            coords = feature["geometry"]["coordinates"]
            if len(coords) < 2:
                raise AssertionError("hn_1 has too few coordinates")
            feature["geometry"]["coordinates"] = [coords[0], coords[1], coords[0], coords[-1]]
            return
    raise AssertionError("hn_1 not found")


def add_bad_special_crossing(input_data, result_data):
    road = {
        "type": "Feature",
        "properties": {"id": "smoke_road", "object_type": "restriction", "restriction_type": "road"},
        "geometry": {
            "type": "Polygon",
            "coordinates": [[
                [37.6300, 55.7000],
                [37.6380, 55.7000],
                [37.6380, 55.7002],
                [37.6300, 55.7002],
                [37.6300, 55.7000],
            ]],
        },
    }
    input_data["features"].append(road)
    start = [37.6298, 55.69999]
    end = [37.6382, 55.70004]
    for feature in result_data["features"]:
        props = feature.get("properties") or {}
        if props.get("object_type") == "heat_network" and props.get("id") == "hn_1":
            feature["geometry"]["coordinates"] = [start, end]
            return
    raise AssertionError("hn_1 not found")


def duplicate_feature_id(input_data, result_data):
    heat_network = next(
        feature for feature in result_data["features"]
        if (feature.get("properties") or {}).get("object_type") == "heat_network"
    )
    duplicated = json.loads(json.dumps(heat_network))
    result_data["features"].append(duplicated)


def duplicate_summary(input_data, result_data):
    summary = next(
        feature for feature in result_data["features"]
        if (feature.get("properties") or {}).get("object_type") == "variant_summary"
    )
    duplicated = json.loads(json.dumps(summary))
    duplicated["properties"]["id"] = "extra_summary"
    result_data["features"].append(duplicated)


def add_nonfinite_cost(input_data, result_data):
    for feature in result_data["features"]:
        props = feature.get("properties") or {}
        if props.get("object_type") == "heat_network":
            props["cost"] = float("nan")
            return
    raise AssertionError("heat_network not found")


def add_forbidden_crossing(input_data, result_data):
    target = None
    for feature in result_data["features"]:
        props = feature.get("properties") or {}
        if props.get("object_type") == "heat_network" and props.get("id") == "hn_1":
            target = feature
            break
    if target is None:
        raise AssertionError("hn_1 not found")
    coords = target["geometry"]["coordinates"]
    lon = sum(coord[0] for coord in coords) / len(coords)
    lat = sum(coord[1] for coord in coords) / len(coords)
    delta = 0.00035
    park = {
        "type": "Feature",
        "properties": {"id": "smoke_forbidden_park", "object_type": "restriction", "restriction_type": "park"},
        "geometry": {
            "type": "Polygon",
            "coordinates": [[
                [lon - delta, lat - delta],
                [lon + delta, lat - delta],
                [lon + delta, lat + delta],
                [lon - delta, lat + delta],
                [lon - delta, lat - delta],
            ]],
        },
    }
    input_data["features"].append(park)


def add_non_nearest_oks_approach(input_data, result_data):
    point = next(
        feature["geometry"]["coordinates"]
        for feature in input_data["features"]
        if str((feature.get("properties") or {}).get("id")) == "1"
    )
    lon, lat = point
    own_oks = {
        "type": "Feature",
        "properties": {
            "id": "smoke_own_oks",
            "object_type": "restriction",
            "restriction_type": "oks",
        },
        "geometry": {
            "type": "Polygon",
            "coordinates": [[
                [lon - 0.00002, lat - 0.00020],
                [lon + 0.00030, lat - 0.00020],
                [lon + 0.00030, lat + 0.00020],
                [lon - 0.00002, lat + 0.00020],
                [lon - 0.00002, lat - 0.00020],
            ]],
        },
    }
    input_data["features"].append(own_oks)


def assert_suite_limits_detect_regressions():
    report = check(INPUT, RESULT, "smoke-suite-limits")
    cost = report["recomputed_cost_components"]
    strict_case = {
        "expectations": {
            "connected_oks_count": report["geometry_metrics"]["connected_oks_count"] + 1,
            "max_new_network_length": cost["new_network_length"] - 0.01,
            "max_calculated_cost": cost["calculated_cost"] - 0.01,
            "max_score": cost["score"] - 0.01,
            "max_elapsed_seconds": 1.0,
        }
    }
    failures = evaluate_expectations(strict_case, report, RESULT, 2.0)
    expected_prefixes = {
        "connected_oks_count",
        "max_new_network_length",
        "max_calculated_cost",
        "max_score",
        "max_elapsed_seconds",
    }
    actual_prefixes = {failure.split(":", 1)[0] for failure in failures}
    if not expected_prefixes.issubset(actual_prefixes):
        raise AssertionError(f"suite limits missed regressions: {failures}")

    def add_legacy_tie_in(input_data, result_data):
        feature = next(
            item for item in result_data["features"]
            if (item.get("properties") or {}).get("object_type") == "heat_network"
        )
        feature["properties"]["object_type"] = "tie_in"

    _, tie_in_result = write_mutation("legacy-tie-in", add_legacy_tie_in)
    tie_failures = evaluate_expectations(
        {"expectations": {"forbid_tie_in_output": True}}, report, tie_in_result, 0.0
    )
    if not any(failure.startswith("forbid_tie_in_output:") for failure in tie_failures):
        raise AssertionError(f"suite failed to reject legacy tie_in: {tie_failures}")
    print("PASS suite-regression-limits: quality, runtime, legacy tie_in")


def main():
    cases = [
        ("missing-segment", remove_one_segment, "OKS_PATH_MISSING"),
        ("small-diameter", shrink_shared_diameter, "SEGMENT_DIAMETER_TOO_SMALL"),
        ("hairpin-turn", add_hairpin_turn, "SEGMENT_HAIRPIN_TURN"),
        ("bad-special-crossing-angle", add_bad_special_crossing, "SPECIAL_CROSSING_ANGLE_TOO_SMALL"),
        ("duplicate-id", duplicate_feature_id, "DUPLICATE_ID"),
        ("duplicate-summary", duplicate_summary, "SUMMARY_CARDINALITY_ERROR"),
        ("nonfinite-cost", add_nonfinite_cost, "NONFINITE_NUMBER"),
        ("forbidden-crossing", add_forbidden_crossing, "FORBIDDEN_RESTRICTION_CROSSED"),
        ("non-nearest-oks-approach", add_non_nearest_oks_approach, "OKS_ENDPOINT_APPROACH_INVALID"),
    ]
    for name, mutate, expected_code in cases:
        input_path, result_path = write_mutation(name, mutate)
        codes = assert_invalid(name, input_path, result_path, expected_code)
        print(f"PASS {name}: {', '.join(codes)}")
    assert_suite_limits_detect_regressions()


if __name__ == "__main__":
    main()
