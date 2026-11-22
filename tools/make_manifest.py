#!/usr/bin/env python3
"""Write manifest.json for the Hugging Face repo that Eco downloads from.

Usage:
  python3 tools/make_manifest.py --version 1 --model eco-4b-Q6_K.gguf --index eco-index.sqlite > manifest.json

Each file gets its name, role (model / index), size in bytes and SHA-256. Bump --version every
time you publish new files: "Check for updated files" in the app compares this value.
"""
import argparse
import hashlib
import json
import os


def describe(path, role):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return {"name": os.path.basename(path), "role": role, "size": os.path.getsize(path), "sha256": h.hexdigest()}


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--version", required=True)
    ap.add_argument("--model", required=True)
    ap.add_argument("--index", required=True)
    a = ap.parse_args()
    manifest = {"version": a.version, "files": [describe(a.model, "model"), describe(a.index, "index")]}
    print(json.dumps(manifest, indent=2))


if __name__ == "__main__":
    main()
