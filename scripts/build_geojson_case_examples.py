#!/usr/bin/env python3
"""Build small research GeoJSON examples from real Russian OSM areas.

The generated files are not official heat-network datasets. They combine
real OSM geometry for the surrounding territory with synthetic heat-network
features shaped to stress four decision cases:

1. separate branches are expected to be better;
2. a shared trunk is expected to be better;
3. borderline / mixed decision;
4. refusal can be cheaper than connection.
"""

from __future__ import annotations

import json
import math
import argparse
import pathlib
import sys
import time
import urllib.parse
import urllib.request
from typing import Dict, Iterable, List, Tuple


ROOT = pathlib.Path(__file__).resolve().parents[1]
OUT_DIR = ROOT / "data" / "research_examples"
RAW_DIR = OUT_DIR / "raw_osm"
OVERPASS_ENDPOINTS = [
    "https://overpass-api.de/api/interpreter",
    "https://overpass.kumi.systems/api/interpreter",
    "https://overpass.openstreetmap.ru/api/interpreter",
]

Color = str
Coord = Tuple[float, float]


CASES = [
    {
        "slug": "01_separate_pipes_moscow_zil",
        "title": "Москва, ЗИЛ: лучше независимые ветки",
        "city": "Москва",
        "bbox": (55.6940, 37.6320, 55.7045, 37.6500),
        "expected_decision": "separate_pipes",
        "rationale": (
            "Точки разбросаны вдоль существующей магистрали. Общая труба даст "
            "лишний крюк и дополнительные камеры, поэтому выгоднее независимые подключения."
        ),
        "source": (37.6332, 55.6992),
        "network": [
            (37.6332, 55.6992),
            (37.6372, 55.6990),
            (37.6417, 55.6984),
            (37.6469, 55.6977),
        ],
        "points": [
            (37.6370, 55.7027, 16.0),
            (37.6439, 55.7012, 18.0),
            (37.6480, 55.6960, 12.0),
            (37.6356, 55.6956, 10.0),
        ],
    },
    {
        "slug": "02_shared_pipe_moscow_kommunarka",
        "title": "Москва, Коммунарка: лучше общая труба",
        "city": "Москва",
        "bbox": (55.5620, 37.4620, 55.5720, 37.4780),
        "expected_decision": "shared_pipe",
        "rationale": (
            "Будущие точки компактно лежат за одним коридором от существующей сети. "
            "Общий ствол до камеры ветвления должен выигрывать по длине и стоимости."
        ),
        "source": (37.4629, 55.5662),
        "network": [
            (37.4629, 55.5662),
            (37.4668, 55.5664),
            (37.4707, 55.5667),
        ],
        "points": [
            (37.4743, 55.5684, 24.0),
            (37.4750, 55.5676, 20.0),
            (37.4734, 55.5671, 18.0),
            (37.4747, 55.5665, 16.0),
        ],
    },
    {
        "slug": "03_fifty_fifty_innopolis",
        "title": "Иннополис: пограничный случай 50/50",
        "city": "Иннополис",
        "bbox": (55.7440, 48.7350, 55.7540, 48.7550),
        "expected_decision": "mixed_or_borderline",
        "rationale": (
            "Точки образуют две небольшие группы. Внутри каждой группы общий участок "
            "может быть выгоден, но общий ствол на все точки уже спорный."
        ),
        "source": (48.7373, 55.7492),
        "network": [
            (48.7373, 55.7492),
            (48.7417, 55.7494),
            (48.7461, 55.7494),
        ],
        "points": [
            (48.7481, 55.7510, 12.0),
            (48.7491, 55.7504, 12.0),
            (48.7522, 55.7483, 11.0),
            (48.7530, 55.7477, 11.0),
        ],
    },
    {
        "slug": "04_refusal_nizhny_novgorod_strelka",
        "title": "Нижний Новгород, Стрелка: отказ как допустимый исход",
        "city": "Нижний Новгород",
        "bbox": (56.3200, 43.9700, 56.3400, 44.0200),
        "expected_decision": "refusal_candidate",
        "rationale": (
            "Точка вынесена за водную и транспортную преграду. На таком примере "
            "нужно проверять штраф за неподключение против дорогого перехода."
        ),
        "source": (43.9820, 56.3270),
        "network": [
            (43.9820, 56.3270),
            (43.9910, 56.3278),
            (44.0000, 56.3282),
        ],
        "points": [
            (44.0125, 56.3362, 6.0),
        ],
    },
]


