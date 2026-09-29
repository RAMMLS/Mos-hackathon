import argparse
import hashlib
import json
import math
import platform
import sys
import time
from collections import defaultdict, deque
from pathlib import Path


CATALOG = [
    (50, 3.5, 181, 74023),
    (65, 8.3, 245, 78631),
    (80, 13.2, 327, 83530),
    (100, 22.3, 419, 89748),
    (125, 40.2, 554, 97275),
    (150, 65.1, 696, 105507),
    (200, 152.3, 1042, 120275),
    (250, 274.9, 1379, 135323),
    (300, 437.4, 1718, 150022),
    (400, 943.1, 2477, 190299),
    (500, 1663.4, 3245, 224137),
    (600, 2627.7, 4037, 264790),
    (700, 3735.1, 4775, 324298),
    (800, 5296.8, 5644, 325996),
    (900, 7165.0, 6518, 327693),
    (1000, 9391.8, 7419, 418777),
    (1200, 15012.8, 9288, 428074),
    (1400, 22501.9, 11276, 683417),
]

OUTPUT_REQUIRED_FIELDS = {
    "heat_network": {
        "id", "object_type", "variant_id", "start_node_id", "end_node_id",
        "flow_tph", "diameter", "length", "laying_method", "cost",
    },
    "heat_chamber": {"id", "object_type", "variant_id", "diameter", "cost"},
    "technical_node": {"id", "object_type", "variant_id"},
    "variant_summary": {
        "id", "object_type", "variant_id", "rank", "construction_cost",
        "chamber_construction_cost", "existing_chamber_tie_in_count",
        "existing_chamber_tie_in_cost", "unconnected_penalty",
        "calculated_cost", "new_network_length", "score", "unconnected_oks_ids",
    },
}

FORBIDDEN_RESTRICTION_TYPES = {"oks", "water", "railway", "park", "social_area", "prohibited_site"}
CLEARANCE_RULES_VERSION = "clearance-rules-v1"
PAIR_WIDTHS = {
    50: 0.400, 65: 0.430, 80: 0.470, 100: 0.510, 125: 0.600,
    150: 0.650, 200: 0.880, 250: 1.050, 300: 1.150, 400: 1.370,
    500: 1.670, 600: 1.850, 700: 2.050, 800: 2.250, 900: 2.450,
    1000: 2.650, 1200: 3.100, 1400: 3.450,
}
ENDPOINT_APPROACH_TOLERANCE_METERS = 0.25
MAX_TURN_ANGLE_DEGREES = 90.0
TURN_ANGLE_TOLERANCE_DEGREES = 0.05
DOCUMENT_NEAREST_V1 = "DOCUMENT_NEAREST_V1"
EXPERIMENTAL_ANY_BOUNDARY_V1 = "EXPERIMENTAL_ANY_BOUNDARY_V1"
SUPPORTED_RULESETS = {DOCUMENT_NEAREST_V1, EXPERIMENTAL_ANY_BOUNDARY_V1}
RULESET_PROFILES = {
    DOCUMENT_NEAREST_V1: {
        "authority_reference": "technical_appendix_lct.docx#2.2",
        "definition": "nearest full polygon boundary; one terminal straight segment; own OKS setback exception",
        "experimental": False,
    },
    EXPERIMENTAL_ANY_BOUNDARY_V1: {
        "authority_reference": "research profile; not an organizer clarification",
        "definition": "ranked points from shell and holes; one terminal straight segment",
        "experimental": True,
    },
}


def ruleset_hash(ruleset_id):
    profile = RULESET_PROFILES[ruleset_id]
    canonical = (
        f"{ruleset_id}|{profile['authority_reference']}|{profile['definition']}"
        f"|experimental={str(profile['experimental']).lower()}"
    )
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


def chamber_cost_for_diameter(diameter):
    if diameter <= 200:
        return 3_000_000.0
    if diameter <= 500:
        return 5_000_000.0
    if diameter <= 1000:
        return 8_000_000.0
    return 12_000_000.0


A = 6378137.0
F = 1.0 / 298.257223563
K0 = 0.9996
E2 = F * (2.0 - F)
EP2 = E2 / (1.0 - E2)
LON0 = math.radians(39.0)


def meridional_arc(lat):
    return A * (
        (1.0 - E2 / 4.0 - 3.0 * E2 * E2 / 64.0 - 5.0 * E2 ** 3 / 256.0) * lat
        - (3.0 * E2 / 8.0 + 3.0 * E2 * E2 / 32.0 + 45.0 * E2 ** 3 / 1024.0) * math.sin(2.0 * lat)
        + (15.0 * E2 * E2 / 256.0 + 45.0 * E2 ** 3 / 1024.0) * math.sin(4.0 * lat)
        - (35.0 * E2 ** 3 / 3072.0) * math.sin(6.0 * lat)
    )


def to_metric(lon_lat):
    lon, lat_deg = lon_lat[0], lon_lat[1]
    lat = math.radians(lat_deg)
    lon_rad = math.radians(lon)
    sin_lat = math.sin(lat)
    cos_lat = math.cos(lat)
    tan_lat = math.tan(lat)
    n = A / math.sqrt(1.0 - E2 * sin_lat * sin_lat)
    t = tan_lat * tan_lat
    c = EP2 * cos_lat * cos_lat
    a = cos_lat * (lon_rad - LON0)
    m = meridional_arc(lat)
    easting = K0 * n * (
        a
        + (1 - t + c) * a ** 3 / 6.0
        + (5 - 18 * t + t * t + 72 * c - 58 * EP2) * a ** 5 / 120.0
    ) + 500000.0
    northing = K0 * (
        m
        + n * tan_lat * (
            a * a / 2.0
            + (5 - t + 9 * c + 4 * c * c) * a ** 4 / 24.0
            + (61 - 58 * t + t * t + 600 * c - 330 * EP2) * a ** 6 / 720.0
        )
    )
    return easting, northing


def distance(a, b):
    return math.hypot(a[0] - b[0], a[1] - b[1])


def line_length(points):
    return sum(distance(points[i], points[i + 1]) for i in range(len(points) - 1))


def dot(a, b):
    return a[0] * b[0] + a[1] * b[1]


