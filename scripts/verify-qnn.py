#!/usr/bin/env python3
"""Validate the pinned official QAIRT library set before building or packaging."""
import hashlib
import json
from pathlib import Path
import sys


def validate(native_dir):
    manifest = json.loads(Path(__file__).with_name("qnn-sdk-manifest.json").read_text())
    root = Path(native_dir)
    required = {"libQnnHtp.so", "libQnnHtpPrepare.so", "libQnnSystem.so", "libQnnGpu.so"}
    required.update(f"libQnnHtpV{a}{kind}.so" for a in manifest["required_arches"] for kind in ("Skel", "Stub"))
    records = {r["name"]: r for r in manifest["libraries"]}
    errors = []
    for name in sorted(required | {n for n in records if (root / n).exists()}):
        p = root / name
        if name not in records:
            errors.append(f"No trusted manifest entry: {name}")
        elif not p.is_file():
            errors.append(f"Missing QNN library: {p}")
        else:
            r = records[name]
            if p.stat().st_size != r["size"]:
                errors.append(f"QNN size mismatch (mixed or corrupt SDK): {p}")
            else:
                with p.open("rb") as f:
                    actual = hashlib.file_digest(f, "sha256").hexdigest()
                if actual != r["sha256"]:
                    errors.append(f"QNN SHA-256 mismatch (mixed or corrupt SDK): {p}")
    allowed = set(records)
    for p in sorted(root.glob("libQnn*.so")):
        if p.name not in allowed:
            errors.append(f"Unverified extra QNN library: {p}; use a clean directory for the pinned SDK")
    if errors:
        raise SystemExit("\n".join(errors))
    print("QNN verified: " + manifest["sdk_version"] + " / V" + "/".join(map(str, manifest["required_arches"])) + " / same SDK SHA-256")
    return manifest


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("Usage: verify-qnn.py QNN_NATIVE_DIR")
    validate(sys.argv[1])
