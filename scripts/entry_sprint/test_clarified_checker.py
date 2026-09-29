"""Regression checks for the clarified exterior-only OKS entry rule."""

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from benchmark_checker import (
    CLARIFIED_EXTERIOR_BOUNDARY_V1,
    DOCUMENT_NEAREST_V1,
    EXPERIMENTAL_ANY_BOUNDARY_V1,
    to_metric,
    validate_oks_endpoint_approach,
)


class ClarifiedEntryCheckerTest(unittest.TestCase):
    def test_farther_exterior_entry_is_allowed(self):
        polygon = [[(39, 55), (39.0002, 55), (39.0002, 55.0002),
                    (39, 55.0002), (39, 55)]]
        line = [to_metric(point) for point in [(39.00002, 55.0001),
                                               (39.0004, 55.0001)]]
        self.assertFalse(validate_oks_endpoint_approach(
            line, polygon, True, DOCUMENT_NEAREST_V1)[0])
        self.assertTrue(validate_oks_endpoint_approach(
            line, polygon, True, CLARIFIED_EXTERIOR_BOUNDARY_V1)[0])

    def test_courtyard_hole_is_not_an_exterior_entry(self):
        polygon = [
            [(39, 55), (39.001, 55), (39.001, 55.001),
             (39, 55.001), (39, 55)],
            [(39.0002, 55.0002), (39.0008, 55.0002),
             (39.0008, 55.0008), (39.0002, 55.0008),
             (39.0002, 55.0002)],
        ]
        line = [to_metric(point) for point in [(39.00019, 55.0005),
                                               (39.0005, 55.0005)]]
        self.assertTrue(validate_oks_endpoint_approach(
            line, polygon, True, EXPERIMENTAL_ANY_BOUNDARY_V1)[0])
        self.assertFalse(validate_oks_endpoint_approach(
            line, polygon, True, CLARIFIED_EXTERIOR_BOUNDARY_V1)[0])


if __name__ == "__main__":
    unittest.main()
