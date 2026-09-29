#!/usr/bin/env python3
"""Audit the complete local dataset tree and its distributable ZIP archive."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import xml.etree.ElementTree as ET
import zipfile
from collections import Counter
from pathlib import Path, PurePosixPath, PureWindowsPath


ROOT = Path(__file__).resolve().parents[1]


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def canonical_json_hash(value) -> str:
    payload = json.dumps(value, ensure_ascii=True, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(payload.encode("utf-8")).hexdigest()


def strict_json(path: Path):
    def reject_constant(value: str):
        raise ValueError(f"non-finite JSON number: {value}")

    return json.loads(path.read_text(encoding="utf-8"), parse_constant=reject_constant)


def coordinate_pairs(value, location: str, errors: list[str]):
    if not isinstance(value, list) or not value:
        errors.append(f"{location}: empty or invalid coordinates")
        return
    if isinstance(value[0], (int, float)):
        if len(value) < 2 or not all(isinstance(item, (int, float)) for item in value[:2]):
            errors.append(f"{location}: coordinate must contain numeric x/y")
            return
        x, y = value[:2]
        if not math.isfinite(x) or not math.isfinite(y):
            errors.append(f"{location}: non-finite coordinate")
        elif not (-180 <= x <= 180 and -90 <= y <= 90):
            errors.append(f"{location}: coordinate outside EPSG:4326 bounds: {x}, {y}")
        return
    for index, child in enumerate(value):
        coordinate_pairs(child, f"{location}/{index}", errors)


def audit_geometry(geometry, location: str, errors: list[str]):
    if geometry is None:
        return
    if not isinstance(geometry, dict):
        errors.append(f"{location}: geometry is not an object")
        return
    geometry_type = geometry.get("type")
    if geometry_type == "GeometryCollection":
        geometries = geometry.get("geometries")
        if not isinstance(geometries, list):
            errors.append(f"{location}: GeometryCollection.geometries is not an array")
            return
        for index, child in enumerate(geometries):
            audit_geometry(child, f"{location}/geometries/{index}", errors)
        return
    if geometry_type not in {
        "Point", "MultiPoint", "LineString", "MultiLineString", "Polygon", "MultiPolygon"
    }:
        errors.append(f"{location}: unsupported geometry type {geometry_type!r}")
        return
    coordinate_pairs(geometry.get("coordinates"), f"{location}/coordinates", errors)


def audit_geojson(path: Path, data, errors: list[str], warnings: list[str]):
    relative = path.relative_to(ROOT).as_posix()
    if not isinstance(data, dict) or data.get("type") != "FeatureCollection":
        errors.append(f"{relative}: expected GeoJSON FeatureCollection")
        return
    features = data.get("features")
    if not isinstance(features, list):
        errors.append(f"{relative}: features is not an array")
        return
    ids = []
    for index, feature in enumerate(features):
        location = f"{relative}/features/{index}"
        if not isinstance(feature, dict) or feature.get("type") != "Feature":
            errors.append(f"{location}: expected Feature")
            continue
        properties = feature.get("properties")
        if not isinstance(properties, dict):
            errors.append(f"{location}: properties is not an object")
        else:
            feature_id = properties.get("id", feature.get("id"))
            if feature_id is not None:
                ids.append(str(feature_id))
        audit_geometry(feature.get("geometry"), f"{location}/geometry", errors)
    duplicates = sorted(item for item, count in Counter(ids).items() if count > 1)
    if duplicates:
        errors.append(f"{relative}: duplicate feature ids: {duplicates[:10]}")
    if not features:
        warnings.append(f"{relative}: empty FeatureCollection")


def resolve_repo_path(value: str) -> Path:
    return (ROOT / Path(*PurePosixPath(value).parts)).resolve()


def audit_manifest(path: Path, data, errors: list[str], counters: Counter):
    if not isinstance(data, dict):
        return
    records = data.get("records")
    if not isinstance(records, list):
        return
    counters["manifest_records"] += len(records)
    scene_ids = [str(record.get("scene_id")) for record in records if isinstance(record, dict)]
    duplicate_scene_ids = sorted(item for item, count in Counter(scene_ids).items() if count > 1)
    if duplicate_scene_ids:
        errors.append(f"{path.relative_to(ROOT)}: duplicate scene ids: {duplicate_scene_ids[:10]}")
    for index, record in enumerate(records):
        if not isinstance(record, dict):
            errors.append(f"{path.relative_to(ROOT)}: records/{index} is not an object")
            continue
        for field in ("input", "source"):
            value = record.get(field)
            if not value:
                continue
            target = resolve_repo_path(str(value))
            if not target.is_file():
                errors.append(f"{path.relative_to(ROOT)}: records/{index}/{field} missing: {value}")
                continue
            expected = record.get(f"{field}_hash")
            if expected and sha256(target) != str(expected).lower():
                errors.append(f"{path.relative_to(ROOT)}: records/{index}/{field}_hash mismatch")
            counters["manifest_references"] += 1
    source_manifest = data.get("source_manifest")
    if source_manifest:
        target = resolve_repo_path(str(source_manifest))
        if not target.is_file():
            errors.append(f"{path.relative_to(ROOT)}: source_manifest missing: {source_manifest}")
        elif data.get("source_manifest_hash"):
            source_data = strict_json(target)
            if canonical_json_hash(source_data) != data["source_manifest_hash"].lower():
                errors.append(f"{path.relative_to(ROOT)}: source_manifest_hash mismatch")
            source_records = {
                str(record.get("scene_id")): record
                for record in source_data.get("records", []) if isinstance(record, dict)
            }
            for index, record in enumerate(records):
                if not isinstance(record, dict):
                    continue
                scene_id = str(record.get("scene_id"))
                source_record = source_records.get(scene_id)
                if source_record is None:
                    errors.append(f"{path.relative_to(ROOT)}: records/{index} absent from source manifest")
                    continue
                value = str(record.get("input") or "")
                if PureWindowsPath(value).is_absolute() or PurePosixPath(value).is_absolute():
                    errors.append(f"{path.relative_to(ROOT)}: records/{index}/input must be relative")
                for field in ("parent_scene_id", "split", "seed", "city", "research_bucket"):
                    if record.get(field) != source_record.get(field):
                        errors.append(
                            f"{path.relative_to(ROOT)}: records/{index}/{field} differs from source manifest"
                        )


def audit_xml(path: Path):
    for _, element in ET.iterparse(path, events=("end",)):
        element.clear()


def audit_zip(data_root: Path, archive: Path, errors: list[str], counters: Counter):
    if not archive.is_file():
        errors.append(f"ZIP missing: {archive}")
        return
    source_files = {
        PurePosixPath("data", *path.relative_to(data_root).parts).as_posix(): path
        for path in data_root.rglob("*") if path.is_file()
    }
    with zipfile.ZipFile(archive) as bundle:
        bad = bundle.testzip()
        if bad:
            errors.append(f"ZIP CRC failure: {bad}")
        entries = {entry.filename.replace("\\", "/"): entry for entry in bundle.infolist() if not entry.is_dir()}
        missing = sorted(set(source_files) - set(entries))
        extra = sorted(set(entries) - set(source_files))
        if missing:
            errors.append(f"ZIP missing {len(missing)} files; first: {missing[:5]}")
        if extra:
            errors.append(f"ZIP contains {len(extra)} extra files; first: {extra[:5]}")
        for name in sorted(set(source_files) & set(entries)):
            digest = hashlib.sha256(bundle.read(entries[name])).hexdigest()
            if digest != sha256(source_files[name]):
                errors.append(f"ZIP content mismatch: {name}")
        counters["zip_files"] = len(entries)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--data-root", type=Path, default=ROOT / "data")
    parser.add_argument(
        "--archive", type=Path,
        default=Path.home() / "Downloads" / "heat_network_datasets_2026-09-21.zip",
    )
    parser.add_argument("--out", type=Path, default=ROOT / "results/dataset-audit-2026-09-21.json")
    args = parser.parse_args()

    data_root = args.data_root.resolve()
    errors: list[str] = []
    warnings: list[str] = []
    counters: Counter = Counter()
    extension_counts: Counter = Counter()

    for path in sorted(item for item in data_root.rglob("*") if item.is_file()):
        extension = path.suffix.lower()
        extension_counts[extension or "<none>"] += 1
        counters["files"] += 1
        try:
            if extension in {".json", ".geojson"}:
                data = strict_json(path)
                counters["json_files"] += 1
                if extension == ".geojson":
                    audit_geojson(path, data, errors, warnings)
                    counters["geojson_files"] += 1
                else:
                    audit_manifest(path, data, errors, counters)
            elif extension in {".osm", ".svg"}:
                audit_xml(path)
                counters["xml_files"] += 1
            elif extension == ".docx":
                with zipfile.ZipFile(path) as document:
                    bad = document.testzip()
                    if bad:
                        errors.append(f"{path.relative_to(ROOT)}: DOCX CRC failure: {bad}")
                counters["docx_files"] += 1
        except Exception as exc:  # report every bad source instead of aborting the audit
            errors.append(f"{path.relative_to(ROOT)}: {type(exc).__name__}: {exc}")

    audit_zip(data_root, args.archive.resolve(), errors, counters)
    report = {
        "status": "VALID" if not errors else "INVALID",
        "data_root": str(data_root),
        "archive": str(args.archive.resolve()),
        "archive_sha256": sha256(args.archive.resolve()) if args.archive.is_file() else None,
        "counts": dict(sorted(counters.items())),
        "extensions": dict(sorted(extension_counts.items())),
        "error_count": len(errors),
        "warning_count": len(warnings),
        "errors": errors,
        "warnings": warnings,
    }
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({key: report[key] for key in (
        "status", "counts", "extensions", "error_count", "warning_count", "archive_sha256"
    )}, ensure_ascii=False, indent=2))
    print(args.out)
    return 0 if not errors else 2


if __name__ == "__main__":
    raise SystemExit(main())
