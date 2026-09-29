import json
import math
import tempfile
from pathlib import Path

from benchmark_checker import (
    DOCUMENT_NEAREST_V1,
    EXPERIMENTAL_ANY_BOUNDARY_V1,
    check,
    required_centerline_clearance,
    segment_segment_distance,
    turn_angle_degrees,
)
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


def assert_invalid(name, input_path, result_path, expected_code,
                   ruleset=DOCUMENT_NEAREST_V1):
    report = check(input_path, result_path, f"smoke-{name}", ruleset)
    codes = {violation["code"] for violation in report["violations"]}
    if report["status"] != "INVALID" or expected_code not in codes:
        raise AssertionError(f"{name}: expected {expected_code}, got status={report['status']} codes={sorted(codes)}")
    return sorted(codes)


def assert_no_violation(name, input_path, result_path, forbidden_code,
                        ruleset=DOCUMENT_NEAREST_V1):
    report = check(input_path, result_path, f"smoke-{name}", ruleset)
    codes = {violation["code"] for violation in report["violations"]}
    if forbidden_code in codes:
        raise AssertionError(f"{name}: unexpected {forbidden_code}, got codes={sorted(codes)}")


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


def add_over_90_turn(input_data, result_data):
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


def duplicate_network_corridor(input_data, result_data):
    heat_network = next(
        feature for feature in result_data["features"]
        if (feature.get("properties") or {}).get("object_type") == "heat_network"
    )
    duplicated = json.loads(json.dumps(heat_network))
    duplicated["properties"]["id"] = "smoke_parallel_network_overlap"
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


def add_nearby_undersized_chamber_before_exact(input_data, result_data):
    exact = next(
        feature for feature in result_data["features"]
        if (feature.get("properties") or {}).get("id") == "ch_branch_1"
    )
    nearby = json.loads(json.dumps(exact))
    nearby["properties"]["id"] = "ch_nearby_undersized"
    nearby["properties"]["diameter"] = 65
    nearby["geometry"]["coordinates"][0] += 0.000005
    result_data["features"].insert(0, nearby)


def branch_at_existing_chamber_without_diameter(input_data, result_data):
    chamber = next(
        feature for feature in input_data["features"]
        if (feature.get("properties") or {}).get("id") == 106
    )
    endpoint = chamber["geometry"]["coordinates"]
    for feature in result_data["features"]:
        props = feature.get("properties") or {}
        if props.get("object_type") == "heat_network" and props.get("id") in ("hn_1", "hn_2"):
            props["end_node_id"] = 106
            feature["geometry"]["coordinates"][-1] = endpoint


def add_feasible_non_nearest_oks_approach(input_data, result_data):
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


def assert_turn_angle_boundaries():
    origin = (0.0, 0.0)
    pivot = (1.0, 0.0)
    for angle, should_pass in ((89.0, True), (90.0, True), (91.0, False)):
        radians = math.radians(angle)
        endpoint = (pivot[0] + math.cos(radians), math.sin(radians))
        actual = turn_angle_degrees(origin, pivot, endpoint)
        passed = actual <= 90.05
        if passed != should_pass:
            raise AssertionError(f"turn {angle}: expected pass={should_pass}, actual={actual}")
    print("PASS turn-angle-boundaries: 89 and 90 accepted, 91 rejected")


def assert_clearance_rule_boundaries():
    expected = {
        ("oks", 100, 0): 5.255,
        ("oks", 125, 0): 5.300,
        ("oks", 500, 0): 7.835,
        ("oks", 900, 0): 10.225,
        ("gas_pipeline", 100, 0): 2.455,
        ("power_cable", 100, 0): 2.355,
        ("heat_network", 100, 500): 2.090,
    }
    for arguments, value in expected.items():
        actual = required_centerline_clearance(*arguments)
        if abs(actual - value) > 1e-9:
            raise AssertionError(f"clearance {arguments}: expected {value}, got {actual}")
    middle_distance = segment_segment_distance((-10, 0), (10, 0), (-1, 1), (1, 1))
    if abs(middle_distance - 1.0) > 1e-9:
        raise AssertionError(f"continuous segment distance: expected 1.0, got {middle_distance}")
    print("PASS clearance-boundaries: diameter steps, pair widths, continuous segment distance")


def main():
    cases = [
        ("missing-segment", remove_one_segment, "OKS_PATH_MISSING"),
        ("small-diameter", shrink_shared_diameter, "SEGMENT_DIAMETER_TOO_SMALL"),
        ("over-90-turn", add_over_90_turn, "SEGMENT_TURN_ANGLE_EXCEEDED"),
        ("bad-special-crossing-angle", add_bad_special_crossing, "SPECIAL_CROSSING_ANGLE_TOO_SMALL"),
        ("duplicate-id", duplicate_feature_id, "DUPLICATE_ID"),
        ("parallel-network-overlap", duplicate_network_corridor, "PARALLEL_NETWORK_OVERLAP"),
        ("duplicate-summary", duplicate_summary, "SUMMARY_CARDINALITY_ERROR"),
        ("nonfinite-cost", add_nonfinite_cost, "NONFINITE_NUMBER"),
        ("forbidden-crossing", add_forbidden_crossing, "FORBIDDEN_RESTRICTION_CROSSED"),
    ]
    for name, mutate, expected_code in cases:
        input_path, result_path = write_mutation(name, mutate)
        codes = assert_invalid(name, input_path, result_path, expected_code)
        print(f"PASS {name}: {', '.join(codes)}")
    input_path, result_path = write_mutation(
        "feasible-non-nearest-oks-approach", add_feasible_non_nearest_oks_approach
    )
    codes = assert_invalid(
        "documented-non-nearest-oks-approach", input_path, result_path,
        "OKS_ENDPOINT_APPROACH_INVALID", DOCUMENT_NEAREST_V1,
    )
    print(f"PASS documented-non-nearest-oks-approach: {', '.join(codes)}")
    assert_no_violation(
        "experimental-non-nearest-oks-approach", input_path, result_path,
        "OKS_ENDPOINT_APPROACH_INVALID", EXPERIMENTAL_ANY_BOUNDARY_V1,
    )
    print("PASS experimental-non-nearest-oks-approach: endpoint approach accepted")
    input_path, result_path = write_mutation(
        "nearby-chamber-selection", add_nearby_undersized_chamber_before_exact
    )
    assert_no_violation(
        "nearby-chamber-selection", input_path, result_path,
        "CHAMBER_DIAMETER_TOO_SMALL",
    )
    print("PASS nearby-chamber-selection: exact chamber wins over earlier nearby chamber")
    input_path, result_path = write_mutation(
        "existing-chamber-without-diameter", branch_at_existing_chamber_without_diameter
    )
    assert_no_violation(
        "existing-chamber-without-diameter", input_path, result_path,
        "CHAMBER_DIAMETER_TOO_SMALL",
    )
    print("PASS existing-chamber-without-diameter: no invented diameter limit")
    assert_suite_limits_detect_regressions()
    assert_turn_angle_boundaries()
    assert_clearance_rule_boundaries()


if __name__ == "__main__":
    main()