def query_overpass(case: Dict, include_relations: bool) -> Dict:
    RAW_DIR.mkdir(parents=True, exist_ok=True)
    raw_path = RAW_DIR / f"{case['slug']}.json"
    if raw_path.exists():
        return json.loads(raw_path.read_text(encoding="utf-8"))

    south, west, north, east = case["bbox"]
    bbox = f"{south},{west},{north},{east}"
    relation_block = ""
    if include_relations:
        relation_block = f"""
  relation({bbox})["natural"="water"];
  relation({bbox})["water"];
  relation({bbox})["leisure"="park"];
  relation({bbox})["landuse"~"grass|forest|recreation_ground|cemetery"];
"""
    query = f"""
[out:json][timeout:45];
(
  way({bbox})["building"];
  way({bbox})["highway"];
  way({bbox})["railway"];
  way({bbox})["waterway"];
  way({bbox})["natural"="water"];
  way({bbox})["water"];
  way({bbox})["leisure"="park"];
  way({bbox})["landuse"~"grass|forest|recreation_ground|cemetery"];
{relation_block}
);
out geom;
"""
    data = urllib.parse.urlencode({"data": query}).encode("utf-8")
    last_error: Exception | None = None
    payload = None
    for endpoint in OVERPASS_ENDPOINTS:
        request = urllib.request.Request(
            endpoint,
            data=data,
            headers={"User-Agent": "MosHackathonResearch/0.1"},
        )
        try:
            with urllib.request.urlopen(request, timeout=70) as response:
                payload = json.load(response)
            break
        except Exception as error:  # Network research helper: try the next public mirror.
            last_error = error
            time.sleep(2.0)
    if payload is None:
        raise RuntimeError(f"Overpass request failed for {case['slug']}: {last_error}")
    raw_path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    time.sleep(1.0)
    return payload


def classify(tags: Dict[str, str]) -> str:
    if "building" in tags:
        return "oks"
    if "railway" in tags:
        return "railway"
    if "highway" in tags:
        if tags.get("railway") == "tram" or tags.get("tram") == "yes":
            return "tram"
        return "road"
    if tags.get("natural") == "water" or "water" in tags or "waterway" in tags:
        return "water"
    if tags.get("leisure") == "park" or tags.get("landuse") in {"grass", "forest", "recreation_ground", "cemetery"}:
        return "park"
    return "other"


def geometry_from_points(points: List[Dict], tags: Dict[str, str]) -> Dict | None:
    geom = points or []
    if len(geom) < 2:
        return None
    coords = [(round(p["lon"], 8), round(p["lat"], 8)) for p in geom]
    is_closed = len(coords) >= 4 and coords[0] == coords[-1]
    is_area = is_closed and (
        "building" in tags
        or tags.get("natural") == "water"
        or "water" in tags
        or tags.get("leisure") == "park"
        or tags.get("landuse") in {"grass", "forest", "recreation_ground", "cemetery"}
    )
    if is_area:
        return {"type": "Polygon", "coordinates": [coords]}
    return {"type": "LineString", "coordinates": coords}


def osm_features(case: Dict, payload: Dict) -> List[Dict]:
    features = []
    for element in payload.get("elements", []):
        tags = element.get("tags", {})
        restriction_type = classify(tags)
        if restriction_type == "other":
            continue
        member_geometries = []
        if element.get("type") == "way":
            geom = geometry_from_points(element.get("geometry") or [], tags)
            if geom:
                member_geometries.append((f"osm_way_{element['id']}", geom, {"osm_way_id": element["id"]}))
        elif element.get("type") == "relation":
            for member_index, member in enumerate(element.get("members") or [], start=1):
                geom = geometry_from_points(member.get("geometry") or [], tags)
                if geom:
                    member_geometries.append(
                        (
                            f"osm_relation_{element['id']}_{member_index}",
                            geom,
                            {"osm_relation_id": element["id"], "osm_member_role": member.get("role", "")},
                        )
                    )
        for feature_id, geom, extra_props in member_geometries:
            name = tags.get("name") or tags.get("addr:housenumber") or ""
            props = {
                "id": feature_id,
                "object_type": "restriction",
                "restriction_type": restriction_type,
                "name": name,
                "osm_tags": {k: tags[k] for k in sorted(tags) if k in {"building", "highway", "railway", "natural", "water", "waterway", "leisure", "landuse", "name"}},
                "case_id": case["slug"],
            }
            props.update(extra_props)
            features.append({"type": "Feature", "properties": props, "geometry": geom})
    features.sort(key=lambda feature: (feature["properties"]["restriction_type"], feature["properties"]["id"]))
    return features[:500]


