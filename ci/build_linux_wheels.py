#!/usr/bin/env python
"""
Host-side wrapper: workflow provides only --arch.
Images are defined as regular Python lists (no env vars).

Environment:
  - GITHUB_WORKSPACE (optional; defaults to cwd)
"""
from __future__ import annotations
import argparse
import os
import shlex
import subprocess
import sys
from typing import List

SCRIPT = r'''set -e
yum install -y git sudo wget || true
git config --global --add safe.directory /work
echo "Using Python from: $(which python || echo not-found)"
echo "Python version: $(python -V 2>&1 || true)"
bash ci/run_ci.sh install_bazel
bash ci/deploy.sh build_pyfory
'''

DEFAULT_X86_IMAGES = [
    "quay.io/pypa/manylinux2014_x86_64:latest",
    # "quay.io/pypa/manylinux_2_28_x86_64:latest",
    "quay.io/pypa/musllinux_1_2_x86_64:latest",
    # add more x86 images here if desired
]

DEFAULT_AARCH64_IMAGES = [
    "quay.io/pypa/manylinux2014_aarch64:latest", # = manylinux2014
    # "quay.io/pypa/manylinux_2_28_aarch64:latest",
    "quay.io/pypa/musllinux_1_2_aarch64:latest", # = manylinux2014
    # add more aarch64 images here if desired
]

ARCH_ALIASES = {
    "X86": "x86",
    "X64": "x86",
    "X86_64": "x86",
    "AMD64": "x86",
    "ARM": "arm64",
    "ARM64": "arm64",
    "AARCH64": "arm64",
}

def parse_args():
    p = argparse.ArgumentParser()
    p.add_argument("--arch", required=True, help="Architecture (e.g. X86, X64, AARCH64)")
    p.add_argument("--dry-run", action="store_true", help="Print docker commands without running")
    return p.parse_args()

def normalize_arch(raw: str) -> str:
    key = raw.strip().upper()
    return ARCH_ALIASES.get(key, raw.strip().lower())

def collect_images_for_arch(arch_normalized: str) -> List[str]:
    if arch_normalized == "x86":
        imgs = DEFAULT_X86_IMAGES  # dedupe preserving order
    elif arch_normalized == "arm64":
        imgs = DEFAULT_AARCH64_IMAGES
    else:
        raise SystemExit(f"Unsupported arch: {arch_normalized!r}")
    return imgs

def build_docker_cmd(workspace: str, image: str) -> List[str]:
    workspace = os.path.abspath(workspace)
    return [
        "docker", "run", "-i", "--rm",
        "-v", f"{workspace}:/work",
        "-w", "/work",
        image,
        "bash", "-s", "--"
    ]

def run_for_images(images: List[str], workspace: str, dry_run: bool) -> int:
    rc_overall = 0
    for image in images:
        docker_cmd = build_docker_cmd(workspace, image)
        printable = " ".join(shlex.quote(c) for c in docker_cmd)
        print(f"+ {printable}")
        if dry_run:
            continue
        try:
            completed = subprocess.run(docker_cmd, input=SCRIPT.encode("utf-8"))
            if completed.returncode != 0:
                print(f"Container {image} exited with {completed.returncode}", file=sys.stderr)
                rc_overall = completed.returncode if rc_overall == 0 else rc_overall
            else:
                print(f"Container {image} completed successfully.")
        except KeyboardInterrupt:
            print("Interrupted by user", file=sys.stderr)
            return 130
        except FileNotFoundError as e:
            print(f"Error running docker: {e}", file=sys.stderr)
            return 2
    return rc_overall

def main() -> int:
    args = parse_args()
    arch = normalize_arch(args.arch)
    images = collect_images_for_arch(arch)
    if not images:
        print(f"No images configured for arch {arch}", file=sys.stderr)
        return 2
    workspace = os.environ.get("GITHUB_WORKSPACE", os.getcwd())
    print(f"Selected images for arch {args.arch}: {images}")
    return run_for_images(images, workspace, args.dry_run)

if __name__ == "__main__":
    sys.exit(main())
