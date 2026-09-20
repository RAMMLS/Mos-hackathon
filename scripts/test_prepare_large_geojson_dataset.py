import json
import unittest
from pathlib import Path

import prepare_large_geojson_dataset as dataset


ROOT = Path(__file__).resolve().parents[1]


class BuildingAnchoredDatasetTest(unittest.TestCase):
    def test_every_connection_point_is_anchored_inside_an_osm_building(self):
        source_path = (
            ROOT / "data/real_geojson_places/shared_pipe/076_innopolis_-1_-3_f0abd3bb.geojson"
        )
        source = json.loads(source_path.read_text(encoding="utf-8"))
        metadata = source["metadata"]
        restrictions = dataset.convert_restrictions(source["features"], source["name"])
        generated = dataset.synthetic_features(
            source["name"], metadata["category"],
            metadata["bbox_south_west_north_east"], restrictions,
        )
        connections = {
            feature["properties"]["id"]: feature
            for feature in generated
            if feature["properties"].get("object_type") == "oks_connection_point"
        }
        buildings = [
            feature for feature in restrictions
            if feature["properties"].get("selected_as_heat_demand_proxy")
        ]

        self.assertEqual(6, len(connections))
        self.assertEqual(len(connections), len(buildings))
        for building in buildings:
            properties = building["properties"]
            connection = connections[properties["connection_point_id"]]
            point = tuple(connection["geometry"]["coordinates"])
            self.assertEqual(properties["osm_id"], connection["properties"]["source_building_osm_id"])
            self.assertEqual("osm_building_heat_demand_proxy", connection["properties"]["consumer_role"])
            self.assertTrue(any(
                dataset.point_in_polygon(point, polygon)
                for polygon in dataset.iter_polygons(building["geometry"])
            ))

    def test_scene_without_enough_buildings_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "not enough real OSM buildings"):
            dataset.synthetic_features(
                "no_buildings", "refusal", [55.0, 37.0, 55.01, 37.01], [],
            )

    def test_many_connection_points_extension_has_twelve_anchored_points(self):
        source_path = (
            ROOT / "data/real_geojson_places/shared_pipe/076_innopolis_-1_-3_f0abd3bb.geojson"
        )
        source = json.loads(source_path.read_text(encoding="utf-8"))
        metadata = source["metadata"]
        restrictions = dataset.convert_restrictions(source["features"], source["name"])
        generated = dataset.synthetic_features(
            source["name"], "many_connection_points",
            metadata["bbox_south_west_north_east"], restrictions,
        )
        connections = [
            feature for feature in generated
            if feature["properties"].get("object_type") == "oks_connection_point"
        ]

        self.assertEqual(12, len(connections))

    def test_large_oks_extension_uses_uncapped_high_loads(self):
        source_path = (
            ROOT / "data/real_geojson_places/separate_pipes/001_moscow_center_-3_-3_fab797d4.geojson"
        )
        source = json.loads(source_path.read_text(encoding="utf-8"))
        metadata = source["metadata"]
        restrictions = dataset.convert_restrictions(source["features"], source["name"])
        generated = dataset.synthetic_features(
            source["name"], "large_oks",
            metadata["bbox_south_west_north_east"], restrictions,
        )
        flows = [
            feature["properties"]["flow_tph"]
            for feature in generated
            if feature["properties"].get("object_type") == "oks_connection_point"
        ]

        self.assertTrue(any(flow > 40.0 for flow in flows))

    def test_tie_in_stress_extension_adds_multiple_existing_chambers(self):
        source_path = (
            ROOT / "data/real_geojson_places/shared_pipe/076_innopolis_-1_-3_f0abd3bb.geojson"
        )
        source = json.loads(source_path.read_text(encoding="utf-8"))
        metadata = source["metadata"]
        restrictions = dataset.convert_restrictions(source["features"], source["name"])
        generated = dataset.synthetic_features(
            source["name"], "tie_in_stress",
            metadata["bbox_south_west_north_east"], restrictions,
        )
        chambers = [
            feature for feature in generated
            if feature["properties"].get("object_type") == "heat_chamber"
        ]

        self.assertGreaterEqual(len(chambers), 3)


if __name__ == "__main__":
    unittest.main()