def synthetic_features(case: Dict) -> List[Dict]:
    features = []
    features.append(
        {
            "type": "Feature",
            "properties": {
                "id": f"{case['slug']}_source",
                "object_type": "source",
                "name": "synthetic_source_for_research",
                "case_id": case["slug"],
            },
            "geometry": {"type": "Point", "coordinates": list(case["source"])},
        }
    )
    features.append(
        {
            "type": "Feature",
            "properties": {
                "id": f"{case['slug']}_heat_network_main",
                "object_type": "heat_network",
                "diameter": 500,
                "flow_tph": 120.0,
                "upstream_object_id": f"{case['slug']}_source",
                "case_id": case["slug"],
            },
            "geometry": {"type": "LineString", "coordinates": [list(p) for p in case["network"]]},
        }
    )
    for index, point in enumerate(case["network"][1:], start=1):
        features.append(
            {
                "type": "Feature",
                "properties": {
                    "id": f"{case['slug']}_chamber_{index}",
                    "object_type": "heat_chamber",
                    "diameter": 500,
                    "upstream_object_id": f"{case['slug']}_source",
                    "case_id": case["slug"],
                },
                "geometry": {"type": "Point", "coordinates": list(point)},
            }
        )
    for index, (lon, lat, flow) in enumerate(case["points"], start=1):
        features.append(
            {
                "type": "Feature",
                "properties": {
                    "id": f"{case['slug']}_cp_{index}",
                    "object_type": "oks_connection_point",
                    "oks_id": f"{case['slug']}_oks_{index}",
                    "flow_tph": flow,
                    "case_id": case["slug"],
                },
                "geometry": {"type": "Point", "coordinates": [lon, lat]},
            }
        )
    return features


def write_geojson(case: Dict, features: List[Dict]) -> pathlib.Path:
    path = OUT_DIR / f"{case['slug']}.geojson"
    collection = {
        "type": "FeatureCollection",
        "name": case["slug"],
        "crs": {"type": "name", "properties": {"name": "EPSG:4326"}},
        "metadata": {
            "title": case["title"],
            "city": case["city"],
            "bbox_south_west_north_east": case["bbox"],
            "expected_decision": case["expected_decision"],
            "rationale": case["rationale"],
            "real_data_source": "OpenStreetMap via Overpass API, ODbL",
            "synthetic_layers": ["source", "heat_network", "heat_chamber", "oks_connection_point"],
            "note": "Research fixture: real territory geometry with synthetic heat-network task entities.",
        },
        "features": features,
    }
    path.write_text(json.dumps(collection, ensure_ascii=False, indent=2), encoding="utf-8")
    return path


def iter_coords(geometry: Dict) -> Iterable[Coord]:
    if geometry["type"] == "Point":
        yield tuple(geometry["coordinates"])
    elif geometry["type"] == "LineString":
        for coord in geometry["coordinates"]:
            yield tuple(coord)
    elif geometry["type"] == "Polygon":
        for ring in geometry["coordinates"]:
            for coord in ring:
                yield tuple(coord)


def project(coord: Coord, bounds: Tuple[float, float, float, float], width: int, height: int) -> Tuple[float, float]:
    lon, lat = coord
    west, south, east, north = bounds
    x = (lon - west) / (east - west) * width
    y = (north - lat) / (north - south) * height
    return x, y


def path_points(coords: Iterable[Coord], bounds: Tuple[float, float, float, float], width: int, height: int) -> str:
    return " ".join(f"{x:.1f},{y:.1f}" for x, y in (project(c, bounds, width, height) for c in coords))