def vector(a, b):
    return b[0] - a[0], b[1] - a[1]


def cross(a, b):
    return a[0] * b[1] - a[1] * b[0]


def segment_intersection(a, b, c, d):
    r = vector(a, b)
    s = vector(c, d)
    denominator = cross(r, s)
    qp = vector(a, c)
    if abs(denominator) < 1e-9:
        return None
    t = cross(qp, s) / denominator
    u = cross(qp, r) / denominator
    if -1e-9 <= t <= 1.0 + 1e-9 and -1e-9 <= u <= 1.0 + 1e-9:
        return a[0] + t * r[0], a[1] + t * r[1]
    return None


def crossing_angle_degrees(a, b, c, d):
    v1 = vector(a, b)
    v2 = vector(c, d)
    len1 = math.hypot(v1[0], v1[1])
    len2 = math.hypot(v2[0], v2[1])
    if len1 < 0.05 or len2 < 0.05:
        return None
    cos_value = max(-1.0, min(1.0, dot(v1, v2) / (len1 * len2)))
    angle = math.degrees(math.acos(abs(cos_value)))
    return angle


def turn_angle_degrees(a, b, c):
    v1 = (b[0] - a[0], b[1] - a[1])
    v2 = (c[0] - b[0], c[1] - b[1])
    len1 = math.hypot(v1[0], v1[1])
    len2 = math.hypot(v2[0], v2[1])
    if len1 < 0.05 or len2 < 0.05:
        return None
    dot = v1[0] * v2[0] + v1[1] * v2[1]
    cos_value = max(-1.0, min(1.0, dot / (len1 * len2)))
    return math.degrees(math.acos(cos_value))


def turn_metrics(points):
    angles = []
    invalid_turns = []
    for index in range(1, len(points) - 1):
        angle = turn_angle_degrees(points[index - 1], points[index], points[index + 1])
        if angle is None:
            continue
        if angle > 1.0:
            angles.append(angle)
        if angle > MAX_TURN_ANGLE_DEGREES + TURN_ANGLE_TOLERANCE_DEGREES:
            invalid_turns.append({"vertex_index": index, "angle_deg": round(angle, 2)})
    return angles, invalid_turns


def point_segment_distance(p, a, b):
    dx = b[0] - a[0]
    dy = b[1] - a[1]
    length2 = dx * dx + dy * dy
    if length2 == 0:
        return distance(p, a)
    t = ((p[0] - a[0]) * dx + (p[1] - a[1]) * dy) / length2
    t = max(0.0, min(1.0, t))
    return distance(p, (a[0] + t * dx, a[1] + t * dy))


def segment_segment_distance(a, b, c, d):
    if segment_intersection(a, b, c, d) is not None:
        return 0.0
    return min(
        point_segment_distance(a, c, d),
        point_segment_distance(b, c, d),
        point_segment_distance(c, a, b),
        point_segment_distance(d, a, b),
    )


def pair_width(diameter):
    effective = diameter if isinstance(diameter, (int, float)) and diameter > 0 else 50
    for catalog_diameter, width in PAIR_WIDTHS.items():
        if effective <= catalog_diameter:
            return width
    return PAIR_WIDTHS[1400]


def required_centerline_clearance(restriction_type, new_diameter, existing_diameter=50):
    effective = new_diameter if isinstance(new_diameter, (int, float)) and new_diameter > 0 else 50
    if restriction_type == "oks":
        base = 5.0 if effective < 500 else 7.0 if effective < 900 else 9.0
    elif restriction_type in ("road", "tram_tracks"):
        base = 1.5
    elif restriction_type in ("gas_pipeline", "power_cable"):
        base = 2.0
    elif restriction_type in (
        "heat_network", "park", "social_area", "prohibited_site", "water", "railway"
    ):
        base = 1.0
    else:
        base = 0.0
    old_half_width = 0.0
    if restriction_type == "gas_pipeline":
        old_half_width = 0.20
    elif restriction_type == "power_cable":
        old_half_width = 0.10
    elif restriction_type == "heat_network":
        old_half_width = pair_width(existing_diameter) / 2.0
    return base + pair_width(effective) / 2.0 + old_half_width


def near_collinear_overlap_length(a, b, c, d, tolerance=0.25):
    """Return projected overlap for nearly coincident primitive segments."""
    ab_x = b[0] - a[0]
    ab_y = b[1] - a[1]
    cd_x = d[0] - c[0]
    cd_y = d[1] - c[1]
    ab_length = math.hypot(ab_x, ab_y)
    cd_length = math.hypot(cd_x, cd_y)
    if ab_length < 1e-6 or cd_length < 1e-6:
        return 0.0

    unit_x = ab_x / ab_length
    unit_y = ab_y / ab_length
    parallel = abs(unit_x * cd_y - unit_y * cd_x) / cd_length
    if parallel > 0.01:
        return 0.0

    perpendicular_c = abs((c[0] - a[0]) * unit_y - (c[1] - a[1]) * unit_x)
    perpendicular_d = abs((d[0] - a[0]) * unit_y - (d[1] - a[1]) * unit_x)
    if max(perpendicular_c, perpendicular_d) > tolerance:
        return 0.0

    projection_c = (c[0] - a[0]) * unit_x + (c[1] - a[1]) * unit_y
    projection_d = (d[0] - a[0]) * unit_x + (d[1] - a[1]) * unit_y
    overlap_start = max(0.0, min(projection_c, projection_d))
    overlap_end = min(ab_length, max(projection_c, projection_d))
    return max(0.0, overlap_end - overlap_start)


def nearest_point_on_segment(p, a, b):
    dx = b[0] - a[0]
    dy = b[1] - a[1]
    length2 = dx * dx + dy * dy
    if length2 == 0:
        return a
    t = ((p[0] - a[0]) * dx + (p[1] - a[1]) * dy) / length2
    t = max(0.0, min(1.0, t))
    return a[0] + t * dx, a[1] + t * dy


def point_line_distance(p, line):
    return min(point_segment_distance(p, line[i], line[i + 1]) for i in range(len(line) - 1))


def metric_geometry(geometry):
    if geometry is None:
        return None
    if geometry["type"] == "Point":
        return to_metric(geometry["coordinates"])
    if geometry["type"] == "LineString":
        return [to_metric(coord) for coord in geometry["coordinates"]]
    return None


