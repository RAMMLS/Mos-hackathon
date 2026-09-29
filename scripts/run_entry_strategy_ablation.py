import argparse
import json
import time
from pathlib import Path

from run_algorithm_benchmark import run_algorithm
from run_benchmark_suite import write_json


STRATEGIES = ("PORTAL_ONLY", "DIRECT_ALLOWED")


def pct(saved, baseline):
    if baseline in (None, 0) or saved is None:
        return None
    return round(100.0 * saved / baseline, 2)


def render_markdown(summary):
    lines = [
        "# Endpoint entry strategy A/B benchmark",
        "",
        f"- algorithm: `{summary['algorithm']}`",
        f"- ruleset: `{summary['ruleset']}`",
        f"- generated_at: `{summary['generated_at']}`",
        "- comparison: `PORTAL_ONLY` versus `DIRECT_ALLOWED`",
        "",
        "| scene | portal oks | direct oks | portal length | direct length | saved m | saved % | portal cost | direct cost | saved rub | saved % |",
        "| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |",
    ]
    for row in summary["scenes"]:
        portal = row["PORTAL_ONLY"]
        direct = row["DIRECT_ALLOWED"]
        lines.append(
            "| {scene} | {po}/{pt} | {do}/{dt} | {pl} | {dl} | {ls} | {lp} | {pc} | {dc} | {cs} | {cp} |".format(
                scene=row["scene"], po=portal.get("connected_oks", "-"),
                pt=portal.get("total_oks", "-"), do=direct.get("connected_oks", "-"),
                dt=direct.get("total_oks", "-"), pl=portal.get("new_network_length", "-"),
                dl=direct.get("new_network_length", "-"), ls=row.get("length_saved_m", "-"),
                lp=row.get("length_saved_pct", "-"), pc=portal.get("calculated_cost", "-"),
                dc=direct.get("calculated_cost", "-"), cs=row.get("cost_saved_rub", "-"),
                cp=row.get("cost_saved_pct", "-"),
            )
        )
    aggregate = summary["aggregate"]
    lines.extend([
        "",
        "## Aggregate",
        "",
        f"- scenes with equal coverage: `{aggregate['equal_coverage_scenes']}`",
        f"- scenes where direct entry connected more OKS: `{aggregate['coverage_wins']}`",
        f"- additional OKS connected: `{aggregate['additional_oks_connected']}`",
        f"- direct wins: `{aggregate['direct_wins']}`",
        f"- equal: `{aggregate['equal']}`",
        f"- portal wins: `{aggregate['portal_wins']}`",
        f"- total length saved: `{aggregate['length_saved_m']}` m (`{aggregate['length_saved_pct']}`%)",
        f"- total cost saved: `{aggregate['cost_saved_rub']}` rub (`{aggregate['cost_saved_pct']}`%)",
        "",
        "Positive savings mean that allowing the final segment through its own OKS footprint improved the result.",
    ])
    return "\n".join(lines) + "\n"


def main():
    parser = argparse.ArgumentParser(description="A/B test endpoint entry strategies.")
    parser.add_argument("inputs", nargs="+")
    parser.add_argument("--algorithm", default="B3")
    parser.add_argument("--out-dir", default="results/entry-strategy-ablation")
    parser.add_argument("--service-url", default="http://localhost:8080/api/trace")
    parser.add_argument("--timeout", type=int, default=120)
    parser.add_argument(
        "--ruleset",
        choices=["DOCUMENT_NEAREST_V1", "EXPERIMENTAL_ANY_BOUNDARY_V1"],
        default="DOCUMENT_NEAREST_V1",
    )
    args = parser.parse_args()

    out_dir = Path(args.out_dir)
    scenes = []
    for value in args.inputs:
        input_path = Path(value)
        scene_dir = out_dir / input_path.stem
        row = {"scene": input_path.stem, "input": str(input_path)}
        for strategy in STRATEGIES:
            print(f"{input_path.stem}: {strategy}", flush=True)
            row[strategy] = run_algorithm(
                args.algorithm, input_path, args.service_url,
                scene_dir / strategy.lower(), args.timeout, strategy, args.ruleset)
        portal = row["PORTAL_ONLY"]
        direct = row["DIRECT_ALLOWED"]
        if portal.get("benchmark_eligible") and direct.get("benchmark_eligible"):
            length_saved = round(portal["new_network_length"] - direct["new_network_length"], 2)
            cost_saved = round(portal["calculated_cost"] - direct["calculated_cost"], 2)
            row.update({
                "length_saved_m": length_saved,
                "length_saved_pct": pct(length_saved, portal["new_network_length"]),
                "cost_saved_rub": cost_saved,
                "cost_saved_pct": pct(cost_saved, portal["calculated_cost"]),
            })
        scenes.append(row)

    comparable = [
        row for row in scenes
        if row["PORTAL_ONLY"].get("contract_valid")
        and row["DIRECT_ALLOWED"].get("contract_valid")
        and row["PORTAL_ONLY"].get("connected_oks") == row["DIRECT_ALLOWED"].get("connected_oks")
    ]
    for row in comparable:
        portal = row["PORTAL_ONLY"]
        direct = row["DIRECT_ALLOWED"]
        length_saved = round(portal["new_network_length"] - direct["new_network_length"], 2)
        cost_saved = round(portal["calculated_cost"] - direct["calculated_cost"], 2)
        row.update({
            "length_saved_m": length_saved,
            "length_saved_pct": pct(length_saved, portal["new_network_length"]),
            "cost_saved_rub": cost_saved,
            "cost_saved_pct": pct(cost_saved, portal["calculated_cost"]),
        })
    portal_length = sum(row["PORTAL_ONLY"]["new_network_length"] for row in comparable)
    portal_cost = sum(row["PORTAL_ONLY"]["calculated_cost"] for row in comparable)
    length_saved = round(sum(row["length_saved_m"] for row in comparable), 2)
    cost_saved = round(sum(row["cost_saved_rub"] for row in comparable), 2)
    summary = {
        "generated_at": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
        "algorithm": args.algorithm,
        "ruleset": args.ruleset,
        "scenes": scenes,
        "aggregate": {
            "equal_coverage_scenes": len(comparable),
            "coverage_wins": sum(
                row["DIRECT_ALLOWED"].get("connected_oks", -1)
                > row["PORTAL_ONLY"].get("connected_oks", -1)
                for row in scenes
            ),
            "additional_oks_connected": sum(
                max(0, row["DIRECT_ALLOWED"].get("connected_oks", 0)
                    - row["PORTAL_ONLY"].get("connected_oks", 0))
                for row in scenes
            ),
            "direct_wins": sum(row["cost_saved_rub"] > 0.01 for row in comparable),
            "equal": sum(abs(row["cost_saved_rub"]) <= 0.01 for row in comparable),
            "portal_wins": sum(row["cost_saved_rub"] < -0.01 for row in comparable),
            "length_saved_m": length_saved,
            "length_saved_pct": pct(length_saved, portal_length),
            "cost_saved_rub": cost_saved,
            "cost_saved_pct": pct(cost_saved, portal_cost),
        },
    }
    out_dir.mkdir(parents=True, exist_ok=True)
    write_json(out_dir / "summary.json", summary)
    (out_dir / "summary.md").write_text(render_markdown(summary), encoding="utf-8")
    print(out_dir / "summary.md", flush=True)


if __name__ == "__main__":
    main()
