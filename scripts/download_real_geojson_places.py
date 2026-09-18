#!/usr/bin/env python3
"""Download real OSM GeoJSON windows for routing research.

This script does not invent heat-network objects. It downloads only real
OpenStreetMap geometry through Overpass and classifies map windows by measurable
spatial features so we can collect 100 real places for each research bucket:

- separate_pipes
- shared_pipe
- fifty_fifty
- refusal

The labels are research buckets, not official ground truth.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import pathlib
import time
import urllib.parse
import urllib.request
from typing import Dict, Iterable, List, Optional, Sequence, Tuple


ROOT = pathlib.Path(__file__).resolve().parents[1]
OUT_DIR = ROOT / "data" / "real_geojson_places"
RAW_DIR = OUT_DIR / "raw_osm"
OVERPASS_ENDPOINTS = [
    "https://lz4.overpass-api.de/api/interpreter",
    "https://overpass-api.de/api/interpreter",
    "https://z.overpass-api.de/api/interpreter",
    "https://overpass.openstreetmap.ru/api/interpreter",
    "https://overpass.kumi.systems/api/interpreter",
]
CATEGORIES = ("separate_pipes", "shared_pipe", "fifty_fifty", "refusal")

Coord = Tuple[float, float]
BBox = Tuple[float, float, float, float]  # south, west, north, east


CITY_SEEDS = [
    ("moscow_center", "Москва", 55.7558, 37.6173),
    ("moscow_zil", "Москва ЗИЛ", 55.6990, 37.6420),
    ("moscow_kommunarka", "Москва Коммунарка", 55.5670, 37.4700),
    ("moscow_mitino", "Москва Митино", 55.8460, 37.3610),
    ("moscow_nekrasovka", "Москва Некрасовка", 55.7050, 37.9300),
    ("saint_petersburg", "Санкт-Петербург", 59.9343, 30.3351),
    ("spb_parnas", "Санкт-Петербург Парнас", 60.0690, 30.3450),
    ("kazan_center", "Казань", 55.7961, 49.1064),
    ("kazan_kaban", "Казань Кабан", 55.7750, 49.1300),
    ("innopolis", "Иннополис", 55.7520, 48.7440),
    ("nizhny_novgorod", "Нижний Новгород", 56.3269, 44.0059),
    ("nn_strelka", "Нижний Новгород Стрелка", 56.3330, 43.9950),
    ("ekaterinburg", "Екатеринбург", 56.8389, 60.6057),
    ("novosibirsk", "Новосибирск", 55.0302, 82.9204),
    ("samara", "Самара", 53.1959, 50.1008),
    ("rostov_on_don", "Ростов-на-Дону", 47.2357, 39.7015),
    ("ufa", "Уфа", 54.7388, 55.9721),
    ("perm", "Пермь", 58.0105, 56.2502),
    ("krasnoyarsk", "Красноярск", 56.0153, 92.8932),
    ("voronezh", "Воронеж", 51.6608, 39.2003),
    ("volgograd", "Волгоград", 48.7080, 44.5133),
    ("krasnodar", "Краснодар", 45.0355, 38.9753),
    ("tyumen", "Тюмень", 57.1530, 65.5343),
    ("irkutsk", "Иркутск", 52.2871, 104.2807),
    ("vladivostok", "Владивосток", 43.1155, 131.8855),
    ("yaroslavl", "Ярославль", 57.6261, 39.8845),
    ("tula", "Тула", 54.1930, 37.6173),
    ("chelyabinsk", "Челябинск", 55.1644, 61.4368),
    ("omsk", "Омск", 54.9885, 73.3242),
    ("saratov", "Саратов", 51.5336, 46.0343),
    ("izhevsk", "Ижевск", 56.8527, 53.2115),
    ("barnaul", "Барнаул", 53.3481, 83.7798),
    ("ulyanovsk", "Ульяновск", 54.3182, 48.3838),
    ("tomsk", "Томск", 56.4846, 84.9482),
    ("kemerovo", "Кемерово", 55.3547, 86.0884),
    ("ryazan", "Рязань", 54.6296, 39.7419),
    ("kaliningrad", "Калининград", 54.7104, 20.4522),
    ("lipetsk", "Липецк", 52.6088, 39.5992),
    ("cheboksary", "Чебоксары", 56.1439, 47.2489),
    ("vologda", "Вологда", 59.2205, 39.8915),
]


def meters_to_lat(meters: float) -> float:
    return meters / 111_320.0


def meters_to_lon(meters: float, lat: float) -> float:
    return meters / (111_320.0 * max(math.cos(math.radians(lat)), 0.2))


def haversine_m(a: Coord, b: Coord) -> float:
    lon1, lat1 = a
    lon2, lat2 = b
    radius = 6_371_000.0
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp = math.radians(lat2 - lat1)
    dl = math.radians(lon2 - lon1)
    h = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * radius * math.asin(math.sqrt(h))


def line_length_m(coords: Sequence[Coord]) -> float:
    return sum(haversine_m(a, b) for a, b in zip(coords, coords[1:]))


def window_id(seed_slug: str, row: int, col: int, lat: float, lon: float) -> str:
    digest = hashlib.sha1(f"{seed_slug}:{row}:{col}:{lat:.6f}:{lon:.6f}".encode()).hexdigest()[:8]
    return f"{seed_slug}_{row:+d}_{col:+d}_{digest}"


def build_candidates(grid_radius: int, step_m: float, window_m: float) -> List[Dict]:
    candidates: List[Dict] = []
    half_lat = meters_to_lat(window_m / 2)
    for seed_slug, city, center_lat, center_lon in CITY_SEEDS:
        for row in range(-grid_radius, grid_radius + 1):
            for col in range(-grid_radius, grid_radius + 1):
                lat = center_lat + meters_to_lat(row * step_m)
                lon = center_lon + meters_to_lon(col * step_m, center_lat)
                half_lon = meters_to_lon(window_m / 2, lat)
                bbox = (lat - half_lat, lon - half_lon, lat + half_lat, lon + half_lon)
                candidates.append(
                    {
                        "id": window_id(seed_slug, row, col, lat, lon),
                        "seed": seed_slug,
                        "city": city,
                        "center": [round(lon, 7), round(lat, 7)],
                        "bbox": [round(v, 7) for v in bbox],
                        "grid_row": row,
                        "grid_col": col,
                    }
                )
    return candidates


def overpass_query(bbox: BBox, include_relations: bool, overpass_timeout: int) -> str:
    south, west, north, east = bbox
    box = f"{south},{west},{north},{east}"
    relation_block = ""
    if include_relations:
        relation_block = f"""
  relation({box})["natural"="water"];
  relation({box})["water"];
  relation({box})["leisure"="park"];
  relation({box})["landuse"~"forest|recreation_ground|cemetery|grass"];