def ring_segments(ring):
    points = [to_metric(coord) for coord in ring]
    return [(points[i], points[i + 1]) for i in range(len(points) - 1)]


def geometry_segments(geometry):
    if geometry is None:
        return []
    geometry_type = geometry.get("type")
    coordinates = geometry.get("coordinates")
    if geometry_type == "LineString":
        points = [to_metric(coord) for coord in coordinates]
        return [(points[i], points[i + 1]) for i in range(len(points) - 1)]
    if geometry_type == "Polygon":
        result = []
        for ring in coordinates:
            result.extend(ring_segments(ring))
        return result
    if geometry_type == "MultiPolygon":
        result = []
        for polygon in coordinates:
            for ring in polygon:
                result.extend(ring_segments(ring))
        return result
    return []


def geometry_polygons(geometry):
    if geometry is None:
        return []
    geometry_type = geometry.get("type")
    coordinates = geometry.get("coordinates")
    if geometry_type == "Polygon":
        return [coordinates]
    if geometry_type == "MultiPolygon":
        return [polygon for polygon in coordinates]
    return []


def point_in_ring(point, ring):
    inside = False
    x, y = point
    metric_ring = [to_metric(coord) for coord in ring]
    j = len(metric_ring) - 1
    for i in range(len(metric_ring)):
        xi, yi = metric_ring[i]
        xj, yj = metric_ring[j]
        intersects = (yi > y) != (yj > y)
        if intersects:
            x_at_y = (xj - xi) * (y - yi) / ((yj - yi) or 1e-12) + xi
            if x < x_at_y:
                inside = not inside
        j = i
    return inside


def point_in_polygon(point, polygon):
    if not polygon or not point_in_ring(point, polygon[0]):
        return False
    return not any(point_in_ring(point, hole) for hole in polygon[1:])


def polygon_metric_segments(polygon):
    result = []
    for ring in polygon:
        points = [to_metric(coord) for coord in ring]
        result.extend((points[i], points[i + 1]) for i in range(len(points) - 1))
    return result


def nearest_polygon_boundary_point(point, polygon):
    best = None
    best_distance = math.inf
    for a, b in polygon_metric_segments(polygon):
        candidate = nearest_point_on_segment(point, a, b)
        candidate_distance = distance(point, candidate)
        if candidate_distance < best_distance:
            best = candidate
            best_distance = candidate_distance
    return best, best_distance


def unique_points(points, tolerance=ENDPOINT_APPROACH_TOLERANCE_METERS):
    result = []
    for point in points:
        if not any(distance(point, existing) <= tolerance for existing in result):
            result.append(point)
    return result


def validate_oks_endpoint_approach(line, polygon, from_start, ruleset_id=DOCUMENT_NEAREST_V1):
    ordered = line if from_start else list(reversed(line))
    if len(ordered) < 2:
        return False, None, "route has no final straight segment"
    endpoint, outside = ordered[0], ordered[1]
    if not polygon or not polygon[0]:
        return False, None, "OKS boundary is empty"
    if point_in_polygon(outside, polygon):
        return False, None, "first route vertex is still inside the OKS"
    crossings = unique_points([
        crossing
        for boundary_a, boundary_b in ring_segments(polygon[0])
        for crossing in [segment_intersection(endpoint, outside, boundary_a, boundary_b)]
        if crossing is not None
    ])
    if len(crossings) != 1:
        return False, None, "final segment must cross the exterior OKS boundary exactly once"
    if ruleset_id == DOCUMENT_NEAREST_V1:
        nearest, _ = nearest_polygon_boundary_point(endpoint, polygon)
        if nearest is None:
            return False, None, "OKS boundary is empty"
        if point_segment_distance(nearest, endpoint, outside) > ENDPOINT_APPROACH_TOLERANCE_METERS:
            return False, None, "final segment does not pass through the nearest boundary point"
        if distance(crossings[0], nearest) > ENDPOINT_APPROACH_TOLERANCE_METERS:
            return False, None, "final segment crosses a non-nearest OKS boundary point"

    segment_index = 0 if from_start else len(line) - 2
    return True, segment_index, None


def is_finite_number(value):
    return isinstance(value, (int, float)) and math.isfinite(value)


def find_nonfinite(value, path="$"):
    if isinstance(value, float) and not math.isfinite(value):
        return path
    if isinstance(value, list):
        for index, item in enumerate(value):
            found = find_nonfinite(item, f"{path}[{index}]")
            if found:
                return found
    if isinstance(value, dict):
        for key, item in value.items():
            found = find_nonfinite(item, f"{path}.{key}")
            if found:
                return found
    return None


def feature_id(feature):
    return str((feature.get("properties") or {}).get("id"))


def object_type(feature):
    return (feature.get("properties") or {}).get("object_type")


