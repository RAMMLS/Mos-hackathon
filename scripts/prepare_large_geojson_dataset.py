#!/usr/bin/env python3
"""Convert OSM map windows into deterministic building-anchored routing scenes.

The map geometry and consumer building footprints come from OpenStreetMap.
Heat demand and the seed network are explicit synthetic proxies; no generated
point may be presented as a verified real heat consumer.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
from collections import Counter
from pathlib import Path
from typing import Dict, Iterable, List, Sequence, Tuple


ROOT = Path(__file__).resolve().parents[1]
SOURCE_DIR = ROOT / "data" / "real_geojson_places"
OUTPUT_DIR = ROOT / "data" / "rl_large"
CATEGORIES = ("separate_pipes", "shared_pipe", "fifty_fifty", "refusal")
EXTENSION_CATEGORIES = ("large_oks", "many_connection_points", "mixed_scale", "tie_in_stress")
ALL_CATEGORIES = CATEGORIES + EXTENSION_CATEGORIES

# Whole geographic seeds are assigned to one split. Keeping neighbouring map
# windows together prevents near-duplicate geography from leaking into tests.
VALIDATION_SEEDS = {"moscow_kommunarka", "kazan_center"}
TEST_SEEDS = {"moscow_center", "spb_parnas"}

Coord = Tuple[float, float]


def canonical_json(value: object) -> bytes:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")


def sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def sha256_file(path: Path) -> str:
    return sha256_bytes(path.read_bytes())


def split_for_seed(seed: str) -> str:
    if seed in VALIDATION_SEEDS:
        return "validation"
    if seed in TEST_SEEDS:
        return "test"
    return "train"


def restriction_type(osm_kind: str) -> str:
    return {
        "building": "oks",
        "road": "road",
        "major_road": "road",
        "railway": "railway",
        "water": "water",
        "green": "park",
    }[osm_kind]


def convert_restrictions(features: Sequence[Dict], scene_id: str) -> List[Dict]:
    converted = []
    for index, feature in enumerate(features, start=1):
        props = feature.get("properties") or {}
        kind = props.get("osm_kind")
        geometry = feature.get("geometry")
        if kind not in {"building", "road", "major_road", "railway", "water", "green"}:
            continue
        if not geometry or geometry.get("type") not in {"LineString", "Polygon", "MultiLineString", "MultiPolygon"}:
            continue
        osm_type = props.get("osm_type", "feature")
        osm_id = props.get("osm_id", index)
        converted.append({
            "type": "Feature",
            "properties": {
                "id": f"{scene_id}_osm_{osm_type}_{osm_id}_{index}",
                "object_type": "restriction",
                "restriction_type": restriction_type(kind),
                "name": props.get("name", ""),
                "osm_kind": kind,
                "osm_id": osm_id,
                "osm_type": osm_type,
                "osm_tags": props.get("osm_tags", {}),
                "case_id": scene_id,
            },
            "geometry": geometry,
        })
    return converted


def iter_lines(geometry: Dict) -> Iterable[Sequence[Sequence[float]]]:
    kind = geometry.get("type")
    coords = geometry.get("coordinates") or []
    if kind == "LineString":
        yield coords
    elif kind == "MultiLineString":
        yield from coords
    elif kind == "Polygon":
        yield from coords
    elif kind == "MultiPolygon":
        for polygon in coords:
            yield from polygon


def iter_polygons(geometry: Dict) -> Iterable[Sequence[Sequence[Sequence[float]]]]:
    kind = geometry.get("type")
    coords = geometry.get("coordinates") or []
    if kind == "Polygon":
        yield coords
    elif kind == "MultiPolygon":
        yield from coords


def metric_xy(point: Coord, latitude: float) -> Coord:
    lon, lat = point
    return lon * 111_320.0 * max(math.cos(math.radians(latitude)), 0.2), lat * 111_320.0


def point_in_ring(point: Coord, ring: Sequence[Sequence[float]]) -> bool:
    x, y = point
    inside = False
    for first, second in zip(ring, ring[1:] + ring[:1]):
        x1, y1 = first[:2]
        x2, y2 = second[:2]
        if (y1 > y) != (y2 > y):
            crossing_x = (x2 - x1) * (y - y1) / (y2 - y1) + x1
            if x < crossing_x:
                inside = not inside
    return inside


def point_in_polygon(point: Coord, polygon: Sequence[Sequence[Sequence[float]]]) -> bool:
    if not polygon or not point_in_ring(point, polygon[0]):
        return False
    return not any(point_in_ring(point, hole) for hole in polygon[1:])


def point_segment_distance_m(point: Coord, first: Coord, second: Coord, latitude: float) -> float:
    px, py = metric_xy(point, latitude)
    ax, ay = metric_xy(first, latitude)
    bx, by = metric_xy(second, latitude)
    dx, dy = bx - ax, by - ay
    if dx == 0.0 and dy == 0.0:
        return math.hypot(px - ax, py - ay)
    t = max(0.0, min(1.0, ((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy)))
    return math.hypot(px - (ax + t * dx), py - (ay + t * dy))


def hard_geometry(restrictions: Sequence[Dict]) -> Tuple[List, List[Tuple[Coord, Coord]]]:
    polygons = []
    segments = []
    for feature in restrictions:
        if feature["properties"]["restriction_type"] not in {"oks", "water", "railway", "park"}:
            continue
        geometry = feature["geometry"]
        polygons.extend(iter_polygons(geometry))
        for line in iter_lines(geometry):
            segments.extend((tuple(a[:2]), tuple(b[:2])) for a, b in zip(line, line[1:]))
    return polygons, segments


def is_free(point: Coord, polygons: Sequence, segments: Sequence, latitude: float, clearance_m: float = 7.0) -> bool:
    if any(point_in_polygon(point, polygon) for polygon in polygons):
        return False
    return all(point_segment_distance_m(point, a, b, latitude) >= clearance_m for a, b in segments)


def distance_m(first: Coord, second: Coord, latitude: float) -> float:
    ax, ay = metric_xy(first, latitude)
    bx, by = metric_xy(second, latitude)
    return math.hypot(ax - bx, ay - by)


def ring_area_and_centroid(ring: Sequence[Sequence[float]], latitude: float) -> Tuple[float, Coord]:
    """Return planar footprint area in m2 and polygon centroid for one outer ring."""
    points = [tuple(point[:2]) for point in ring]
    if len(points) > 1 and points[0] == points[-1]:
        points = points[:-1]
    if len(points) < 3:
        raise ValueError("building ring has fewer than three points")
    metric = [metric_xy(point, latitude) for point in points]
    cross_sum = 0.0
    centroid_x = 0.0
    centroid_y = 0.0
    for first, second in zip(metric, metric[1:] + metric[:1]):
        cross = first[0] * second[1] - second[0] * first[1]
        cross_sum += cross
        centroid_x += (first[0] + second[0]) * cross
        centroid_y += (first[1] + second[1]) * cross
    if abs(cross_sum) < 1e-6:
        lon = sum(point[0] for point in points) / len(points)
        lat = sum(point[1] for point in points) / len(points)
        return 0.0, (lon, lat)
    centroid_x /= 3.0 * cross_sum
    centroid_y /= 3.0 * cross_sum
    lon_scale = 111_320.0 * max(math.cos(math.radians(latitude)), 0.2)
    return abs(cross_sum) / 2.0, (centroid_x / lon_scale, centroid_y / 111_320.0)


def representative_point(polygon: Sequence[Sequence[Sequence[float]]], latitude: float) -> Tuple[Coord, float]:
    """Choose a deterministic point inside a footprint, including concave polygons."""
    area, centroid = ring_area_and_centroid(polygon[0], latitude)
    if point_in_polygon(centroid, polygon):
        return centroid, area
    outer = polygon[0]
    west = min(point[0] for point in outer)
    east = max(point[0] for point in outer)
    south = min(point[1] for point in outer)
    north = max(point[1] for point in outer)
    candidates = []
    for row in range(1, 12):
        for col in range(1, 12):
            candidate = (west + (east - west) * col / 12.0, south + (north - south) * row / 12.0)
            if point_in_polygon(candidate, polygon):
                clearance = min(
                    point_segment_distance_m(candidate, tuple(a[:2]), tuple(b[:2]), latitude)
                    for ring in polygon
                    for a, b in zip(ring, ring[1:])
                )
                candidates.append((clearance, candidate))
    if not candidates:
        raise ValueError("cannot find a point inside building footprint")
    return max(candidates, key=lambda item: item[0])[1], area


def building_candidates(restrictions: Sequence[Dict], latitude: float) -> List[Dict]:
    candidates = []
    for feature_index, feature in enumerate(restrictions):
        props = feature["properties"]
        if props.get("osm_kind") != "building":
            continue
        polygons = list(iter_polygons(feature["geometry"]))
        if not polygons:
            continue
        ranked = []
        for polygon in polygons:
            try:
                point, area = representative_point(polygon, latitude)
                ranked.append((area, point))
            except ValueError:
                continue
        if not ranked:
            continue
        area, point = max(ranked, key=lambda item: item[0])
        if area < 20.0:
            continue
        candidates.append({"feature": feature, "feature_index": feature_index, "point": point, "area_m2": area})
    return candidates


def normalized_point(bbox: Sequence[float], x: float, y: float) -> Coord:
    south, west, north, east = bbox
    return west + (east - west) * x, south + (north - south) * y


def free_grid(bbox: Sequence[float], restrictions: Sequence[Dict]) -> List[Coord]:
    polygons, segments = hard_geometry(restrictions)
    latitude = (bbox[0] + bbox[2]) / 2.0
    candidates = []
    for row in range(2, 29):
        for col in range(2, 29):
            point = normalized_point(bbox, col / 30.0, row / 30.0)
            if is_free(point, polygons, segments, latitude):
                candidates.append(point)
    if len(candidates) < 12:
        raise ValueError(f"not enough free placement points: {len(candidates)}")
    return candidates


def nearest_unused(target: Coord, candidates: Sequence[Coord], used: Sequence[Coord], latitude: float,
                   min_distance_m: float = 12.0) -> Coord:
    allowed = [
        point for point in candidates
        if all(distance_m(point, other, latitude) >= min_distance_m for other in used)
    ]
    if not allowed:
        raise ValueError("no free placement candidate")
    return min(allowed, key=lambda point: distance_m(point, target, latitude))


def task_targets(category: str) -> List[Tuple[float, float]]:
    if category == "separate_pipes":
        return [(0.48, 0.18), (0.61, 0.34), (0.74, 0.50), (0.84, 0.67), (0.92, 0.82)]
    if category == "shared_pipe":
        return [(0.72, 0.39), (0.77, 0.45), (0.82, 0.51), (0.75, 0.58), (0.81, 0.64), (0.87, 0.55)]
    if category == "fifty_fifty":
        return [(0.57, 0.27), (0.63, 0.34), (0.68, 0.28), (0.79, 0.66), (0.85, 0.73), (0.90, 0.65)]
    if category == "large_oks":
        return [(0.58, 0.24), (0.72, 0.38), (0.84, 0.57), (0.70, 0.76)]
    if category == "many_connection_points":
        return [
            (0.50, 0.20), (0.58, 0.27), (0.66, 0.23), (0.74, 0.31),
            (0.82, 0.39), (0.88, 0.48), (0.80, 0.58), (0.72, 0.66),
            (0.64, 0.74), (0.78, 0.78), (0.90, 0.70), (0.56, 0.56),
        ]
    if category == "mixed_scale":
        return [
            (0.55, 0.23), (0.82, 0.35), (0.62, 0.42), (0.72, 0.49),
            (0.86, 0.55), (0.58, 0.68), (0.70, 0.76), (0.90, 0.78),
        ]
    if category == "tie_in_stress":
        return [
            (0.48, 0.18), (0.58, 0.30), (0.71, 0.26), (0.84, 0.37),
            (0.76, 0.55), (0.89, 0.66), (0.64, 0.72), (0.52, 0.84),
        ]
    return [(0.76, 0.25), (0.86, 0.52), (0.78, 0.79)]


def select_buildings(category: str, bbox: Sequence[float], buildings: Sequence[Dict], latitude: float) -> List[Dict]:
    targets = task_targets(category)
    required = len(targets)
    if len(buildings) < required:
        raise ValueError(f"not enough real OSM buildings: {len(buildings)} < {required}")

    selected = []
    if category == "large_oks":
        # Stress high-load diameter decisions by taking the largest available
        # footprints while still keeping them spatially spread by target.
        large_pool = sorted(buildings, key=lambda item: item["area_m2"], reverse=True)[: max(12, required * 4)]
        for x, y in targets:
            target = normalized_point(bbox, x, y)
            candidate = min(
                (item for item in large_pool if item not in selected),
                key=lambda item: distance_m(item["point"], target, latitude),
            )
            selected.append(candidate)
        return selected

    if category == "mixed_scale":
        large = sorted(buildings, key=lambda item: item["area_m2"], reverse=True)[:2]
        selected.extend(large)
        remaining_targets = targets[len(selected):]
    else:
        remaining_targets = targets

    for x, y in remaining_targets:
        target = normalized_point(bbox, x, y)
        candidate = min(
            (item for item in buildings if item not in selected),
            key=lambda item: distance_m(item["point"], target, latitude),
        )
        selected.append(candidate)
    return selected


def flow_for_building(category: str, area: float, index: int) -> float:
    if category == "large_oks":
        return max(20.0, min(180.0, area / 55.0))
    if category == "mixed_scale" and index <= 2:
        return max(20.0, min(140.0, area / 65.0))
    if category in {"many_connection_points", "tie_in_stress"}:
        return max(5.0, min(70.0, area / 90.0))
    return max(5.0, min(40.0, area / 100.0))


def existing_network_features(scene_id: str, category: str, network_start: Coord, network_end: Coord,
                              bbox: Sequence[float], candidates: Sequence[Coord], latitude: float) -> List[Dict]:
    source = {
        "type": "Feature",
        "properties": {"id": f"{scene_id}_source", "object_type": "source", "case_id": scene_id},
        "geometry": {"type": "Point", "coordinates": list(network_start)},
    }
    if category != "tie_in_stress":
        return [
            source,
            {
                "type": "Feature",
                "properties": {
                    "id": f"{scene_id}_existing_network", "object_type": "heat_network",
                    "diameter": 500, "flow_tph": 180.0, "upstream_object_id": f"{scene_id}_source",
                    "case_id": scene_id,
                },
                "geometry": {"type": "LineString", "coordinates": [list(network_start), list(network_end)]},
            },
            {
                "type": "Feature",
                "properties": {
                    "id": f"{scene_id}_existing_chamber", "object_type": "heat_chamber",
                    "diameter": 500, "upstream_object_id": f"{scene_id}_source", "case_id": scene_id,
                },
                "geometry": {"type": "Point", "coordinates": list(network_end)},
            },
        ]

    mid = nearest_unused(normalized_point(bbox, 0.23, 0.34), candidates, [network_start, network_end], latitude, 18.0)
    upper = nearest_unused(normalized_point(bbox, 0.25, 0.68), candidates, [network_start, network_end, mid], latitude, 18.0)
    return [
        source,
        {
            "type": "Feature",
            "properties": {
                "id": f"{scene_id}_existing_network_a", "object_type": "heat_network",
                "diameter": 700, "flow_tph": 260.0, "upstream_object_id": f"{scene_id}_source",
                "case_id": scene_id,
            },
            "geometry": {"type": "LineString", "coordinates": [list(network_start), list(mid)]},
        },
        {
            "type": "Feature",
            "properties": {
                "id": f"{scene_id}_existing_network_b", "object_type": "heat_network",
                "diameter": 600, "flow_tph": 220.0, "upstream_object_id": f"{scene_id}_source",
                "case_id": scene_id,
            },
            "geometry": {"type": "LineString", "coordinates": [list(mid), list(network_end)]},
        },
        {
            "type": "Feature",
            "properties": {
                "id": f"{scene_id}_existing_network_c", "object_type": "heat_network",
                "diameter": 500, "flow_tph": 180.0, "upstream_object_id": f"{scene_id}_source",
                "case_id": scene_id,
            },
            "geometry": {"type": "LineString", "coordinates": [list(mid), list(upper)]},
        },
        {
            "type": "Feature",
            "properties": {
                "id": f"{scene_id}_existing_chamber_low", "object_type": "heat_chamber",
                "diameter": 700, "upstream_object_id": f"{scene_id}_source", "case_id": scene_id,
            },
            "geometry": {"type": "Point", "coordinates": list(mid)},
        },
        {
            "type": "Feature",
            "properties": {
                "id": f"{scene_id}_existing_chamber_mid", "object_type": "heat_chamber",
                "diameter": 600, "upstream_object_id": f"{scene_id}_source", "case_id": scene_id,
            },
            "geometry": {"type": "Point", "coordinates": list(network_end)},
        },
        {
            "type": "Feature",
            "properties": {
                "id": f"{scene_id}_existing_chamber_high", "object_type": "heat_chamber",
                "diameter": 500, "upstream_object_id": f"{scene_id}_source", "case_id": scene_id,
            },
            "geometry": {"type": "Point", "coordinates": list(upper)},
        },
    ]


def synthetic_features(scene_id: str, category: str, bbox: Sequence[float], restrictions: Sequence[Dict],
                       placement_candidates: Sequence[Coord] | None = None,
                       building_pool: Sequence[Dict] | None = None) -> List[Dict]:
    candidates = list(placement_candidates) if placement_candidates is not None else free_grid(bbox, restrictions)
    latitude = (bbox[0] + bbox[2]) / 2.0
    network_start = nearest_unused(normalized_point(bbox, 0.08, 0.50), candidates, [], latitude)
    network_end = nearest_unused(normalized_point(bbox, 0.30, 0.50), candidates, [network_start], latitude, 18.0)
    buildings = list(building_pool) if building_pool is not None else building_candidates(restrictions, latitude)
    selected = select_buildings(category, bbox, buildings, latitude)
    features = existing_network_features(scene_id, category, network_start, network_end, bbox, candidates, latitude)
    for index, building in enumerate(selected, start=1):
        point = building["point"]
        area = building["area_m2"]
        source_feature = restrictions[building["feature_index"]]
        source_properties = source_feature["properties"]
        flow = flow_for_building(category, area, index)
        connection_id = f"{scene_id}_cp_{index}"
        source_properties["selected_as_heat_demand_proxy"] = True
        source_properties["connection_point_id"] = connection_id
        features.append({
            "type": "Feature",
            "properties": {
                "id": connection_id,
                "object_type": "oks_connection_point",
                "flow_tph": round(flow, 3),
                "case_id": scene_id,
                "consumer_role": "osm_building_heat_demand_proxy",
                "source_building_osm_id": source_properties.get("osm_id"),
                "source_building_osm_type": source_properties.get("osm_type"),
                "source_building_name": source_properties.get("name", ""),
                "source_building_tags": source_properties.get("osm_tags", {}),
                "footprint_area_m2": round(area, 2),
                "flow_basis": "synthetic_proxy_from_osm_footprint_area",
                "stress_bucket": category if category in EXTENSION_CATEGORIES else "",
            },
            "geometry": {"type": "Point", "coordinates": list(point)},
        })
    return features


def build_scene(source_path: Path, output_path: Path, category_override: str | None = None,
                placement_candidates: Sequence[Coord] | None = None,
                building_pool: Sequence[Dict] | None = None) -> Dict:
    source = json.loads(source_path.read_text(encoding="utf-8"))
    metadata = source.get("metadata") or {}
    base_scene_id = str(source.get("name") or source_path.stem)
    category = category_override or metadata["category"]
    scene_id = base_scene_id if category_override is None else f"{base_scene_id}_{category_override}"
    seed = metadata["seed"]
    bbox = metadata["bbox_south_west_north_east"]
    restrictions = convert_restrictions(source.get("features") or [], scene_id)
    synthetic = synthetic_features(scene_id, category, bbox, restrictions, placement_candidates, building_pool)
    split = split_for_seed(seed)
    output = {
        "type": "FeatureCollection",
        "name": scene_id,
        "crs": {"type": "name", "properties": {"name": "EPSG:4326"}},
        "metadata": {
            "dataset_version": "osm_building_anchored_routing_scenes_v3",
            "parent_scene_id": base_scene_id,
            "split": split,
            "city": metadata.get("city"),
            "seed": seed,
            "research_bucket": category,
            "source_research_bucket": metadata["category"],
            "research_bucket_is_ground_truth": False,
            "bbox_south_west_north_east": bbox,
            "real_data_source": metadata.get("source", "OpenStreetMap"),
            "source_file": source_path.relative_to(ROOT).as_posix(),
            "real_layers": ["restrictions", "consumer_building_footprints"],
            "synthetic_layers": ["source", "heat_network", "heat_chamber", "heat_demand"],
            "consumer_selection": "deterministic points inside real OSM building footprints",
            "flow_model": "synthetic proxy derived from building footprint area; not metered demand",
            "note": "OSM buildings anchor consumers; heat demand and seed network are synthetic proxies.",
        },
        "features": restrictions + synthetic,
    }
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(json.dumps(output, ensure_ascii=False, indent=2), encoding="utf-8")
    counts = Counter((feature.get("properties") or {}).get("object_type") for feature in output["features"])
    return {
        "scene_id": scene_id,
        "parent_scene_id": base_scene_id,
        "split": split,
        "seed": seed,
        "city": metadata.get("city"),
        "research_bucket": category,
        "source_research_bucket": metadata["category"],
        "input": output_path.relative_to(ROOT).as_posix(),
        "input_hash": sha256_file(output_path),
        "source": source_path.relative_to(ROOT).as_posix(),
        "source_hash": sha256_file(source_path),
        "feature_count": len(output["features"]),
        "restriction_count": counts["restriction"],
        "oks_count": counts["oks_connection_point"],
    }


def prune_stale_scenes(out_dir: Path, records: Sequence[Dict]) -> int:
    scene_root = (out_dir / "scenes").resolve()
    if not scene_root.is_dir():
        return 0
    expected = {(ROOT / record["input"]).resolve() for record in records}
    stale = []
    for path in scene_root.rglob("*.geojson"):
        resolved = path.resolve()
        if scene_root not in resolved.parents:
            raise ValueError(f"unsafe generated-scene path: {resolved}")
        if resolved not in expected:
            stale.append(resolved)
    for path in stale:
        path.unlink()
    return len(stale)


def main() -> int:
    parser = argparse.ArgumentParser(description="Prepare a large deterministic GeoJSON routing dataset.")
    parser.add_argument("--source-dir", type=Path, default=SOURCE_DIR)
    parser.add_argument("--out-dir", type=Path, default=OUTPUT_DIR)
    parser.add_argument("--limit-per-category", type=int, default=0, help="0 means all source places")
    parser.add_argument(
        "--no-extensions",
        action="store_true",
        help="Generate only the original four research buckets, without stress-extension scenes.",
    )
    args = parser.parse_args()
    if not args.source_dir.is_absolute():
        args.source_dir = ROOT / args.source_dir
    if not args.out_dir.is_absolute():
        args.out_dir = ROOT / args.out_dir

    records = []
    failures = []
    for category in CATEGORIES:
        paths = sorted((args.source_dir / category).glob("*.geojson"))
        if args.limit_per_category > 0:
            paths = paths[:args.limit_per_category]
        for source_path in paths:
            source = json.loads(source_path.read_text(encoding="utf-8"))
            metadata = source.get("metadata") or {}
            base_scene_id = str(source.get("name") or source_path.stem)
            bbox = metadata["bbox_south_west_north_east"]
            latitude = (bbox[0] + bbox[2]) / 2.0
            base_restrictions = convert_restrictions(source.get("features") or [], base_scene_id)
            try:
                placement_candidates = free_grid(bbox, base_restrictions)
                building_pool = building_candidates(base_restrictions, latitude)
            except Exception:
                placement_candidates = None
                building_pool = None
            output_path = args.out_dir / "scenes" / category / source_path.name
            try:
                records.append(build_scene(source_path, output_path, None, placement_candidates, building_pool))
            except Exception as error:
                failures.append({"source": source_path.relative_to(ROOT).as_posix(), "error": str(error)})
            if args.no_extensions:
                continue
            for extension_category in EXTENSION_CATEGORIES:
                extension_output_path = args.out_dir / "scenes" / extension_category / source_path.name
                try:
                    records.append(build_scene(
                        source_path,
                        extension_output_path,
                        extension_category,
                        placement_candidates,
                        building_pool,
                    ))
                except Exception as error:
                    failures.append({
                        "source": source_path.relative_to(ROOT).as_posix(),
                        "research_bucket": extension_category,
                        "optional_extension": True,
                        "error": str(error),
                    })

    stale_scene_count = prune_stale_scenes(args.out_dir, records)
    manifest = {
        "dataset_version": "osm_building_anchored_routing_scenes_v3",
        "description": (
            "Real OSM surroundings and building-anchored consumers. Heat demand and the seed "
            "network are explicit synthetic proxies; research buckets are not optimal-routing labels. "
            "Stress-extension buckets add large buildings, many connection points, mixed-scale loads, "
            "and multiple existing tie-in chambers."
        ),
        "source_corpus": "data/real_geojson_places",
        "split_policy": "geographic seed groups; one seed belongs to exactly one split",
        "records": records,
        "failures": failures,
        "summary": {
            "scene_count": len(records),
            "failure_count": len(failures),
            "required_failure_count": sum(1 for failure in failures if not failure.get("optional_extension")),
            "optional_extension_failure_count": sum(1 for failure in failures if failure.get("optional_extension")),
            "stale_scene_count_removed": stale_scene_count,
            "split_counts": dict(sorted(Counter(record["split"] for record in records).items())),
            "bucket_counts": dict(sorted(Counter(record["research_bucket"] for record in records).items())),
            "seed_counts": dict(sorted(Counter(record["seed"] for record in records).items())),
            "total_restrictions": sum(record["restriction_count"] for record in records),
            "total_connection_points": sum(record["oks_count"] for record in records),
        },
    }
    manifest["records_hash"] = sha256_bytes(canonical_json(records))
    args.out_dir.mkdir(parents=True, exist_ok=True)
    manifest_path = args.out_dir / "scene_manifest.json"
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(manifest["summary"], ensure_ascii=False, indent=2))
    print(manifest_path)
    return 0 if records else 2


if __name__ == "__main__":
    raise SystemExit(main())
