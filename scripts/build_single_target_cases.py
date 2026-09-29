import argparse
import hashlib
import json
from pathlib import Path


def sha256_bytes(payload):
    return hashlib.sha256(payload).hexdigest()


def main():
    parser = argparse.ArgumentParser(
        description="Build single-demand diagnostic cases without changing map geometry."
    )
    parser.add_argument("--input", required=True, type=Path)
    parser.add_argument("--out-dir", required=True, type=Path)
    parser.add_argument("--target", action="append", required=True)
    args = parser.parse_args()

    source_bytes = args.input.read_bytes()
    source = json.loads(source_bytes)
    features = source.get("features", [])
    connection_points = [
        feature
        for feature in features
        if feature.get("properties", {}).get("object_type") == "oks_connection_point"
    ]
    by_id = {
        str(feature.get("properties", {}).get("id")): feature
        for feature in connection_points
    }

    args.out_dir.mkdir(parents=True, exist_ok=True)
    manifest = {
        "source": str(args.input.resolve()),
        "source_sha256": sha256_bytes(source_bytes),
        "source_feature_count": len(features),
        "source_connection_point_ids": sorted(by_id),
        "cases": [],
    }

    for target_id in args.target:
        if target_id not in by_id:
            raise SystemExit(f"Unknown target id: {target_id}")

        case_dir = args.out_dir / f"target-{target_id}"
        case_dir.mkdir(parents=True, exist_ok=True)
        case = dict(source)
        case["features"] = [
            feature
            for feature in features
            if feature.get("properties", {}).get("object_type") != "oks_connection_point"
            or str(feature.get("properties", {}).get("id")) == target_id
        ]
        output_path = case_dir / "input.geojson"
        output_bytes = (json.dumps(case, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
        output_path.write_bytes(output_bytes)

        target = by_id[target_id]
        manifest["cases"].append(
            {
                "target_id": target_id,
                "flow_tph": target.get("properties", {}).get("flow_tph"),
                "coordinates": target.get("geometry", {}).get("coordinates"),
                "output": str(output_path.resolve()),
                "output_sha256": sha256_bytes(output_bytes),
                "feature_count": len(case["features"]),
                "removed_connection_point_ids": sorted(
                    connection_id for connection_id in by_id if connection_id != target_id
                ),
                "map_features_removed": 0,
            }
        )

    manifest_path = args.out_dir / "manifest.json"
    manifest_path.write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    print(manifest_path)


if __name__ == "__main__":
    main()
