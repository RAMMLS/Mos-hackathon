#!/usr/bin/env python3
"""Build a reproducibility manifest for an algorithm benchmark run."""

from __future__ import annotations

import hashlib
import json
import platform
import os
import subprocess
import sys
import time
from pathlib import Path


MANIFEST_SCHEMA = "heat-network-benchmark-run-v1"
CANDIDATE_GENERATOR_VERSION = "route-candidate-v5-ruleset-aware"
TOPOLOGIZER_VERSION = "tree-repair-v2-parallel-branch"


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def relative_path(root: Path, path: Path) -> str:
    try:
        return path.resolve().relative_to(root.resolve()).as_posix()
    except ValueError:
        return str(path.resolve())


def file_record(root: Path, path: Path) -> dict:
    resolved = path.resolve()
    return {
        "path": relative_path(root, resolved),
        "bytes": resolved.stat().st_size,
        "sha256": sha256_file(resolved),
    }


def tree_records(root: Path, directory: Path, pattern: str) -> list[dict]:
    if not directory.exists():
        return []
    return [file_record(root, path) for path in sorted(directory.rglob(pattern)) if path.is_file()]


def existing_records(root: Path, paths: list[Path]) -> list[dict]:
    return [file_record(root, path) for path in paths if path.is_file()]


def combined_sha256(records: list[dict]) -> str:
    canonical = json.dumps(records, ensure_ascii=True, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


def git_output(root: Path, *args: str) -> str | None:
    try:
        completed = subprocess.run(
            ["git", *args], cwd=root, check=True, capture_output=True, text=True,
        )
    except (OSError, subprocess.CalledProcessError):
        return None
    return completed.stdout.strip()


def build_run_manifest(
    root: Path,
    input_paths: list[Path],
    algorithms: list[str],
    configuration: dict,
) -> dict:
    root = root.resolve()
    inputs = [file_record(root, path) for path in sorted({path.resolve() for path in input_paths})]
    checker_files = [file_record(root, root / "scripts" / "benchmark_checker.py")]
    rule_files = existing_records(root, [
        root / "data" / "benchmark_specs" / "rule_catalog.json",
        root / "data" / "benchmark_specs" / "benchmark_cases.json",
        root / "docs" / "benchmark_specs" / "OUTPUT_CONTRACT.md",
    ])
    build_files = existing_records(root, [root / "pom.xml", root / "Dockerfile"])
    solver_files = tree_records(
        root, root / "src" / "main" / "java" / "ru" / "moshackathon" / "heatnetwork", "*.java"
    )
    resource_files = tree_records(root, root / "src" / "main" / "resources", "*")
    benchmark_files = existing_records(root, [
        root / "scripts" / "run_manifest.py",
        root / "scripts" / "run_algorithm_benchmark.py",
        root / "scripts" / "run_multi_scene_algorithm_benchmark.py",
    ])
    code_files = [*solver_files, *resource_files, *benchmark_files]
    model_files = tree_records(root, root / "models", "*")
    export_files = existing_records(root, [
        root / "src" / "main" / "java" / "ru" / "moshackathon" / "heatnetwork"
        / "geo" / "GeoJsonWriter.java",
        root / "src" / "main" / "java" / "ru" / "moshackathon" / "heatnetwork"
        / "geo" / "ExportGeometryNormalizer.java",
    ])
    config_canonical = json.dumps(
        configuration, ensure_ascii=True, sort_keys=True, separators=(",", ":")
    ).encode("utf-8")

    git_status = git_output(root, "status", "--porcelain=v1")
    stable = {
        "schema": MANIFEST_SCHEMA,
        "inputs": inputs,
        "algorithms": list(algorithms),
        "configuration": configuration,
        "components": {
            "checker": {
                "sha256": combined_sha256(checker_files),
                "files": checker_files,
            },
            "rules": {
                "sha256": combined_sha256(rule_files),
                "files": rule_files,
            },
            "solver": {
                "sha256": combined_sha256(solver_files),
                "files": solver_files,
            },
            "code": {
                "sha256": combined_sha256(code_files),
                "files": code_files,
            },
            "models": {
                "sha256": combined_sha256(model_files),
                "files": model_files,
            },
            "build": {
                "sha256": combined_sha256(build_files),
                "files": build_files,
            },
            "export_profile": {
                "sha256": combined_sha256(export_files),
                "files": export_files,
            },
            "route_cache": {
                "persistence": "per-request-memory-only",
                "implementation": "src/main/java/ru/moshackathon/heatnetwork/solver/RoutePlanner.java",
                "implementation_sha256": sha256_file(
                    root / "src" / "main" / "java" / "ru" / "moshackathon"
                    / "heatnetwork" / "solver" / "RoutePlanner.java"
                ),
            },
        },
        "identifiers": {
            "input_sha256": combined_sha256(inputs),
            "ruleset_id": configuration.get("ruleset", "DOCUMENT_NEAREST_V1"),
            "checker_sha256": combined_sha256(checker_files),
            "code_sha256": combined_sha256(code_files),
            "config_sha256": hashlib.sha256(config_canonical).hexdigest(),
            "model_sha256": combined_sha256(model_files),
            "candidate_generator_version": CANDIDATE_GENERATOR_VERSION,
            "topologizer_version": TOPOLOGIZER_VERSION,
            "export_profile_sha256": combined_sha256(export_files),
            "budget_seconds": configuration.get("timeout_seconds"),
            "seed": configuration.get("seed"),
            "workers": configuration.get("workers", 1),
        },
        "git": {
            "commit": git_output(root, "rev-parse", "HEAD"),
            "dirty": bool(git_status),
            "status_sha256": hashlib.sha256((git_status or "").encode("utf-8")).hexdigest(),
        },
    }
    configuration_id = hashlib.sha256(
        json.dumps(stable, ensure_ascii=True, sort_keys=True, separators=(",", ":")).encode("utf-8")
    ).hexdigest()
    return {
        **stable,
        "configuration_id": configuration_id,
        "generated_at": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
        "runtime": {
            "python": sys.version.split()[0],
            "platform": platform.platform(),
            "machine": platform.machine(),
            "processor": platform.processor(),
            "cpu_count": os.cpu_count(),
        },
    }


def write_run_manifest(path: Path, manifest: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
