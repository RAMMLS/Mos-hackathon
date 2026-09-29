import unittest

from run_multi_scene_algorithm_benchmark import aggregate


class MultiSceneAggregateTest(unittest.TestCase):
    def test_uncertified_request_counts_as_zero_only_in_all_request_coverage(self):
        scenes = [
            {
                "oks_count": 4,
                "algorithms": [{
                    "algorithm": "A", "contract_valid": True,
                    "connected_oks": 2, "total_oks": 4, "score": 1.0,
                }],
            },
            {
                "oks_count": 6,
                "algorithms": [{"algorithm": "A", "contract_valid": False}],
            },
        ]

        result = aggregate(scenes, ["A"])[0]

        self.assertEqual(25.0, result["coverage_macro_all_percent"])
        self.assertEqual(50.0, result["coverage_macro_valid_percent"])
        self.assertEqual(20.0, result["coverage_micro_all_percent"])
        self.assertEqual(1, result["contract_valid_count"])

    def test_cost_components_are_averaged_only_for_certified_results(self):
        scenes = [{
            "oks_count": 2,
            "algorithms": [{
                "algorithm": "A", "contract_valid": True,
                "connected_oks": 2, "total_oks": 2, "score": 1.0,
                "construction_cost": 30.0, "unconnected_penalty": 0.0,
            }],
        }]

        result = aggregate(scenes, ["A"])[0]

        self.assertEqual(30.0, result["mean_construction_cost"])
        self.assertEqual(0.0, result["mean_unconnected_penalty"])


if __name__ == "__main__":
    unittest.main()
