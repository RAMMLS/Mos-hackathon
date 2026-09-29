import argparse
import json
import math
from pathlib import Path

from benchmark_checker import (
    distance,
    geometry_polygons,
    geometry_segments,
    nearest_point_on_segment,
    point_in_polygon,
    point_segment_distance,
    polygon_metric_segments,
    ring_segments,
    segment_intersection,
    to_metric,
    unique_points,
)


def nearest_on_segments(point, segments):
    best = None
    best_distance = math.inf
    best_index = None
    for index, (a, b) in enumerate(segments):
        candidate = nearest_point_on_segment(point, a, b)
        candidate_distance = distance(point, candidate)
        if candidate_distance < best_distance:
            best = candidate
            best_distance = candidate_distance
            best_index = index
    return best, best_distance, best_index


def nearest_infrastructure(point, features, limit=10):
    candidates = []
    for feature in features:
        props = feature.get("properties", {})
        object_type = props.get("object_type")
        if object_type == "heat_chamber":
            metric = to_metric(feature["geometry"]["coordinates"])
            candidate_distance = distance(point, metric)
        elif object_type == "heat_network":
            segments = geometry_segments(feature.get("geometry"))
            if not segments:
                continue
            candidate_distance = min(point_segment_distance(point, a, b) for a, b in segments)
        else:
            continue
        candidates.append({
            "id": str(props.get("id")),
            "object_type": object_type,
            "distance_m": round(candidate_distance, 3),
        })
    candidates.sort(key=lambda item: item["distance_m"])
    return candidates[:limit]


def ray_profile(endpoint, boundary, polygon):
    boundary_distance = distance(endpoint, boundary)
    direction = (
        (boundary[0] - endpoint[0]) / boundary_distance,
        (boundary[1] - endpoint[1]) / boundary_distance,
    )
    segments = polygon_metric_segments(polygon)
    profile = []
    for offset in (1.0, 2.0, 5.0, 10.0, 20.0, 40.0, 80.0, 160.0):
        portal = (
            boundary[0] + direction[0] * offset,
            boundary[1] + direction[1] * offset,
        )
        crossings = unique_points([
            crossing
            for a, b in segments
            for crossing in [segment_intersection(endpoint, portal, a, b)]
            if crossing is not None
        ])
        boundary_clearance = min(point_segment_distance(portal, a, b) for a, b in segments)
        profile.append({
            "offset_m": offset,
            "portal_inside_polygon": point_in_polygon(portal, polygon),
            "portal_boundary_clearance_m": round(boundary_clearance, 3),
            "boundary_crossing_count": len(crossings),
        })
    return profile


def main():
    parser = argparse.ArgumentParser(description="Inspect the documented nearest OKS entry geometry.")
    parser.add_argument("--input", required=True, type=Path)
    parser.add_argument("--target", required=True)
    parser.add_argument("--out", required=True, type=Path)
    args = parser.parse_args()

    data = json.loads(args.input.read_text(encoding="utf-8-sig"))
    features = data.get("features", [])
    target_feature = next(
        feature for feature in features
        if feature.get("properties", {}).get("object_type") == "oks_connection_point"
        and str(feature.get("properties", {}).get("id")) == args.target
    )
    target = to_metric(target_feature["geometry"]["coordinates"])

    containing = []
    for feature in features:
        props = feature.get("properties", {})
        if props.get("object_type") != "restriction" or props.get("restriction_type") != "oks":
            continue
        polygons = geometry_polygons(feature.get("geometry"))
        whole_segments = geometry_segments(feature.get("geometry"))
        nearest_whole, distance_whole, segment_whole = nearest_on_segments(target, whole_segments)
        for component_index, polygon in enumerate(polygons):
            if not point_in_polygon(target, polygon):
                continue
            all_rings = polygon_metric_segments(polygon)
            exterior = ring_segments(polygon[0])
            nearest_all, distance_all, segment_all = nearest_on_segments(target, all_rings)
            nearest_exterior, distance_exterior, segment_exterior = nearest_on_segments(target, exterior)
            ring_distances = []
            for ring_index, ring in enumerate(polygon):
                _, ring_distance, _ = nearest_on_segments(target, ring_segments(ring))
                ring_distances.append({
                    "ring_index": ring_index,
                    "role": "exterior" if ring_index == 0 else "hole",
                    "distance_m": round(ring_distance, 3),
                })
            containing.append({
                "restriction_id": str(props.get("id")),
                "restriction_component_count": len(polygons),
                "component_index": component_index,
                "hole_count": max(0, len(polygon) - 1),
                "nearest_any_boundary_m": round(distance_all, 3),
                "nearest_any_boundary_metric": [round(value, 3) for value in nearest_all],
                "nearest_any_segment_index": segment_all,
                "nearest_whole_restriction_m": round(distance_whole, 3),
                "nearest_whole_restriction_metric": [round(value, 3) for value in nearest_whole],
                "nearest_whole_segment_index": segment_whole,
                "whole_nearest_differs_from_containing_component": (
                    distance(nearest_whole, nearest_all) > 0.2
                ),
                "nearest_exterior_boundary_m": round(distance_exterior, 3),
                "nearest_exterior_boundary_metric": [round(value, 3) for value in nearest_exterior],
                "nearest_exterior_segment_index": segment_exterior,
                "nearest_boundary_is_hole": distance_all + 1e-6 < distance_exterior,
                "ring_distances": ring_distances,
                "nearest_boundary_ray_profile": ray_profile(target, nearest_all, polygon),
            })

    report = {
        "target_id": args.target,
        "target_lon_lat": target_feature["geometry"]["coordinates"],
        "target_metric": [round(value, 3) for value in target],
        "containing_oks_component_count": len(containing),
        "containing_oks": containing,
        "nearest_existing_infrastructure": nearest_infrastructure(target, features),
    }
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(args.out)


if __name__ == "__main__":
    main()
