#!/usr/bin/env python

# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.

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

# Define Python version sets directly in the Python script
RELEASE_PYTHON_VERSIONS = "cp38-cp38 cp39-cp39 cp310-cp310 cp311-cp311 cp312-cp312 cp313-cp313"
DEFAULT_PYTHON_VERSIONS = "cp38-cp38 cp313-cp313"

# Path to the container build script
CONTAINER_SCRIPT_PATH = "ci/tasks/python_container_build_script.sh"

DEFAULT_X86_IMAGES = [
    "quay.io/pypa/manylinux2014_x86_64:latest",
    # "quay.io/pypa/manylinux_2_28_x86_64:latest",

    # bazel binaries do not work with musl
    # "quay.io/pypa/musllinux_1_2_x86_64:latest",
]

DEFAULT_AARCH64_IMAGES = [
    "quay.io/pypa/manylinux2014_aarch64:latest",
    # "quay.io/pypa/manylinux_2_28_aarch64:latest",

    # bazel binaries do not work with musl
    # "quay.io/pypa/musllinux_1_2_aarch64:latest",
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
    p.add_argument("--release", action="store_true", help="Run full test suite for release")
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

def build_docker_cmd(workspace: str, image: str, release: bool = False) -> List[str]:
    workspace = os.path.abspath(workspace)
    python_versions = RELEASE_PYTHON_VERSIONS if release else DEFAULT_PYTHON_VERSIONS

    # Get GitHub reference name from environment
    github_ref_name = os.environ.get("GITHUB_REF_NAME", "")

    cmd = [
        "docker", "run", "-i", "--rm",
        "-v", f"{workspace}:/work", # (v)olume
        "-w", "/work",  # (w)orking directory
        "-e", f"PYTHON_VERSIONS={python_versions}", # (e)nvironment variables
    ]

    # Pass GitHub reference name if available
    if github_ref_name:
        cmd.extend(["-e", f"GITHUB_REF_NAME={github_ref_name}"])

    cmd.extend([image, "bash", CONTAINER_SCRIPT_PATH])
    return cmd

def run_for_images(images: List[str], workspace: str, dry_run: bool, release: bool = False) -> int:
    rc_overall = 0
    for image in images:
        docker_cmd = build_docker_cmd(workspace, image, release=release)
        printable = " ".join(shlex.quote(c) for c in docker_cmd)
        print(f"+ {printable}")
        if dry_run:
            continue
        try:
            completed = subprocess.run(docker_cmd)
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

    # Check if the container script exists
    script_path = os.path.join(workspace, CONTAINER_SCRIPT_PATH)
    if not os.path.exists(script_path):
        print(f"Container script not found at {script_path}", file=sys.stderr)
        return 2

    print(f"Selected images for arch {args.arch}: {images}")
    return run_for_images(images, workspace, args.dry_run, release=args.release)

if __name__ == "__main__":
    sys.exit(main())
