"""Inspect all nearest straight entry rays for selected original OKS targets."""

import argparse
import hashlib
import json
import sys
from pathlib import Path

from pyproj import Transformer
from shapely.geometry import LineString, Point, shape
from shapely.ops import transform

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from benchmark_checker import required_centerline_clearance, select_diameter


PROJECT = Transformer.from_crs("EPSG:4326", "EPSG:32637", always_xy=True).transform
TARGET_IDS = ("2", "5", "10")
TOLERANCE = 0.001


def segments(geometry):
    if geometry.geom_type == "LineString":
        points = list(geometry.coords)
        return list(zip(points, points[1:]))
    return [segment for part in geometry.geoms for segment in segments(part)]


def intervals(geometry, point, ux, uy):
    parts = [geometry] if geometry.geom_type == "LineString" else list(geometry.geoms)
    raw = []
    for part in parts:
        if part.geom_type != "LineString":
            continue
        distances = [(x - point.x) * ux + (y - point.y) * uy for x, y in part.coords]
        if max(distances) - min(distances) > TOLERANCE:
            raw.append((max(0.0, min(distances)), max(distances)))
    raw.sort()
    merged = []
    for start, end in raw:
        if merged and start <= merged[-1][1] + TOLERANCE:
            merged[-1] = (merged[-1][0], max(end, merged[-1][1]))
        else:
            merged.append((start, end))
    return merged


def nearest_rays(point, polygon):
    projected = []
    for start, end in segments(polygon.boundary):
        segment = LineString([start, end])
        q = segment.interpolate(segment.project(point))
        projected.append((point.distance(q), q))
    minimum = min(distance for distance, _ in projected)
    distinct = []
    for distance, q in projected:
        if distance <= minimum + TOLERANCE and all(q.distance(old) > TOLERANCE for old in distinct):
            distinct.append(q)
    return minimum, distinct


def inspect(path):
    raw = path.read_bytes()
    features = json.loads(raw)["features"]
    mapped = [(feature["properties"], transform(PROJECT, shape(feature["geometry"])))
              for feature in features]
    networks = [(str(prop["id"]), geom) for prop, geom in mapped
                if prop.get("object_type") == "heat_network"]
    chambers = [(str(prop["id"]), geom) for prop, geom in mapped
                if prop.get("object_type") == "heat_chamber"]
    output = {"input_sha256": hashlib.sha256(raw).hexdigest(), "targets": []}
    for target_id in TARGET_IDS:
        target_prop, target = next((prop, geom) for prop, geom in mapped if
                                   prop.get("object_type") == "oks_connection_point"
                                   and str(prop.get("id")) == target_id)
        owners = [(str(prop["id"]), geom) for prop, geom in mapped if
                  prop.get("object_type") == "restriction"
                  and prop.get("restriction_type") == "oks" and geom.covers(target)]
        owner = owners[0][1]
        for _, polygon in owners[1:]:
            owner = owner.union(polygon)
        minimum, rays = nearest_rays(target, owner)
        flow = float(target_prop["flow_tph"])
        diameter = select_diameter(flow, 0)[0]
        required = required_centerline_clearance("oks", diameter, 0)
        item = {"id": target_id, "metric_point": [target.x, target.y],
                "owners": [name for name, _ in owners], "owner_valid": owner.is_valid,
                "polygon_parts": len(owner.geoms) if owner.geom_type == "MultiPolygon" else 1,
                "flow_tph": flow, "minimum_diameter_mm": diameter,
                "required_centerline_clearance_m": required,
                "nearest_distance_m": minimum, "rays": []}
        for q in rays:
            ux, uy = (q.x - target.x) / minimum, (q.y - target.y) / minimum
            horizon = max(target.distance(Point(x, y)) for x in
                          (owner.bounds[0], owner.bounds[2]) for y in
                          (owner.bounds[1], owner.bounds[3])) + 20
            ray = LineString([target, Point(target.x + ux * horizon, target.y + uy * horizon)])
            material = intervals(ray.intersection(owner), target, ux, uy)
            first_exit = material[0][1]
            first_reentry = material[1][0] if len(material) > 1 else None
            gap = first_reentry - first_exit if first_reentry is not None else None
            free = (LineString([Point(target.x + ux * first_exit, target.y + uy * first_exit),
                                Point(target.x + ux * first_reentry, target.y + uy * first_reentry)])
                    if first_reentry is not None else None)
            roots = [] if free is None else sorted(
                [(name, free.distance(geom), "network") for name, geom in networks]
                + [(name, free.distance(geom), "chamber") for name, geom in chambers],
                key=lambda row: row[1])[:3]
            polygons = list(owner.geoms) if owner.geom_type == "MultiPolygon" else [owner]
            boundary_ring = "interior" if any(
                ring.distance(q) <= TOLERANCE for polygon in polygons
                for ring in polygon.interiors) else "exterior"
            item["rays"].append({"boundary_metric": [q.x, q.y],
                                 "boundary_ring": boundary_ring,
                                 "material_intervals_m": material,
                                 "first_gap_m": gap,
                                 "max_possible_clearance_m": gap / 2 if gap else None,
                                 "clearance_shortfall_m": required - gap / 2 if gap else None,
                                 "nearby_existing_roots": roots})
        output["targets"].append(item)
    return output


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("input", type=Path)
    args = parser.parse_args()
    print(json.dumps(inspect(args.input), ensure_ascii=False, indent=2))
