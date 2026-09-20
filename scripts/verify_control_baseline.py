import hashlib
import json
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SCRIPTS = ROOT / "scripts"
if str(SCRIPTS) not in sys.path:
    sys.path.insert(0, str(SCRIPTS))

from benchmark_checker import check  # noqa: E402


def sha256(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def main():
    baseline_dir = ROOT / "data/control_baselines/b2_c_full"
    manifest = json.loads((baseline_dir / "manifest.json").read_text(encoding="utf-8"))
    input_path = ROOT / manifest["input"]
    output_path = ROOT / manifest["output"]
    if sha256(input_path) != manifest["input_sha256"]:
        raise AssertionError("frozen B2-C input hash mismatch")
    if sha256(output_path) != manifest["output_sha256"]:
        raise AssertionError("frozen B2-C output hash mismatch")
    report = check(input_path, output_path, "frozen-b2-c-control")
    if report.get("status") != "VALID" or report.get("violations"):
        raise AssertionError(f"frozen B2-C no longer passes checker: {report.get('violations')}")
    recomputed = report["recomputed_cost_components"]
    expected = manifest["metrics"]
    for key in ("new_network_length", "calculated_cost", "score"):
        if recomputed[key] != expected[key]:
            raise AssertionError(f"frozen B2-C metric mismatch for {key}")
    geometry = report["geometry_metrics"]
    if geometry["connected_oks_count"] != expected["connected_oks"]:
        raise AssertionError("frozen B2-C connected OKS mismatch")
    print(json.dumps({
        "status": "PASS",
        "baseline_id": manifest["baseline_id"],
        "input_sha256": manifest["input_sha256"],
        "output_sha256": manifest["output_sha256"],
        "score": recomputed["score"],
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