def render_svg(case: Dict, features: List[Dict]) -> pathlib.Path:
    width, height = 900, 640
    south, west, north, east = case["bbox"]
    bounds = (west, south, east, north)
    colors: Dict[str, Color] = {
        "oks": "#D8D8D8",
        "road": "#F4B942",
        "railway": "#7E57C2",
        "water": "#6CB6FF",
        "park": "#8BCB88",
    }
    lines = [
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" viewBox="0 0 {width} {height}">',
        '<rect width="100%" height="100%" fill="#FAFAFA"/>',
        f'<text x="24" y="34" font-family="Arial" font-size="22" font-weight="700" fill="#222">{case["title"]}</text>',
        f'<text x="24" y="58" font-family="Arial" font-size="13" fill="#555">{case["expected_decision"]}: {case["rationale"]}</text>',
    ]
    for feature in features:
        props = feature["properties"]
        geom = feature["geometry"]
        object_type = props.get("object_type")
        if object_type == "restriction":
            rtype = props.get("restriction_type", "other")
            color = colors.get(rtype, "#CCCCCC")
            if geom["type"] == "Polygon":
                pts = path_points(geom["coordinates"][0], bounds, width, height)
                lines.append(f'<polygon points="{pts}" fill="{color}" fill-opacity="0.40" stroke="{color}" stroke-opacity="0.65" stroke-width="1"/>')
            elif geom["type"] == "LineString":
                pts = path_points(geom["coordinates"], bounds, width, height)
                stroke_width = 4 if rtype == "road" else 3
                lines.append(f'<polyline points="{pts}" fill="none" stroke="{color}" stroke-opacity="0.70" stroke-width="{stroke_width}"/>')
    for feature in features:
        props = feature["properties"]
        geom = feature["geometry"]
        object_type = props.get("object_type")
        if object_type == "heat_network":
            pts = path_points(geom["coordinates"], bounds, width, height)
            lines.append(f'<polyline points="{pts}" fill="none" stroke="#E53935" stroke-width="6" stroke-linecap="round" stroke-linejoin="round"/>')
        elif object_type == "source":
            x, y = project(tuple(geom["coordinates"]), bounds, width, height)
            lines.append(f'<circle cx="{x:.1f}" cy="{y:.1f}" r="9" fill="#B71C1C" stroke="white" stroke-width="2"/>')
        elif object_type == "heat_chamber":
            x, y = project(tuple(geom["coordinates"]), bounds, width, height)
            lines.append(f'<rect x="{x-6:.1f}" y="{y-6:.1f}" width="12" height="12" fill="#E53935" stroke="white" stroke-width="2"/>')
        elif object_type == "oks_connection_point":
            x, y = project(tuple(geom["coordinates"]), bounds, width, height)
            lines.append(f'<circle cx="{x:.1f}" cy="{y:.1f}" r="8" fill="#1E88E5" stroke="white" stroke-width="2"/>')
    legend = [
        ("#E53935", "existing heat layer"),
        ("#1E88E5", "connection points"),
        ("#D8D8D8", "buildings"),
        ("#F4B942", "roads"),
        ("#7E57C2", "rail/metro/tram"),
        ("#6CB6FF", "water"),
        ("#8BCB88", "parks/green"),
    ]
    y = height - 120
    lines.append(f'<g font-family="Arial" font-size="13" fill="#333">')
    for index, (color, label) in enumerate(legend):
        lx = 24 + (index % 4) * 210
        ly = y + (index // 4) * 28
        lines.append(f'<rect x="{lx}" y="{ly}" width="18" height="12" fill="{color}" fill-opacity="0.75"/>')
        lines.append(f'<text x="{lx + 26}" y="{ly + 11}">{label}</text>')
    lines.append("</g>")
    lines.append("</svg>")
    path = OUT_DIR / f"{case['slug']}.svg"
    path.write_text("\n".join(lines), encoding="utf-8")
    return path


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Download OSM/Overpass geometry for four heat-network research cases "
            "and generate GeoJSON plus SVG previews."
        )
    )
    parser.add_argument(
        "--case",
        choices=[case["slug"] for case in CASES],
        action="append",
        help="Build only the selected case. Can be passed multiple times.",
    )
    parser.add_argument(
        "--list-cases",
        action="store_true",
        help="Print available case ids and exit.",
    )
    parser.add_argument(
        "--refresh",
        action="store_true",
        help="Delete cached raw OSM JSON for selected cases before downloading.",
    )
    parser.add_argument(
        "--endpoint",
        action="append",
        help="Override Overpass endpoint. Can be passed multiple times.",
    )
    parser.add_argument(
        "--include-relations",
        action="store_true",
        help="Also request OSM relations for large water/park multipolygons. Slower and more likely to hit rate limits.",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    if args.list_cases:
        for case in CASES:
            print(f"{case['slug']}: {case['title']} -> {case['expected_decision']}")
        return 0
    if args.endpoint:
        OVERPASS_ENDPOINTS[:] = args.endpoint
    selected = {case["slug"] for case in CASES}
    if args.case:
        selected = set(args.case)
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    index = []
    for case in CASES:
        if case["slug"] not in selected:
            continue
        if args.refresh:
            raw_path = RAW_DIR / f"{case['slug']}.json"
            if raw_path.exists():
                raw_path.unlink()
        payload = query_overpass(case, args.include_relations)
        features = osm_features(case, payload) + synthetic_features(case)
        geojson_path = write_geojson(case, features)
        svg_path = render_svg(case, features)
        counts: Dict[str, int] = {}
        for feature in features:
            props = feature["properties"]
            key = props.get("restriction_type") or props.get("object_type")
            counts[key] = counts.get(key, 0) + 1
        index.append(
            {
                "slug": case["slug"],
                "title": case["title"],
                "city": case["city"],
                "expected_decision": case["expected_decision"],
                "geojson": str(geojson_path.relative_to(ROOT)),
                "preview": str(svg_path.relative_to(ROOT)),
                "feature_counts": counts,
            }
        )
        print(f"{case['slug']}: {len(features)} features -> {geojson_path.name}, {svg_path.name}")
    (OUT_DIR / "index.json").write_text(json.dumps(index, ensure_ascii=False, indent=2), encoding="utf-8")
    return 0


if __name__ == "__main__":
    sys.exit(main())
