"""Apply the reviewed ITERATION_001 diff, only when every preimage matches.
No network access, no shell evaluation of patch content, no main-branch write.
The branch-scoped workflow commits the verified source files separately.
"""
from pathlib import Path, PurePosixPath
import hashlib
import json
import os
import subprocess

ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent
REPO = "RAMMLS/Mos-hackathon"
BRANCH = "refs/heads/chatgpt/iteration-001"


def blob(path):
    if not path.exists():
        return None
    data = path.read_bytes()
    return hashlib.sha1(b"blob " + str(len(data)).encode() + b"\0" + data).hexdigest()


def safe_path(name):
    path = PurePosixPath(name)
    if path.is_absolute() or ".." in path.parts or not path.parts or ".git" in path.parts:
        raise ValueError("Unsafe path in manifest")
    dest = ROOT.joinpath(*path.parts)
    if not dest.resolve().is_relative_to(ROOT):
        raise ValueError("Path escapes checkout")
    if any(p.is_symlink() for p in (dest, *dest.parents) if p != ROOT):
        raise ValueError("Symlinks are not accepted")
    return dest


def main():
    if os.environ.get("GITHUB_ACTIONS") == "true":
        if os.environ.get("GITHUB_REPOSITORY") != REPO or os.environ.get("GITHUB_REF") != BRANCH:
            raise SystemExit("This publisher is restricted to the iteration branch")
    raw = (HERE / "manifest.json").read_bytes()
    manifest = json.loads(raw)
    stamp = HERE / "APPLIED.json"
    fingerprint = hashlib.sha256(raw).hexdigest()
    if stamp.exists():
        if json.loads(stamp.read_text())["manifest_sha256"] != fingerprint:
            raise SystemExit("Applied manifest differs; do not overwrite later work")
        print("ITERATION_001 already materialized; later source edits are preserved.")
        return
    files = manifest["files"]
    paths = [e["path"] for e in files]
    if len(paths) != len(set(paths)):
        raise SystemExit("Duplicate manifest paths")
    # Check all inputs BEFORE modifying even one file.
    for e in files:
        if blob(safe_path(e["path"])) != e["before"]:
            raise SystemExit("Source differs from reviewed base: " + e["path"])
    payloads = []
    for entry in manifest["patches"]:
        name = entry["name"]
        if Path(name).name != name:
            raise SystemExit("Invalid patch filename")
        data = (HERE / name).read_bytes()
        if hashlib.sha256(data).hexdigest() != entry["sha256"]:
            raise SystemExit("Patch checksum mismatch: " + name)
        payloads.append(data)
    patch = b"".join(payloads)
    # The diffs can be split inside a hunk, so concatenate in manifest order.
    mentioned = set()
    for line in patch.decode("utf-8").splitlines():
        if line.startswith("+++ b/"):
            mentioned.add(line[6:])
    if mentioned != set(paths):
        raise SystemExit("Patch file list differs from allowlist")
    subprocess.run(["git", "apply", "--check", "--whitespace=nowarn", "-"],
                   cwd=ROOT, input=patch, check=True)
    subprocess.run(["git", "apply", "--whitespace=nowarn", "-"],
                   cwd=ROOT, input=patch, check=True)
    for e in files:
        if blob(safe_path(e["path"])) != e["after"]:
            raise SystemExit("Postimage checksum mismatch: " + e["path"])
    stamp.write_text(json.dumps({"iteration":"ITERATION_001", "base_commit":manifest["base_commit"],
        "manifest_sha256":fingerprint, "source_files_verified":len(files),
        "meaning":"Source integrity only; CI results are separate."}, indent=2) + "\n")
    print("Verified and applied", len(files), "source/test/runner files.")


if __name__ == "__main__":
    main()