"""
    return f"""
[out:json][timeout:{overpass_timeout}];
(
  way({box})["building"];
  way({box})["highway"];
  way({box})["railway"];
  way({box})["waterway"];
  way({box})["natural"="water"];
  way({box})["water"];
  way({box})["leisure"="park"];
  way({box})["landuse"~"forest|recreation_ground|cemetery|grass"];
{relation_block}
);
out geom;
"""


def download_osm(
    candidate: Dict,
    endpoints: Sequence[str],
    include_relations: bool,
    refresh: bool,
    request_timeout: float,
    overpass_timeout: int,
    verbose: bool,
) -> Dict:
    RAW_DIR.mkdir(parents=True, exist_ok=True)
    raw_path = RAW_DIR / f"{candidate['id']}.json"
    if raw_path.exists() and not refresh:
        return json.loads(raw_path.read_text(encoding="utf-8"))

    data = urllib.parse.urlencode(
        {"data": overpass_query(tuple(candidate["bbox"]), include_relations, overpass_timeout)}
    ).encode("utf-8")
    errors = []
    for endpoint in endpoints:
        if verbose:
            print(f"  try {endpoint}", flush=True)
        request = urllib.request.Request(
            endpoint,
            data=data,
            headers={"User-Agent": "MosHackathonRealGeoJSON/0.2"},
        )
        try:
            with urllib.request.urlopen(request, timeout=request_timeout) as response:
                payload = json.load(response)
            raw_path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
            return payload
        except Exception as error:
            errors.append(f"{endpoint}: {type(error).__name__}: {error}")
    raise RuntimeError("; ".join(errors))


def osm_kind(tags: Dict[str, str]) -> Optional[str]:
    if "building" in tags:
        return "building"
    if "railway" in tags:
        return "railway"
    if "highway" in tags:
        if tags.get("highway") in {"motorway", "trunk", "primary", "secondary"}:
            return "major_road"
        return "road"
    if tags.get("natural") == "water" or "water" in tags or "waterway" in tags:
        return "water"
    if tags.get("leisure") == "park" or tags.get("landuse") in {"forest", "recreation_ground", "cemetery", "grass"}:
        return "green"
    return None


def geometry_from_points(points: Sequence[Dict], tags: Dict[str, str]) -> Optional[Dict]:
    if len(points) < 2:
        return None
    coords = [(round(point["lon"], 8), round(point["lat"], 8)) for point in points]
    closed = len(coords) >= 4 and coords[0] == coords[-1]
    area_tags = (
        "building" in tags
        or tags.get("natural") == "water"
        or "water" in tags
        or tags.get("leisure") == "park"
        or tags.get("landuse") in {"forest", "recreation_ground", "cemetery", "grass"}
    )
    if closed and area_tags:
        return {"type": "Polygon", "coordinates": [coords]}
    return {"type": "LineString", "coordinates": coords}


def element_geometries(element: Dict) -> Iterable[Tuple[Dict, Dict]]:
    tags = element.get("tags", {})
    if element.get("type") == "way":
        geometry = geometry_from_points(element.get("geometry") or [], tags)
        if geometry:
            yield geometry, {"osm_id": element.get("id"), "osm_type": "way"}
    elif element.get("type") == "relation":
        for index, member in enumerate(element.get("members") or [], start=1):
            geometry = geometry_from_points(member.get("geometry") or [], tags)
            if geometry:
                yield geometry, {
                    "osm_id": element.get("id"),
                    "osm_type": "relation",
                    "member_index": index,
                    "member_role": member.get("role", ""),
                }


def coord_iter(geometry: Dict) -> Iterable[Coord]:
    if geometry["type"] == "Point":
        yield tuple(geometry["coordinates"])
    elif geometry["type"] == "LineString":
        for coord in geometry["coordinates"]:
            yield tuple(coord)
    elif geometry["type"] == "Polygon":
        for ring in geometry["coordinates"]:
            for coord in ring:
                yield tuple(coord)


def bbox_from_coords(coords: Sequence[Coord]) -> Optional[Tuple[float, float, float, float]]:
    if not coords:
        return None
    lons = [coord[0] for coord in coords]
    lats = [coord[1] for coord in coords]
    return min(lons), min(lats), max(lons), max(lats)


def centroid_from_geometry(geometry: Dict) -> Optional[Coord]:
    coords = list(coord_iter(geometry))
    bbox = bbox_from_coords(coords)
    if not bbox:
        return None
    west, south, east, north = bbox
    return (west + east) / 2, (south + north) / 2


def to_geojson_features(payload: Dict, candidate: Dict) -> List[Dict]:
    features: List[Dict] = []
    for element in payload.get("elements", []):
        tags = element.get("tags", {})
        kind = osm_kind(tags)
        if not kind:
            continue
        for geometry, ids in element_geometries(element):
            props = {
                "osm_kind": kind,
                "name": tags.get("name", ""),
                "source": "OpenStreetMap",
                "download_window_id": candidate["id"],
                "download_city": candidate["city"],
                "osm_tags": {
                    key: value
                    for key, value in sorted(tags.items())
                    if key
                    in {
                        "building",
                        "highway",
                        "railway",
                        "water",
                        "waterway",
                        "natural",
                        "leisure",
                        "landuse",
                        "name",
                    }
                },
            }
            props.update(ids)
            features.append({"type": "Feature", "properties": props, "geometry": geometry})
    return features


def compute_metrics(features: Sequence[Dict], candidate: Dict) -> Dict:
    counts = {kind: 0 for kind in ("building", "road", "major_road", "railway", "water", "green")}
    lengths = {kind: 0.0 for kind in ("road", "major_road", "railway", "water")}
    building_centers: List[Coord] = []

    for feature in features:
        kind = feature["properties"]["osm_kind"]
        counts[kind] = counts.get(kind, 0) + 1
        geometry = feature["geometry"]
        if kind == "building":
            center = centroid_from_geometry(geometry)
            if center:
                building_centers.append(center)
        elif geometry["type"] == "LineString" and kind in lengths:
            lengths[kind] += line_length_m(list(coord_iter(geometry)))

    south, west, north, east = candidate["bbox"]
    diag = max(haversine_m((west, south), (east, north)), 1.0)
    spread = 0.0
    if building_centers:
        lon = sum(point[0] for point in building_centers) / len(building_centers)
        lat = sum(point[1] for point in building_centers) / len(building_centers)
        spread = sum(haversine_m((lon, lat), point) for point in building_centers) / len(building_centers) / diag

    barrier_score = (
        0.002 * lengths["water"]
        + 0.0015 * lengths["railway"]
        + 0.001 * lengths["major_road"]
        + 1.5 * counts["water"]
        + 0.8 * counts["railway"]
    )
    road_score = 0.0005 * (lengths["road"] + lengths["major_road"]) + 0.08 * (counts["road"] + counts["major_road"])
    building_density = min(counts["building"] / 80.0, 3.0)
    compactness = max(0.0, 1.0 - min(spread, 1.0))

    return {
        "counts": counts,
        "lengths_m": {key: round(value, 2) for key, value in lengths.items()},
        "building_centers": len(building_centers),
        "building_spread": round(spread, 4),
        "barrier_score": round(barrier_score, 4),
        "road_score": round(road_score, 4),
        "category_scores": {
            "shared_pipe": round(2.5 * building_density + 2.0 * compactness - 0.7 * barrier_score, 4),
            "separate_pipes": round(2.0 * building_density + 2.4 * spread + 0.4 * road_score - 0.4 * barrier_score, 4),
            "fifty_fifty": round(2.0 * building_density + 2.0 * (1.0 - abs(spread - 0.45)) - 0.25 * abs(barrier_score - 2.0), 4),
            "refusal": round(1.5 * building_density + 2.5 * barrier_score + 0.8 * (1.0 if counts["water"] else 0.0), 4),
        },
    }


def write_place_geojson(candidate: Dict, category: str, rank: int, features: Sequence[Dict], metrics: Dict) -> pathlib.Path:
    target_dir = OUT_DIR / category
    target_dir.mkdir(parents=True, exist_ok=True)
    filename = f"{rank:03d}_{candidate['id']}.geojson"
    path = target_dir / filename
    collection = {
        "type": "FeatureCollection",
        "name": candidate["id"],
        "metadata": {
            "category": category,
            "rank": rank,
            "city": candidate["city"],
            "seed": candidate["seed"],
            "center": candidate["center"],
            "bbox_south_west_north_east": candidate["bbox"],
            "source": "OpenStreetMap via Overpass API",
            "note": "Real OSM geometry only. Category is a heuristic research bucket, not heat-network ground truth.",
            "metrics": metrics,
        },
        "features": list(features),
    }
    path.write_text(json.dumps(collection, ensure_ascii=False, indent=2), encoding="utf-8")
    return path


def enough(selected: Dict[str, List[Dict]], target: int) -> bool:
    return all(len(selected[category]) >= target for category in CATEGORIES)


def select_category(metrics: Dict, selected: Dict[str, List[Dict]], target: int) -> str:
    scores = metrics["category_scores"]
    ordered = sorted(CATEGORIES, key=lambda category: scores[category], reverse=True)
    for category in ordered:
        if len(selected[category]) < target:
            return category
    return ordered[0]


def export_candidate_plan(candidates: Sequence[Dict]) -> pathlib.Path:
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    path = OUT_DIR / "candidate_windows.json"
    path.write_text(json.dumps(candidates, ensure_ascii=False, indent=2), encoding="utf-8")
    return path


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Download 400 real OSM GeoJSON place windows.")
    parser.add_argument("--plan-only", action="store_true", help="Only write candidate_windows.json; no network calls.")
    parser.add_argument("--download", action="store_true", help="Download OSM data and export selected GeoJSON files.")
    parser.add_argument("--target-per-category", type=int, default=100, help="Default: 100.")
    parser.add_argument("--max-candidates", type=int, default=1600, help="Maximum candidate windows to try.")
    parser.add_argument("--grid-radius", type=int, default=3, help="Grid radius around every city seed. Default gives 49 windows per seed.")
    parser.add_argument("--step-m", type=float, default=850.0, help="Distance between candidate centers.")
    parser.add_argument("--window-m", type=float, default=900.0, help="Candidate bbox size.")
    parser.add_argument("--sleep", type=float, default=1.2, help="Delay between successful Overpass requests.")
    parser.add_argument("--endpoint", action="append", help="Override Overpass endpoint; can be repeated.")
    parser.add_argument("--request-timeout", type=float, default=20.0, help="HTTP timeout per Overpass endpoint in seconds.")
    parser.add_argument("--overpass-timeout", type=int, default=20, help="Timeout embedded into Overpass QL.")
    parser.add_argument("--rate-limit-sleep", type=float, default=90.0, help="Sleep seconds after Overpass 429 errors.")
    parser.add_argument("--error-sleep", type=float, default=8.0, help="Sleep seconds after non-rate-limit download errors.")
    parser.add_argument("--start-index", type=int, default=0, help="Skip the first N candidate windows.")
    parser.add_argument("--verbose", action="store_true", help="Print every endpoint attempt.")
    parser.add_argument("--probe", action="store_true", help="Run a tiny Overpass probe against configured endpoints and exit.")
    parser.add_argument("--include-relations", action="store_true", help="Download large OSM relations too; slower.")
    parser.add_argument("--refresh", action="store_true", help="Re-download raw OSM cache.")
    parser.add_argument("--min-features", type=int, default=20, help="Skip windows with fewer real OSM features.")
    return parser.parse_args()


def probe_endpoints(endpoints: Sequence[str], request_timeout: float) -> int:
    query = '[out:json][timeout:10];way(55.755,37.617,55.756,37.618)["highway"];out geom 5;'
    data = urllib.parse.urlencode({"data": query}).encode("utf-8")
    ok_count = 0
    for endpoint in endpoints:
        started = time.time()
        request = urllib.request.Request(
            endpoint,
            data=data,
            headers={"User-Agent": "MosHackathonRealGeoJSONProbe/0.2"},
        )
        try:
            with urllib.request.urlopen(request, timeout=request_timeout) as response:
                payload = json.load(response)
            elapsed = time.time() - started
            print(f"OK  {endpoint}  {elapsed:.2f}s  elements={len(payload.get('elements', []))}")
            ok_count += 1
        except Exception as error:
            elapsed = time.time() - started
            print(f"ERR {endpoint}  {elapsed:.2f}s  {type(error).__name__}: {error}")
    return 0 if ok_count else 2


def main() -> int:
    args = parse_args()
    candidates = build_candidates(args.grid_radius, args.step_m, args.window_m)
    plan_path = export_candidate_plan(candidates)
    print(f"candidate plan: {plan_path} ({len(candidates)} windows)")

    endpoints = args.endpoint or OVERPASS_ENDPOINTS
    if args.probe:
        return probe_endpoints(endpoints, args.request_timeout)

    if args.plan_only or not args.download:
        if not args.plan_only:
            print("pass --download to fetch real OSM GeoJSON files")
        return 0

    selected: Dict[str, List[Dict]] = {category: [] for category in CATEGORIES}
    index: List[Dict] = []
    errors: List[Dict] = []

    stop_index = min(len(candidates), args.max_candidates)
    for absolute_index, candidate in enumerate(candidates[args.start_index : stop_index], start=args.start_index):
        if enough(selected, args.target_per_category):
            break
        try:
            print(
                f"[{absolute_index + 1}/{stop_index}] {candidate['city']} {candidate['id']}",
                flush=True,
            )
            payload = download_osm(
                candidate,
                endpoints,
                args.include_relations,
                args.refresh,
                args.request_timeout,
                args.overpass_timeout,
                args.verbose,
            )
            features = to_geojson_features(payload, candidate)
            if len(features) < args.min_features:
                continue
            metrics = compute_metrics(features, candidate)
            category = select_category(metrics, selected, args.target_per_category)
            if len(selected[category]) >= args.target_per_category:
                continue
            rank = len(selected[category]) + 1
            path = write_place_geojson(candidate, category, rank, features, metrics)
            record = {
                "category": category,
                "rank": rank,
                "id": candidate["id"],
                "city": candidate["city"],
                "path": str(path.relative_to(ROOT)),
                "feature_count": len(features),
                "metrics": metrics,
            }
            selected[category].append(record)
            index.append(record)
            print(f"{category} {rank:03d}: {candidate['city']} {candidate['id']} -> {len(features)} features")
            time.sleep(args.sleep)
        except Exception as error:
            errors.append({"id": candidate["id"], "city": candidate["city"], "error": str(error)})
            print(f"ERROR {candidate['id']}: {error}")
            if "429" in str(error):
                print(f"rate limit: sleeping {args.rate_limit_sleep:.0f}s", flush=True)
                time.sleep(args.rate_limit_sleep)
            else:
                time.sleep(max(args.sleep, args.error_sleep))

    OUT_DIR.mkdir(parents=True, exist_ok=True)
    (OUT_DIR / "index.json").write_text(json.dumps(index, ensure_ascii=False, indent=2), encoding="utf-8")
    (OUT_DIR / "errors.json").write_text(json.dumps(errors, ensure_ascii=False, indent=2), encoding="utf-8")
    summary = {category: len(selected[category]) for category in CATEGORIES}
    print(f"summary: {summary}")
    if not enough(selected, args.target_per_category):
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
