import argparse
import json
from decimal import Decimal, getcontext
from pathlib import Path


getcontext().prec = 45
D = Decimal
DEFAULT_SPEC_DIR = Path("data/benchmark_specs")
DEFAULT_OUT = Path("results/numeric-benchmarks/report.json")


class NumericBenchmarks:
    def __init__(self, spec_dir):
        self.spec_dir = Path(spec_dir)
        self.catalog = json.loads(
            (self.spec_dir / "rule_catalog.json").read_text(encoding="utf-8"),
            parse_float=Decimal,
            parse_int=int,
        )
        self.pipes = {int(row["diameter_mm"]): row for row in self.catalog["pipe_catalog"]}
        self.assertions = 0

    def check(self, condition, message):
        self.assertions += 1
        if not condition:
            raise AssertionError(message)

    @staticmethod
    def dec(value):
        return value if isinstance(value, Decimal) else D(str(value))

    def diameter(self, flow):
        q = self.dec(flow)
        if q < 0:
            raise ValueError("Negative flow")
        for diameter, row in self.pipes.items():
            if self.dec(row["capacity_tph"]) >= q:
                return diameter
        raise ValueError("Flow exceeds largest pipe in the case catalog")

    def new_cost(self, length, diameter):
        return self.dec(length) * self.dec(self.pipes[diameter]["new_rate_rub_per_m"])

    def reconstruction_cost(self, length, diameter):
        return self.dec(length) * self.dec(self.pipes[diameter]["reconstruction_rate_rub_per_m"])

    def score(self, cost, length):
        return D(".7") * self.dec(cost) / D(25_000_000) + D(".3") * self.dec(length) / D(100)

    def bundle(self, cost, length):
        return {
            "cost_rub": self.dec(cost),
            "linear_work_length_m": self.dec(length),
            "score": self.score(cost, length),
        }

    def run(self):
        results = {}
        self.check(len(self.pipes) == 18, "18 catalog diameters expected")
        previous_capacity = D("-1")
        for diameter, row in self.pipes.items():
            capacity = self.dec(row["capacity_tph"])
            self.check(capacity > previous_capacity, "Capacities must increase")
            self.check(self.diameter(capacity) == diameter, f"Boundary flow must select diameter {diameter}")
            previous_capacity = capacity

        self.check(self.diameter("3.500001") == 65, "Just above DN50 capacity must select DN65")
        self.check(self.diameter("22.300001") == 125, "Just above DN100 capacity must select DN125")
        for flow in ("-1", "22502"):
            try:
                self.diameter(flow)
            except ValueError:
                self.check(True, "Rejected invalid flow")
            else:
                self.check(False, f"Invalid flow accepted: {flow}")

        separate = self.bundle(2 * self.new_cost(100, 50) + D(10_000_000), 200)
        shared = self.bundle(2 * self.new_cost(30, 50) + self.new_cost(80, 65) + D(3_000_000) + D(5_000_000), 140)
        self.check(self.diameter(3) == 50 and self.diameter(6) == 65, "Template flow diameters")
        self.check(separate["cost_rub"] == D(24_804_600), "Separate cost")
        self.check(shared["cost_rub"] == D(18_731_860), "Shared cost")
        self.check(shared["score"] < separate["score"], "Shared template should win")
        results["SHARED_WINS_FOR_THESE_TEMPLATES"] = {
            "assumptions": "2 OKS of 3 tph each; existing chamber capacity/degree sufficient; no specials or reconstruction; geometry not supplied",
            "separate": separate,
            "shared": shared,
            "scope": "comparison of two prescribed candidate networks, not proof of global geometric optimality",
        }

        shared_long = self.bundle(2 * self.new_cost(80, 50) + self.new_cost(80, 65) + D(8_000_000), 240)
        self.check(shared_long["cost_rub"] == D(26_134_160), "Long shared template cost")
        self.check(separate["score"] < shared_long["score"], "Separate template should beat long shared template")
        results["SEPARATE_WINS_FOR_THESE_TEMPLATES"] = {
            "separate": separate,
            "shared_long": shared_long,
            "scope": "only these two route templates are compared; another shared route might be better",
        }

        near = self.bundle(self.new_cost(60, 100) + self.reconstruction_cost(100, 125) + D(5_000_000) + D(3_000_000), 160)
        far = self.bundle(self.new_cost(100, 100) + D(5_000_000), 100)
        self.check(self.diameter(15) == 100 and self.diameter(35) == 125, "Near/far flow thresholds")
        self.check(near["cost_rub"] == D(28_187_880), "Near tie-in cost")
        self.check(far["cost_rub"] == D(13_974_800), "Far tie-in cost")
        self.check(far["score"] < near["score"], "Far tie-in should win")
        results["FAR_TIE_IN_WINS"] = {
            "near": near,
            "far": far,
            "assumptions": "Near: 60m new DN100; 100m old DN100->DN125 and tie-in chamber reconstruction. Far: 100m new DN100; existing capacity sufficient. All paths assumed geometrically admissible.",
        }

        ties = [(D(30), D(3)), (D(80), D(5))]
        boundaries = [D(0), D(30), D(80), D(100)]
        intervals = []
        total_reconstruction = D(0)
        for lo, hi in zip(boundaries, boundaries[1:]):
            mid = (lo + hi) / 2
            added = sum((q for point, q in ties if mid <= point), D(0))
            required = self.diameter(D(20) + added)
            cost = self.reconstruction_cost(hi - lo, required) if required > 100 else D(0)
            total_reconstruction += cost
            intervals.append(
                {
                    "start_from_upstream_m": lo,
                    "end_m": hi,
                    "added_flow_tph": added,
                    "final_flow_tph": D(20) + added,
                    "required_diameter_mm": required,
                    "reconstruction_cost_rub": cost,
                }
            )
        self.check([row["added_flow_tph"] for row in intervals] == [D(8), D(5), D(0)], "Interval flow distribution")
        self.check(total_reconstruction == D(11_842_400), "80m reconstructed exactly once")
        self.check(intervals[0]["added_flow_tph"] != intervals[1]["added_flow_tph"], "Equal DN does not mean equal exported attributes")
        results["PARTIAL_RECONSTRUCTION"] = {
            "intervals": intervals,
            "total_reconstruction_cost_rub": total_reconstruction,
        }

        points = [D(0), D(10), D(100)]
        weights = [D(1), D(1), D(8)]
        mean = sum((point * weight for point, weight in zip(points, weights)), D(0)) / sum(weights)

        def weighted_distance(x):
            return sum((weight * abs(point - x) for point, weight in zip(points, weights)), D(0))

        median = min(points, key=weighted_distance)
        self.check(mean == D(81) and median == D(100), "Weighted mean vs discrete weighted median")
        self.check(weighted_distance(mean) == D(304), "Mean weighted linear-distance objective")
        self.check(weighted_distance(median) == D(190), "Median weighted linear-distance objective")
        results["WEIGHTED_CENTER_NOT_MEAN"] = {
            "points": points,
            "weights": weights,
            "weighted_mean": mean,
            "median_among_candidates": median,
            "linear_distance_at_mean": weighted_distance(mean),
            "linear_distance_at_median": weighted_distance(median),
            "scope": "1-D weighted-distance calculation; not a monetary or geometric heat-network optimizer",
        }

        current_scores = [D(10), D(9), D("9.6"), D(8)]
        best_scores = []
        best = current_scores[0]
        for current in current_scores:
            best = min(best, current)
            best_scores.append(best)
        rewards = [(before - after) / D(10) for before, after in zip(best_scores, best_scores[1:])]
        self.check(rewards == [D(".1"), D(0), D(".1")], "Best-improvement reward")
        self.check(sum(rewards) == D(".2"), "Telescoping best-improvement return")
        results["RL_REWARD_TELESCOPING"] = {
            "current_scores": current_scores,
            "best_scores": best_scores,
            "Z": D(10),
            "rewards": rewards,
            "return_gamma_1": sum(rewards),
        }

        lambda_length = D(".3") / D(100) * D(25_000_000) / D(".7")
        for record in (separate, shared, shared_long, near, far):
            equivalent_objective = record["cost_rub"] + lambda_length * record["linear_work_length_m"]
            expected = record["score"] * D(25_000_000) / D(".7")
            self.check(abs(equivalent_objective - expected) < D("1e-30"), "Score/J equivalence")
        results["SCORE_EQUIVALENCE"] = {
            "lambda_length_rub_per_m": lambda_length,
            "scope": "J is an equivalent ranking objective, not actual construction cost",
        }

        return {
            "status": "ARITHMETIC_CHECKS_PASSED",
            "assertions_passed": self.assertions,
            "numeric_templates_checked": len(results),
            "numeric_values_serialized_as_exact_decimal_strings": True,
            "geometry_checked": False,
            "java_solver_executed": False,
            "rl_trained": False,
            "real_dataset_analyzed": False,
            "cases": results,
        }