def sha256(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def select_diameter(flow_tph, length_m):
    first = None
    for row in CATALOG:
        if row[1] >= flow_tph:
            first = row
            break
    if first is None:
        return None
    if length_m <= first[2]:
        return first
    index = CATALOG.index(first)
    if index + 1 < len(CATALOG) and length_m <= CATALOG[index + 1][2]:
        return CATALOG[index + 1]
    return first


def add_violation(violations, code, message, object_ids=None, expected=None, actual=None):
    violations.append({
        "code": code,
        "message": message,
        "object_ids": object_ids or [],
        "expected": expected,
        "actual": actual,
    })


def find_path(graph, start, tie_nodes):
    queue = deque([(start, [])])
    seen = {start}
    while queue:
        node, path = queue.popleft()
        if node in tie_nodes:
            return path
        for edge in graph.get(node, []):
            if edge["end"] not in seen:
                seen.add(edge["end"])
                queue.append((edge["end"], path + [edge]))
    return None


def check(input_path, result_path, run_id, ruleset_id=DOCUMENT_NEAREST_V1):
    if ruleset_id not in SUPPORTED_RULESETS:
        raise ValueError(f"unsupported ruleset_id: {ruleset_id}")
    started = time.perf_counter()
    source = json.loads(Path(input_path).read_text(encoding="utf-8"))
    result = json.loads(Path(result_path).read_text(encoding="utf-8"))
    violations = []
    warnings = []

    source_features = source.get("features", [])
    result_features = result.get("features", [])
    if result.get("type") != "FeatureCollection" or not isinstance(result_features, list):
        add_violation(violations, "FEATURE_COLLECTION_INVALID", "Result must be a GeoJSON FeatureCollection with a features array")

    nonfinite_path = find_nonfinite(result)
    if nonfinite_path:
        add_violation(violations, "NONFINITE_NUMBER", "Result contains NaN or Infinity", [nonfinite_path])

    source_by_type = defaultdict(list)
    source_by_id = {}
    for feature in source_features:
        source_by_type[object_type(feature)].append(feature)
        source_by_id[feature_id(feature)] = feature

    result_by_type = defaultdict(list)
    result_by_id = {}
    seen_ids = defaultdict(list)
    variant_ids = set()
    for feature in result_features:
        if feature.get("type") != "Feature":
            add_violation(violations, "FEATURE_TYPE_INVALID", "Every result item must be a GeoJSON Feature", [feature_id(feature)])
        props = feature.get("properties") or {}
        feature_type = props.get("object_type")
        feature_key = feature_id(feature)
        result_by_type[feature_type].append(feature)
        seen_ids[feature_key].append(feature_type)
        if props.get("variant_id") is not None:
            variant_ids.add(str(props.get("variant_id")))
        required = OUTPUT_REQUIRED_FIELDS.get(feature_type)
        if required is None:
            add_violation(violations, "OUTPUT_OBJECT_TYPE_UNKNOWN", "Result feature has unsupported object_type", [feature_key], sorted(OUTPUT_REQUIRED_FIELDS), feature_type)
        else:
            missing = sorted(field for field in required if field not in props)
            if missing:
                add_violation(violations, "OUTPUT_FIELD_MISSING", "Result feature is missing required fields", [feature_key], missing, None)
        if feature_type != "variant_summary":
            result_by_id[feature_key] = feature
    for result_id, types in seen_ids.items():
        if result_id == "None":
            add_violation(violations, "OUTPUT_ID_MISSING", "Result feature has no properties.id")
        elif len(types) > 1:
            add_violation(violations, "DUPLICATE_ID", "Result contains duplicate feature id", [result_id], "unique id", types)
    if len(variant_ids) > 1:
        add_violation(violations, "VARIANT_ID_MISMATCH", "Result contains more than one variant_id", sorted(variant_ids))

    demands = {}
    node_coords = {}
    for feature in source_by_type["oks_connection_point"]:
        oks_id = feature_id(feature)
        props = feature.get("properties") or {}
        flow = props.get("flow_tph")
        if not isinstance(flow, (int, float)) or flow <= 0:
            add_violation(violations, "DEMAND_FLOW_INVALID", "Connection point has missing or non-positive flow_tph", [oks_id], "> 0", flow)
            continue
        point = metric_geometry(feature.get("geometry"))
        demands[oks_id] = {"id": oks_id, "flow_tph": float(flow), "point": point}
        node_coords[oks_id] = point

    for feature in source_by_type["heat_chamber"]:
        node_coords[feature_id(feature)] = metric_geometry(feature.get("geometry"))

    for feature in result_by_type["heat_chamber"] + result_by_type["technical_node"]:
        node_coords[feature_id(feature)] = metric_geometry(feature.get("geometry"))

    existing_network = {feature_id(feature): metric_geometry(feature.get("geometry")) for feature in source_by_type["heat_network"]}
    existing_chambers = {feature_id(feature): metric_geometry(feature.get("geometry")) for feature in source_by_type["heat_chamber"]}
    special_crossing_restrictions = []
    forbidden_restrictions = []
    clearance_restrictions = []
    for feature in source_by_type["restriction"]:
        props = feature.get("properties") or {}
        restriction_type = props.get("restriction_type")
        entry = {
            "id": feature_id(feature),
            "restriction_type": restriction_type,
            "segments": geometry_segments(feature.get("geometry")),
            "polygons": geometry_polygons(feature.get("geometry")),
            "existing_diameter": props.get("diameter", 50),
        }
        clearance_restrictions.append(entry)
        if restriction_type in ("road", "tram_tracks", "gas_pipeline", "power_cable", "heat_network"):
            special_crossing_restrictions.append(entry)
        if restriction_type in FORBIDDEN_RESTRICTION_TYPES:
            forbidden_restrictions.append(entry)
    for feature in source_by_type["heat_network"]:
        props = feature.get("properties") or {}
        clearance_restrictions.append({
            "id": feature_id(feature),
            "restriction_type": "heat_network",
            "segments": geometry_segments(feature.get("geometry")),
            "polygons": [],
            "existing_diameter": props.get("diameter", 50),
            "existing_network": True,
        })

    graph = defaultdict(list)
    segments = []
    incoming_edges_by_node = defaultdict(list)
    outgoing_edges_by_node = defaultdict(list)
    incoming_by_node = defaultdict(int)
    outgoing_by_node = defaultdict(int)
    physical_turn_count = 0
    invalid_turn_count = 0
    max_turn_angle_degrees = 0.0
    special_crossing_count = 0
    special_crossing_angle_violation_count = 0
    min_special_crossing_angle_degrees = None
    forbidden_crossing_count = 0
    horizontal_clearance_violation_count = 0
    minimum_clearance_margin_m = None
    parallel_overlap_count = 0
    parallel_overlap_length_m = 0.0
    max_node_degree = 0
    for feature in result_by_type["heat_network"]:
        props = feature.get("properties") or {}
        segment_id = feature_id(feature)
        start = props.get("start_node_id")
        end = props.get("end_node_id")
        line = metric_geometry(feature.get("geometry"))
        if not start or not end or line is None or len(line) < 2:
            add_violation(violations, "SEGMENT_TOPOLOGY_INVALID", "Heat network segment lacks start/end node or valid line geometry", [segment_id])
            continue
        edge = {"id": segment_id, "start": start, "end": end, "props": props, "line": line}
        graph[start].append(edge)
        segments.append(edge)
        outgoing_edges_by_node[start].append(edge)
        incoming_edges_by_node[end].append(edge)
        outgoing_by_node[start] += 1
        incoming_by_node[end] += 1

        angles, invalid_turns = turn_metrics(line)
        physical_turn_count += len(angles)
        if angles:
            max_turn_angle_degrees = max(max_turn_angle_degrees, max(angles))
        invalid_turn_count += len(invalid_turns)
        for invalid_turn in invalid_turns:
            add_violation(
                violations,
                "SEGMENT_TURN_ANGLE_EXCEEDED",
                "Segment changes direction by more than 90 degrees",
                [segment_id],
                "<= 90 deg",
                invalid_turn,
            )

        checked_crossings = set()
        checked_forbidden = set()
        allowed_oks_approach_segments = defaultdict(set)
        for restriction in forbidden_restrictions:
            if restriction["restriction_type"] != "oks":
                continue
            endpoint_sides = []
            if str(start) in demands:
                endpoint_sides.append((True, line[0], str(start)))
            if str(end) in demands:
                endpoint_sides.append((False, line[-1], str(end)))
            for from_start, endpoint, node_id in endpoint_sides:
                containing_polygons = [
                    polygon for polygon in restriction["polygons"]
                    if point_in_polygon(endpoint, polygon)
                ]
                if not containing_polygons:
                    continue
                if len(containing_polygons) != 1:
                    add_violation(
                        violations,
                        "OKS_ENDPOINT_APPROACH_AMBIGUOUS",
                        "Connection point is contained by more than one polygon component",
                        [segment_id, restriction["id"], node_id],
                        1,
                        len(containing_polygons),
                    )
                    continue
                valid_approach, segment_index, reason = validate_oks_endpoint_approach(
                    line, containing_polygons[0], from_start, ruleset_id
                )
                if valid_approach:
                    allowed_oks_approach_segments[restriction["id"]].add(segment_index)
                else:
                    add_violation(
                        violations,
                        "OKS_ENDPOINT_APPROACH_INVALID",
                        "Final OKS segment violates the selected endpoint-entry ruleset",
                        [segment_id, restriction["id"], node_id],
                        ruleset_id,
                        reason,
                    )
        for i in range(len(line) - 1):
            route_a = line[i]
            route_b = line[i + 1]
            segment_midpoint = ((route_a[0] + route_b[0]) / 2.0, (route_a[1] + route_b[1]) / 2.0)
            for restriction in forbidden_restrictions:
                if i in allowed_oks_approach_segments.get(restriction["id"], set()):
                    continue
                forbidden_hit = False
                for j, (limit_a, limit_b) in enumerate(restriction["segments"]):
                    if segment_intersection(route_a, route_b, limit_a, limit_b) is not None:
                        checked_key = (segment_id, restriction["id"], i, j)
                        if checked_key not in checked_forbidden:
                            checked_forbidden.add(checked_key)
                            forbidden_hit = True
                            break
                if not forbidden_hit:
                    forbidden_hit = any(point_in_polygon(segment_midpoint, polygon) for polygon in restriction["polygons"])
                if forbidden_hit:
                    forbidden_crossing_count += 1
                    add_violation(
                        violations,
                        "FORBIDDEN_RESTRICTION_CROSSED",
                        "New heat-network segment crosses a forbidden restriction",
                        [segment_id, restriction["id"]],
                        "no crossing",
                        restriction["restriction_type"],
                    )
            for restriction in special_crossing_restrictions:
                for j, (limit_a, limit_b) in enumerate(restriction["segments"]):
                    intersection = segment_intersection(route_a, route_b, limit_a, limit_b)
                    if intersection is None:
                        continue
                    crossing_key = (segment_id, restriction["id"], i, j)
                    if crossing_key in checked_crossings:
                        continue
                    checked_crossings.add(crossing_key)
                    angle = crossing_angle_degrees(route_a, route_b, limit_a, limit_b)
                    if angle is None:
                        continue
                    special_crossing_count += 1
                    min_special_crossing_angle_degrees = (
                        angle if min_special_crossing_angle_degrees is None
                        else min(min_special_crossing_angle_degrees, angle)
                    )
                    if angle + 1e-6 < 45.0:
                        special_crossing_angle_violation_count += 1
                        add_violation(
                            violations,
                            "SPECIAL_CROSSING_ANGLE_TOO_SMALL",
                            "Road or tram crossing angle is smaller than the 45-degree requirement",
                            [segment_id, restriction["id"]],
                            ">= 45 deg",
                            {
                                "restriction_type": restriction["restriction_type"],
                                "angle_deg": round(angle, 2),
                                "route_segment_index": i,
                                "restriction_segment_index": j,
                            },
                        )

            diameter = props.get("diameter")
            for restriction in clearance_restrictions:
                restriction_type = restriction["restriction_type"]
                if restriction_type not in {
                    "oks", "park", "social_area", "prohibited_site", "water", "railway",
                    "road", "tram_tracks", "gas_pipeline", "power_cable", "heat_network",
                }:
                    continue
                if i in allowed_oks_approach_segments.get(restriction["id"], set()):
                    continue
                intersects_boundary = any(
                    segment_intersection(route_a, route_b, limit_a, limit_b) is not None
                    for limit_a, limit_b in restriction["segments"]
                )
                if restriction_type in {
                    "road", "tram_tracks", "gas_pipeline", "power_cable", "heat_network"
                } and intersects_boundary:
                    # The intersecting primitive is the locally classified special passage.
                    continue
                if restriction.get("existing_network") and i == len(line) - 2:
                    endpoint_gap = min(
                        (point_segment_distance(route_b, a, b) for a, b in restriction["segments"]),
                        default=math.inf,
                    )
                    if endpoint_gap <= ENDPOINT_APPROACH_TOLERANCE_METERS:
                        continue
                actual_clearance = min(
                    (segment_segment_distance(route_a, route_b, a, b)
                     for a, b in restriction["segments"]),
                    default=math.inf,
                )
                required_clearance = required_centerline_clearance(
                    restriction_type, diameter, restriction.get("existing_diameter", 50)
                )
                margin = actual_clearance - required_clearance
                if math.isfinite(margin):
                    minimum_clearance_margin_m = (
                        margin if minimum_clearance_margin_m is None
                        else min(minimum_clearance_margin_m, margin)
                    )
                if actual_clearance + 0.01 < required_clearance:
                    horizontal_clearance_violation_count += 1
                    add_violation(
                        violations,
                        "HORIZONTAL_CLEARANCE_TOO_SMALL",
                        "New heat-network centerline violates the diameter-dependent horizontal clearance",
                        [segment_id, restriction["id"]],
                        {
                            "restriction_type": restriction_type,
                            "minimum_centerline_clearance_m": round(required_clearance, 3),
                            "rules_version": CLEARANCE_RULES_VERSION,
                        },
                        {
                            "centerline_clearance_m": round(actual_clearance, 3),
                            "route_segment_index": i,
                            "diameter": diameter,
                        },
                    )

        for node_id, endpoint in ((start, line[0]), (end, line[-1])):
            expected = node_coords.get(node_id)
            if expected is None:
                add_violation(violations, "SEGMENT_NODE_MISSING", "Segment references unknown node", [segment_id, str(node_id)])
                continue
            gap = distance(endpoint, expected)
            if gap > 0.25:
                add_violation(violations, "SEGMENT_ENDPOINT_MISMATCH", "Segment endpoint does not match declared node", [segment_id, str(node_id)], "<= 0.25 m", round(gap, 3))

        declared_length = props.get("length")
        actual_length = line_length(line)
        if isinstance(declared_length, (int, float)):
            tolerance = max(0.5, declared_length * 0.01)
            if abs(actual_length - declared_length) > tolerance:
                add_violation(violations, "SEGMENT_LENGTH_MISMATCH", "Declared segment length differs from geometry length", [segment_id], round(actual_length, 2), declared_length)

    for left_index in range(len(segments)):
        left = segments[left_index]
        for right in segments[left_index + 1:]:
            max_overlap = 0.0
            for left_start, left_end in zip(left["line"], left["line"][1:]):
                for right_start, right_end in zip(right["line"], right["line"][1:]):
                    max_overlap = max(
                        max_overlap,
                        near_collinear_overlap_length(
                            left_start, left_end, right_start, right_end
                        ),
                    )
            if max_overlap <= 2.0:
                continue
            parallel_overlap_count += 1
            parallel_overlap_length_m += max_overlap
            add_violation(
                violations,
                "PARALLEL_NETWORK_OVERLAP",
                "Separate heat-network segments occupy the same corridor without shared topology",
                [left["id"], right["id"]],
                "<= 0.25 m proximity for at most 2 m, or one shared topological segment",
                {"overlap_length_m": round(max_overlap, 3)},
            )

    for node_id in set(incoming_edges_by_node) & set(outgoing_edges_by_node):
        if str(node_id) in demands:
            add_violation(
                violations,
                "OKS_USED_AS_TRANSIT_NODE",
                "An OKS connection point must terminate its branch and cannot carry another route onward",
                [str(node_id)],
                "no incoming segment at an OKS start node",
                {
                    "incoming_segment_ids": [edge["id"] for edge in incoming_edges_by_node[node_id]],
                    "outgoing_segment_ids": [edge["id"] for edge in outgoing_edges_by_node[node_id]],
                },
            )
        for incoming_edge in incoming_edges_by_node[node_id]:
            for outgoing_edge in outgoing_edges_by_node[node_id]:
                incoming_line = incoming_edge["line"]
                outgoing_line = outgoing_edge["line"]
                angle = turn_angle_degrees(
                    incoming_line[-2], incoming_line[-1], outgoing_line[1]
                )
                if angle is None or angle <= 1.0:
                    continue
                physical_turn_count += 1
                max_turn_angle_degrees = max(max_turn_angle_degrees, angle)
                if angle <= MAX_TURN_ANGLE_DEGREES + TURN_ANGLE_TOLERANCE_DEGREES:
                    continue
                invalid_turn_count += 1
                add_violation(
                    violations,
                    "JUNCTION_TURN_ANGLE_EXCEEDED",
                    "Connected heat-network segments change direction by more than 90 degrees",
                    [incoming_edge["id"], outgoing_edge["id"], str(node_id)],
                    "<= 90 deg",
                    {"node_id": str(node_id), "angle_deg": round(angle, 2)},
                )

    chamber_entries = [
        {
            "id": feature_id(feature),
            "point": metric_geometry(feature.get("geometry")),
            "props": feature.get("properties") or {},
            "existing": False,
        }
        for feature in result_by_type["heat_chamber"]
        if feature.get("geometry")
    ]
    chamber_entries.extend(
        {
            "id": feature_id(feature),
            "point": metric_geometry(feature.get("geometry")),
            "props": feature.get("properties") or {},
            "existing": True,
        }
        for feature in source_by_type["heat_chamber"]
        if feature.get("geometry")
    )
    required_branch_chambers = 0
    missing_branch_chambers = 0
    for node_id, incoming_count in incoming_by_node.items():
        max_node_degree = max(max_node_degree, incoming_count + outgoing_by_node.get(node_id, 0))
        outgoing_count = outgoing_by_node.get(node_id, 0)
        physical_degree = incoming_count + outgoing_count
        is_consumer_branch = node_id in demands and incoming_count > 0 and outgoing_count > 0
        is_network_branch = physical_degree > 2
        if not (is_consumer_branch or is_network_branch):
            continue
        required_branch_chambers += 1
        node_point = node_coords.get(node_id)
        if node_point is None:
            continue
        exact_chambers = [
            chamber for chamber in chamber_entries
            if chamber["id"] == str(node_id)
            and chamber["point"] is not None
            and distance(node_point, chamber["point"]) <= 0.75
        ]
        nearby = exact_chambers or sorted(
            (
                chamber for chamber in chamber_entries
                if not chamber["existing"]
                and chamber["point"] is not None
                and distance(node_point, chamber["point"]) <= 0.75
            ),
            key=lambda chamber: (distance(node_point, chamber["point"]), chamber["id"] or ""),
        )
        if not nearby:
            missing_branch_chambers += 1
            add_violation(
                violations,
                "BRANCH_CHAMBER_MISSING",
                "New-network branching point has no exported heat_chamber",
                [node_id],
                "heat_chamber within 0.75 m",
                None,
            )
        else:
            incident = [
                edge for edge in segments
                if edge["start"] == node_id or edge["end"] == node_id
            ]
            required_diameter = max(
                (int(edge["props"].get("diameter") or 0) for edge in incident),
                default=0,
            )
            chamber = nearby[0]
            actual_diameter = chamber["props"].get("diameter")
            if (not chamber["existing"]
                    and (not isinstance(actual_diameter, (int, float))
                         or actual_diameter < required_diameter)):
                add_violation(
                    violations,
                    "CHAMBER_DIAMETER_TOO_SMALL",
                    "Branch chamber diameter is smaller than an incident heat-network segment",
                    [chamber["id"], node_id],
                    required_diameter,
                    actual_diameter,
                )
            actual_cost = chamber["props"].get("cost")
            if not chamber["existing"] and isinstance(actual_diameter, (int, float)):
                expected_cost = chamber_cost_for_diameter(actual_diameter)
                if not isinstance(actual_cost, (int, float)) or abs(actual_cost - expected_cost) > 1.0:
                    add_violation(
                        violations,
                        "CHAMBER_COST_MISMATCH",
                        "Branch chamber cost does not match its declared diameter",
                        [chamber["id"]],
                        expected_cost,
                        actual_cost,
                    )
        if physical_degree > 4:
            add_violation(
                violations,
                "CHAMBER_DEGREE_EXCEEDED",
                "Branching chamber has more than four incident heat-network segments",
                [node_id],
                "<= 4",
                physical_degree,
            )

    chamber_nodes = {feature_id(feature) for feature in source_by_type["heat_chamber"]}
    chamber_nodes.update(feature_id(feature) for feature in result_by_type["heat_chamber"])

    existing_chamber_tie_in_count = 0
    for edge in segments:
        if str(edge["end"]) in existing_chambers:
            existing_chamber_tie_in_count += 1

    for feature in result_by_type["heat_chamber"]:
        chamber_id = feature_id(feature)
        chamber_point = metric_geometry(feature.get("geometry"))
        if chamber_point is None:
            continue
        on_existing_network = any(point_line_distance(chamber_point, network) <= 0.75 for network in existing_network.values())
        is_terminal_connection_chamber = incoming_by_node.get(chamber_id, 0) > 0 and outgoing_by_node.get(chamber_id, 0) == 0
        if is_terminal_connection_chamber and not on_existing_network:
            add_violation(
                violations,
                "NEW_CHAMBER_NOT_ON_NETWORK",
                "Connection heat_chamber is not located on an existing heat_network",
                [chamber_id],
                "<= 0.75 m to existing heat_network",
                None,
            )

    summary_features = result_by_type["variant_summary"]
    summary = (summary_features[0].get("properties") or {}) if summary_features else {}
    declared_unconnected = set()
    raw_unconnected = summary.get("unconnected_oks_ids", [])
    if isinstance(raw_unconnected, list):
        declared_unconnected = {str(item) for item in raw_unconnected}
    elif raw_unconnected not in (None, ""):
        add_violation(violations, "SUMMARY_UNCONNECTED_IDS_INVALID", "unconnected_oks_ids must be an array", ["variant_summary"], "array", raw_unconnected)

    per_oks = []
    segment_flow_expected = defaultdict(float)
    connected_flow = 0.0
    for oks_id, demand in sorted(demands.items(), key=lambda item: int(item[0]) if item[0].isdigit() else item[0]):
        start = oks_id
        path = find_path(graph, start, chamber_nodes)
        if path is None:
            status_value = "UNCONNECTED" if oks_id in declared_unconnected else "INVALID"
            per_oks.append({"oks_id": oks_id, "status": status_value, "flow_tph": demand["flow_tph"], "path_segment_ids": [], "tie_in_id": None})
            if oks_id not in declared_unconnected:
                add_violation(violations, "OKS_PATH_MISSING", "No path from connection point to any heat_chamber", [oks_id])
            continue
        if oks_id in declared_unconnected:
            add_violation(violations, "UNCONNECTED_OKS_HAS_PATH", "OKS is declared unconnected but has an exported path", [oks_id])
        connected_flow += demand["flow_tph"]
        for edge in path:
            segment_flow_expected[edge["id"]] += demand["flow_tph"]
        per_oks.append({
            "oks_id": oks_id,
            "status": "VALID",
            "flow_tph": round(demand["flow_tph"], 4),
            "path_segment_ids": [edge["id"] for edge in path],
            "tie_in_id": path[-1]["end"],
        })

    construction_cost = 0.0
    for edge in segments:
        props = edge["props"]
        segment_id = edge["id"]
        expected_flow = segment_flow_expected.get(segment_id, 0.0)
        actual_flow = props.get("flow_tph")
        if not isinstance(actual_flow, (int, float)) or abs(actual_flow - expected_flow) > 0.1:
            add_violation(violations, "SEGMENT_FLOW_MISMATCH", "Segment flow does not match sum of dependent OKS flows", [segment_id], round(expected_flow, 2), actual_flow)

        length = props.get("length")
        if not isinstance(length, (int, float)):
            length = line_length(edge["line"])
        selected = select_diameter(expected_flow, length)
        actual_diameter = props.get("diameter")
        if selected is None:
            add_violation(violations, "SEGMENT_FLOW_TOO_HIGH", "No catalog diameter can carry segment flow", [segment_id], None, expected_flow)
        elif not isinstance(actual_diameter, (int, float)) or actual_diameter < selected[0]:
            add_violation(violations, "SEGMENT_DIAMETER_TOO_SMALL", "Segment diameter is smaller than catalog requirement", [segment_id], selected[0], actual_diameter)

        cost = props.get("cost")
        if isinstance(cost, (int, float)):
            construction_cost += float(cost)
            if selected is not None and props.get("laying_method") == "base":
                expected_cost = length * selected[3]
                if abs(cost - expected_cost) > max(1.0, expected_cost * 0.01):
                    add_violation(violations, "SEGMENT_COST_MISMATCH", "Base segment cost differs from independent catalog calculation", [segment_id], round(expected_cost, 2), cost)
        else:
            add_violation(violations, "SEGMENT_COST_MISSING", "Segment has no numeric cost", [segment_id])

    chamber_cost = sum(float((feature.get("properties") or {}).get("cost") or 0.0) for feature in result_by_type["heat_chamber"])
    if not summary_features:
        add_violation(violations, "SUMMARY_MISSING", "Result has no variant_summary feature")
    elif len(summary_features) > 1:
        add_violation(violations, "SUMMARY_CARDINALITY_ERROR", "Result must contain exactly one variant_summary feature", ["variant_summary"], 1, len(summary_features))

    for oks_id in declared_unconnected:
        if oks_id not in demands:
            add_violation(violations, "UNCONNECTED_OKS_UNKNOWN", "unconnected_oks_ids references unknown connection point", [oks_id])
    unconnected_penalty = sum(
        100_000_000.0 + 500_000.0 * demand["flow_tph"]
        for oks_id, demand in demands.items()
        if oks_id in declared_unconnected
    )

    existing_chamber_tie_in_cost = existing_chamber_tie_in_count * 5_000_000.0
    full_construction_cost = construction_cost + chamber_cost + existing_chamber_tie_in_cost
    recomputed = {
        "construction_cost": round(full_construction_cost, 2),
        "chamber_construction_cost": round(chamber_cost, 2),
        "existing_chamber_tie_in_count": existing_chamber_tie_in_count,
        "existing_chamber_tie_in_cost": round(existing_chamber_tie_in_cost, 2),
        "unconnected_penalty": round(unconnected_penalty, 2),
    }
    recomputed["calculated_cost"] = round(full_construction_cost + unconnected_penalty, 2)
    recomputed["new_network_length"] = round(sum(float((edge["props"].get("length") or line_length(edge["line"]))) for edge in segments), 2)
    recomputed["score"] = round(0.7 * (recomputed["calculated_cost"] / 25_000_000.0) + 0.3 * (recomputed["new_network_length"] / 100.0), 2)

    for key in ("construction_cost", "chamber_construction_cost", "existing_chamber_tie_in_count",
                "existing_chamber_tie_in_cost", "calculated_cost", "new_network_length", "score"):
        actual = summary.get(key)
        expected = recomputed[key]
        if isinstance(actual, (int, float)) and abs(actual - expected) > max(0.1, abs(expected) * 0.001):
            add_violation(violations, "SUMMARY_MISMATCH", f"Summary field {key} differs from independent recomputation", [str(summary.get("id", "summary"))], expected, actual)

    status = "VALID" if not violations else "INVALID"
    elapsed = time.perf_counter() - started
    return {
        "run_id": run_id,
        "case_id": Path(input_path).stem,
        "input_sha256": sha256(input_path),
        "result_sha256": sha256(result_path),
        "checker_version": "benchmark_checker_v0.3-full-horizontal-clearance",
        "clearance_rules_version": CLEARANCE_RULES_VERSION,
        "ruleset_version": ruleset_id,
        "ruleset_hash": ruleset_hash(ruleset_id),
        "ruleset_experimental": RULESET_PROFILES[ruleset_id]["experimental"],
        "status": status,
        "contract_valid": status == "VALID",
        "case_expectations_met": status == "VALID" and len(per_oks) == len(demands),
        "per_oks": per_oks,
        "violations": violations,
        "warnings": warnings,
        "recomputed_cost_components": recomputed,
        "geometry_metrics": {
            "new_segment_count": len(segments),
            "tie_in_count": 0,
            "existing_chamber_tie_in_count": existing_chamber_tie_in_count,
            "new_chamber_count": len(result_by_type["heat_chamber"]),
            "connected_oks_count": sum(1 for item in per_oks if item["status"] == "VALID"),
            "total_oks_count": len(demands),
            "connected_flow_tph": round(connected_flow, 4),
            "physical_turn_count": physical_turn_count,
            "invalid_turn_count": invalid_turn_count,
            "max_turn_angle_degrees": round(max_turn_angle_degrees, 3),
            "special_crossing_count": special_crossing_count,
            "special_crossing_angle_violation_count": special_crossing_angle_violation_count,
            "min_special_crossing_angle_degrees": (
                None if min_special_crossing_angle_degrees is None
                else round(min_special_crossing_angle_degrees, 3)
            ),
            "forbidden_crossing_count": forbidden_crossing_count,
            "horizontal_clearance_violation_count": horizontal_clearance_violation_count,
            "minimum_clearance_margin_m": (
                None if minimum_clearance_margin_m is None
                else round(minimum_clearance_margin_m, 3)
            ),
            "parallel_overlap_count": parallel_overlap_count,
            "parallel_overlap_length_m": round(parallel_overlap_length_m, 3),
            "required_branch_chamber_count": required_branch_chambers,
            "missing_branch_chamber_count": missing_branch_chambers,
            "max_node_degree": max_node_degree,
        },
        "runtime_metrics": {
            "checker_elapsed_seconds": round(elapsed, 4),
            "python_version": sys.version.split()[0],
            "machine_description": platform.platform(),
        },
    }


def main():
    parser = argparse.ArgumentParser(description="Independent benchmark checker for heat-network routing GeoJSON results.")
    parser.add_argument("--input", required=True, help="Source case GeoJSON")
    parser.add_argument("--result", required=True, help="Solver result GeoJSON")
    parser.add_argument("--out", required=True, help="Benchmark report JSON path")
    parser.add_argument("--run-id", default=None, help="Stable run identifier")
    parser.add_argument(
        "--ruleset",
        choices=sorted(SUPPORTED_RULESETS),
        default=DOCUMENT_NEAREST_V1,
        help="Geometry ruleset used to validate the result.",
    )
    args = parser.parse_args()

    run_id = args.run_id or f"run_{int(time.time())}"
    report = check(args.input, args.result, run_id, args.ruleset)
    out_path = Path(args.out)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"{report['status']} {report['geometry_metrics']['connected_oks_count']}/{report['geometry_metrics']['total_oks_count']} oks")
    print(out_path)
    if report["violations"]:
        print(f"violations: {len(report['violations'])}")
        for violation in report["violations"][:10]:
            print(f"- {violation['code']}: {violation['message']} {violation['object_ids']}")
        sys.exit(2)


if __name__ == "__main__":
    main()