def stringify(value):
    if isinstance(value, Decimal):
        return str(value)
    if isinstance(value, dict):
        return {key: stringify(item) for key, item in value.items()}
    if isinstance(value, list):
        return [stringify(item) for item in value]
    return value


def compare_to_reference(spec_dir, report):
    reference_path = Path(spec_dir) / "numeric_reference_results.json"
    if not reference_path.exists():
        return [{"code": "REFERENCE_MISSING", "message": f"{reference_path} not found"}]

    reference = json.loads(reference_path.read_text(encoding="utf-8"))
    mismatches = []
    for key in (
        "status",
        "assertions_passed",
        "numeric_templates_checked",
        "numeric_values_serialized_as_exact_decimal_strings",
        "geometry_checked",
        "java_solver_executed",
        "rl_trained",
        "real_dataset_analyzed",
        "cases",
    ):
        if report.get(key) != reference.get(key):
            mismatches.append(
                {
                    "code": "REFERENCE_MISMATCH",
                    "field": key,
                    "expected": reference.get(key),
                    "actual": report.get(key),
                }
            )
    return mismatches


def main():
    parser = argparse.ArgumentParser(description="Run exact arithmetic benchmark templates from data/benchmark_specs.")
    parser.add_argument("--spec-dir", default=str(DEFAULT_SPEC_DIR), help="Directory with rule_catalog.json and numeric_reference_results.json")
    parser.add_argument("--out", default=str(DEFAULT_OUT), help="Report JSON path")
    parser.add_argument("--no-fail", action="store_true", help="Do not return non-zero exit code on reference mismatch")
    args = parser.parse_args()

    raw_report = NumericBenchmarks(args.spec_dir).run()
    report = stringify(raw_report)
    mismatches = compare_to_reference(args.spec_dir, report)
    report["reference_status"] = "MATCH" if not mismatches else "MISMATCH"
    report["reference_mismatches"] = mismatches

    out_path = Path(args.out)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    print(
        json.dumps(
            {
                "status": report["status"],
                "assertions_passed": report["assertions_passed"],
                "numeric_templates_checked": report["numeric_templates_checked"],
                "reference_status": report["reference_status"],
                "out": str(out_path),
            },
            ensure_ascii=False,
        )
    )
    if mismatches and not args.no_fail:
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
